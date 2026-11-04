package com.kaizten.sheriff.infrastructure.mcp.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.infrastructure.mcp.ServerVersion;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four MCP methods that matter, and the one rule that holds across all
 * of them: a notification is never answered.
 */
final class JsonRpcServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonRpcServer server(String toolText, boolean toolIsError) {
        return new JsonRpcServer(
                () -> List.of(Map.of("name", "sheriff_test", "description", "runs Sheriff", "inputSchema", Map.of())),
                (name, arguments) -> new ToolOutcome(toolText, toolIsError),
                "sheriff");
    }

    private JsonNode request(String method, Map<String, Object> params) throws Exception {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", 1);
        node.put("method", method);
        node.set("params", JSON.valueToTree(params));
        return node;
    }

    @Test
    void initializeAnswersWithTheServerIdentityAndAFallbackVersion() throws Exception {
        ObjectNode response = server("", false).handle(request("initialize", Map.of()));

        assertEquals("2025-11-25", response.at("/result/protocolVersion").asText());
        assertEquals("sheriff", response.at("/result/serverInfo/name").asText());
    }

    @Test
    @DisplayName("the handshake names the version the build recorded, not one written by hand")
    void initializeAnswersWithTheBuiltVersion() throws Exception {
        ObjectNode response = server("", false).handle(request("initialize", Map.of()));

        String version = response.at("/result/serverInfo/version").asText();
        assertEquals(ServerVersion.current(), version);
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), version);
    }

    @Test
    @DisplayName("every version it supports is answered as asked, the one Claude Code asks for included")
    void initializeEchoesBackAVersionItSupports() throws Exception {
        for (String version : List.of("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")) {
            ObjectNode response = server("", false).handle(request("initialize", Map.of("protocolVersion", version)));

            assertEquals(version, response.at("/result/protocolVersion").asText());
        }
    }

    @Test
    @DisplayName("a version this server does not know is answered with its own, never echoed")
    void initializeAnswersAnUnknownVersionWithItsOwn() throws Exception {
        ObjectNode response = server("", false).handle(request("initialize", Map.of("protocolVersion", "1999-01-01")));

        assertEquals("2025-11-25", response.at("/result/protocolVersion").asText());
    }

    @Test
    void initializeCarriesTheInstructionsWhenThereAreAny() throws Exception {
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> new ToolOutcome("", false),
                "sheriff", "check before writing");

        ObjectNode response = server.handle(request("initialize", Map.of()));

        assertEquals("check before writing", response.at("/result/instructions").asText());
    }

    @Test
    void initializeLeavesInstructionsOutWhenThereAreNone() throws Exception {
        ObjectNode response = server("", false).handle(request("initialize", Map.of()));

        assertTrue(response.at("/result/instructions").isMissingNode());
    }

    @Test
    void pingAnswersWithAnEmptyResult() throws Exception {
        ObjectNode response = server("", false).handle(request("ping", Map.of()));

        assertTrue(response.get("result").isObject());
        assertTrue(response.get("result").isEmpty());
    }

    @Test
    void toolsListReturnsEveryDefinition() throws Exception {
        ObjectNode response = server("", false).handle(request("tools/list", Map.of()));

        assertEquals(1, response.at("/result/tools").size());
        assertEquals("sheriff_test", response.at("/result/tools/0/name").asText());
    }

    @Test
    void toolsCallCarriesTheHandlersTextAndErrorFlag() throws Exception {
        ObjectNode response = server("3 error(s) found", true).handle(
                request("tools/call", Map.of("name", "sheriff_test", "arguments", Map.of())));

        assertEquals("3 error(s) found", response.at("/result/content/0/text").asText());
        assertTrue(response.at("/result/isError").asBoolean());
    }

    @Test
    void aNotificationWithNoIdIsNeverAnswered() throws Exception {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", "notifications/initialized");

        assertNull(server("", false).handle(node));
    }

    @Test
    void anUnknownMethodIsMethodNotFound() throws Exception {
        ObjectNode response = server("", false).handle(request("something/else", Map.of()));

        assertEquals(-32601, response.at("/error/code").asInt());
    }

    @Test
    @DisplayName("a ping is answered while a tool call is still running, and the call is answered before serve returns")
    void aPingIsAnsweredWhileACallRuns() throws Exception {
        CountDownLatch pinged = new CountDownLatch(1);
        List<String> lines = new CopyOnWriteArrayList<>();
        OutputStream recording = new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int character) {
                if (character != '\n') {
                    line.append((char) character);
                    return;
                }
                lines.add(line.toString());
                if (line.toString().contains("\"id\":2")) {
                    pinged.countDown();
                }
                line.setLength(0);
            }
        };
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            try {
                return new ToolOutcome(pinged.await(10, TimeUnit.SECONDS) ? "after the ping" : "blocked it", false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new ToolOutcome("interrupted", true);
            }
        }, "sheriff");
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"t\"}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n";

        server.serve(new BufferedReader(new StringReader(input)),
                new PrintStream(recording, true, StandardCharsets.UTF_8));

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"id\":2"), lines.toString());
        assertTrue(lines.get(1).contains("\"id\":1") && lines.get(1).contains("after the ping"), lines.toString());
    }

    @Test
    @DisplayName("a tool that answers at once is not queued behind a long call, as sheriff_guidelines was behind "
            + "a 7-minute sheriff_test")
    void aQuickToolIsNotQueuedBehindALongCall() throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        List<String> lines = new CopyOnWriteArrayList<>();
        OutputStream recording = new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int character) {
                if (character != '\n') {
                    line.append((char) character);
                    return;
                }
                lines.add(line.toString());
                if (line.toString().contains("\"id\":2")) {
                    answered.countDown();
                }
                line.setLength(0);
            }
        };
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            if (name.equals("sheriff_guidelines")) {
                return new ToolOutcome("the rules", false);
            }
            try {
                return new ToolOutcome(answered.await(10, TimeUnit.SECONDS) ? "after the rules" : "held them", false);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new ToolOutcome("interrupted", true);
            }
        }, "sheriff_guidelines"::equals, "sheriff", "");
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"sheriff_test\"}}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"sheriff_guidelines\"}}\n";

        server.serve(new BufferedReader(new StringReader(input)),
                new PrintStream(recording, true, StandardCharsets.UTF_8));

        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"id\":2") && lines.get(0).contains("the rules"), lines.toString());
        assertTrue(lines.get(1).contains("\"id\":1") && lines.get(1).contains("after the rules"), lines.toString());
    }

    @Test
    @DisplayName("a tool that cannot load its own classes is answered, saying to restart, not left hanging")
    void aCallWhoseCodeWasReplacedIsAnswered() throws Exception {
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            throw new NoClassDefFoundError("com/fasterxml/jackson/databind/util/TokenBuffer$Parser");
        }, "sheriff");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        server.serve(new BufferedReader(new StringReader(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"sheriff_task\"}}\n")),
                new PrintStream(output, true, StandardCharsets.UTF_8));

        JsonNode response = JSON.readTree(output.toString(StandardCharsets.UTF_8).strip());
        assertEquals(7, response.path("id").asInt());
        assertEquals(-32603, response.at("/error/code").asInt());
        String message = response.at("/error/message").asText();
        assertTrue(message.contains("NoClassDefFoundError"), message);
        assertTrue(message.contains("Restart this session"), message);
    }

    @Test
    @DisplayName("any other Error in a tool is answered as an internal error")
    void anyErrorInACallIsAnswered() throws Exception {
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            throw new AssertionError("broken");
        }, "sheriff");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        server.serve(new BufferedReader(new StringReader(
                "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{\"name\":\"t\"}}\n")),
                new PrintStream(output, true, StandardCharsets.UTF_8));

        JsonNode answered = JSON.readTree(output.toString(StandardCharsets.UTF_8).strip());
        assertEquals(8, answered.path("id").asInt());
        assertEquals("AssertionError: broken", answered.at("/error/message").asText());
    }

    @Test
    @DisplayName("tool calls keep the order they arrived in, one at a time")
    void callsAreAnsweredInTheirOrder() throws Exception {
        List<String> running = new CopyOnWriteArrayList<>();
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            running.add(name);
            return new ToolOutcome(name, false);
        }, "sheriff");
        StringBuilder input = new StringBuilder();
        for (int call = 1; call <= 5; call++) {
            input.append("{\"jsonrpc\":\"2.0\",\"id\":").append(call)
                    .append(",\"method\":\"tools/call\",\"params\":{\"name\":\"t").append(call).append("\"}}\n");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        server.serve(new BufferedReader(new StringReader(input.toString())),
                new PrintStream(output, true, StandardCharsets.UTF_8));

        assertEquals(List.of("t1", "t2", "t3", "t4", "t5"), running);
        List<String> answered = output.toString(StandardCharsets.UTF_8).lines().toList();
        for (int call = 1; call <= 5; call++) {
            assertTrue(answered.get(call - 1).contains("\"id\":" + call), answered.toString());
        }
    }

    @Test
    @DisplayName("the client's name and version, from initialize, are told to whoever asked")
    void theClientIsToldOnInitialize() throws Exception {
        List<String> told = new CopyOnWriteArrayList<>();
        JsonRpcServer named = server("", false).onClient((name, version) -> told.add(name + " " + version));
        named.handle(request("initialize", Map.of("clientInfo", Map.of("name", "codex-mcp-client", "version",
                "0.160.0"))));
        named.handle(request("initialize", Map.of()));
        assertEquals(List.of("codex-mcp-client 0.160.0", " "), told);
    }

    /**
     * A stream that keeps every line written to it.
     */
    private static PrintStream recording(List<String> lines) {
        return new PrintStream(new OutputStream() {
            private final StringBuilder line = new StringBuilder();

            @Override
            public void write(int character) {
                if (character != '\n') {
                    line.append((char) character);
                    return;
                }
                lines.add(line.toString());
                line.setLength(0);
            }
        }, true, StandardCharsets.UTF_8);
    }

    private static String call(int id, String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\"}}\n";
    }

    private static String cancel(int id) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":" + id
                + ",\"reason\":\"AbortError: user-cancel\"}}\n";
    }

    @Test
    @DisplayName("a call the client cancels while it runs is interrupted and not answered, and the next one runs "
            + "with no interrupt left over")
    void aRunningCallThatIsCancelledIsInterruptedAndNotAnswered() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        List<String> seen = new CopyOnWriteArrayList<>();
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            if (name.equals("next")) {
                seen.add("next, interrupted: " + Thread.currentThread().isInterrupted());
                return new ToolOutcome("next", false);
            }
            started.countDown();
            try {
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                seen.add("slow ran to its end");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                seen.add("slow interrupted");
            }
            return new ToolOutcome("slow", false);
        }, "sheriff");
        List<String> lines = new CopyOnWriteArrayList<>();
        PipedWriter client = new PipedWriter();
        PipedReader input = new PipedReader(client);
        Thread serving = new Thread(() -> {
            try {
                server.serve(new BufferedReader(input), recording(lines));
            } catch (java.io.IOException exception) {
                lines.add("serve failed: " + exception.getMessage());
            }
        });
        serving.start();

        client.write(call(1, "slow"));
        client.flush();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        client.write(cancel(1) + call(2, "next"));
        client.close();
        serving.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(List.of("slow interrupted", "next, interrupted: false"), seen);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"id\":2"), lines.toString());
    }

    @Test
    @DisplayName("a call the client cancels while it waits behind another never runs, and is not answered")
    void aQueuedCallThatIsCancelledNeverRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> ran = new CopyOnWriteArrayList<>();
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> {
            ran.add(name);
            started.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ToolOutcome(name, false);
        }, "sheriff");
        List<String> lines = new CopyOnWriteArrayList<>();
        PipedWriter client = new PipedWriter();
        PipedReader input = new PipedReader(client);
        Thread serving = new Thread(() -> {
            try {
                server.serve(new BufferedReader(input), recording(lines));
            } catch (java.io.IOException exception) {
                lines.add("serve failed: " + exception.getMessage());
            }
        });
        serving.start();

        client.write(call(1, "first") + call(2, "queued"));
        client.flush();
        assertTrue(started.await(10, TimeUnit.SECONDS));
        client.write(cancel(2));
        client.flush();
        Thread.sleep(200);
        release.countDown();
        client.close();
        serving.join(TimeUnit.SECONDS.toMillis(10));

        assertEquals(List.of("first"), ran);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"id\":1"), lines.toString());
    }

    @Test
    @DisplayName("a cancellation of a call that is neither queued nor running is passed over, and never answered")
    void aCancellationOfNoCallIsPassedOver() throws Exception {
        List<String> lines = new CopyOnWriteArrayList<>();
        JsonRpcServer server = new JsonRpcServer(List::of, (name, arguments) -> new ToolOutcome(name, false), "sheriff");

        server.serve(new BufferedReader(new StringReader(cancel(9) + call(1, "only"))), recording(lines));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("\"id\":1") && lines.get(0).contains("only"), lines.toString());
    }
}
