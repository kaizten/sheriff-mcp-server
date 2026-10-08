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

        assertEquals("2025-06-18", response.at("/result/protocolVersion").asText());
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
    void initializeEchoesBackAVersionItSupports() throws Exception {
        ObjectNode response = server("", false).handle(request("initialize", Map.of("protocolVersion", "2025-03-26")));

        assertEquals("2025-03-26", response.at("/result/protocolVersion").asText());
    }

    @Test
    @DisplayName("a version this server does not know is answered with its own, never echoed")
    void initializeAnswersAnUnknownVersionWithItsOwn() throws Exception {
        ObjectNode response = server("", false).handle(request("initialize", Map.of("protocolVersion", "1999-01-01")));

        assertEquals("2025-06-18", response.at("/result/protocolVersion").asText());
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
}
