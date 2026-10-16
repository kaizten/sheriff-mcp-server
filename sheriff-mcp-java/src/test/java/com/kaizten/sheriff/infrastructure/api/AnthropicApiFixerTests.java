package com.kaizten.sheriff.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Anthropic API fixer, driven through a local endpoint with
 * canned replies.
 *
 * <p>What matters here is what the fixer refuses to act on: a reply cut off
 * at the token limit, a refusal, and a write that arrived without its
 * content. Each of those used to reach the disk as an empty file or as a pass
 * reported successful.
 */
class AnthropicApiFixerTests {

    private static final String ORIGINAL = "class A {}";

    @TempDir
    private Path repository;

    @BeforeEach
    void writeAFixture() throws IOException {
        Files.createDirectories(repository.resolve("app"));
        Files.writeString(repository.resolve("app/A.java"), ORIGINAL);
    }

    private static String reply(String stopReason, String content) {
        return """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5",
                 "content":[%s],"stop_reason":"%s","stop_sequence":null,
                 "usage":{"input_tokens":1,"output_tokens":1}}
                """.formatted(content, stopReason);
    }

    private static String writeCall(String input) {
        return "{\"type\":\"tool_use\",\"id\":\"tu_1\",\"name\":\"write_file\",\"input\":" + input + "}";
    }

    private FixResult fixThrough(CannedEndpoint endpoint) {
        AnthropicApiFixer fixer = new AnthropicApiFixer("key", endpoint.url(), "claude-opus-5", 16000L, 5,
                repository, new PromptLog(repository.resolve("logs")));
        return fixer.fix(FixRequest.scoped("fix it", "iteration-1", Set.of("app/A.java")));
    }

    @Test
    @DisplayName("a reply cut off at max_tokens fails the pass and touches nothing")
    void aTruncatedReplyIsNotActedOn() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("max_tokens", writeCall("{\"path\":\"app/A.java\"}"))))) {
            FixResult result = fixThrough(endpoint);
            assertFalse(result.ok());
            assertTrue(result.output().contains("cut off"));
        }
        assertEquals(ORIGINAL, Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    void aRefusalFailsThePass() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("refusal", "{\"type\":\"text\",\"text\":\"no\"}")))) {
            FixResult result = fixThrough(endpoint);
            assertFalse(result.ok());
            assertTrue(result.output().contains("refusal"));
        }
    }

    @Test
    @DisplayName("a write without content is refused back to the model, and the file survives")
    void aWriteWithoutContentLeavesTheFileAlone() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("tool_use", writeCall("{\"path\":\"app/A.java\"}")),
                reply("end_turn", "{\"type\":\"text\",\"text\":\"done\"}")))) {
            FixResult result = fixThrough(endpoint);
            assertTrue(result.ok());
            assertTrue(endpoint.requests().get(1).contains("Refused"));
        }
        assertEquals(ORIGINAL, Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    void aCompleteWriteIsApplied() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("tool_use", writeCall("{\"path\":\"app/A.java\",\"content\":\"class A { }\"}")),
                reply("end_turn", "{\"type\":\"text\",\"text\":\"done\"}")))) {
            assertTrue(fixThrough(endpoint).ok());
        }
        assertEquals("class A { }", Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    @DisplayName("each request authenticates, names the API version, offers both tools and carries the turn before")
    void theRequestIsTheMessagesApis() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("tool_use", writeCall("{\"path\":\"app/A.java\",\"content\":\"class A { }\"}")),
                reply("end_turn", "{\"type\":\"text\",\"text\":\"done\"}")))) {
            assertTrue(fixThrough(endpoint).ok());
            Map<String, List<String>> headers = endpoint.headers().get(0);
            assertEquals(List.of("key"), headers.get("X-api-key"));
            assertEquals(List.of("2023-06-01"), headers.get("Anthropic-version"));
            JsonNode first = new ObjectMapper().readTree(endpoint.requests().get(0));
            assertEquals("claude-opus-5", first.path("model").asText());
            assertEquals(16000, first.path("max_tokens").asInt());
            assertEquals("read_file", first.path("tools").path(0).path("name").asText());
            assertEquals("object", first.path("tools").path(1).path("input_schema").path("type").asText());
            assertEquals("content", first.path("tools").path(1).path("input_schema").path("required").path(1).asText());
            assertEquals("fix it", first.path("messages").path(0).path("content").asText());
            JsonNode second = new ObjectMapper().readTree(endpoint.requests().get(1)).path("messages");
            assertEquals("assistant", second.path(1).path("role").asText());
            assertEquals("tu_1", second.path(1).path("content").path(0).path("id").asText());
            JsonNode result = second.path(2).path("content").path(0);
            assertEquals("tool_result", result.path("type").asText());
            assertEquals("tu_1", result.path("tool_use_id").asText());
        }
    }

    @Test
    @DisplayName("an overloaded or rate-limited endpoint is tried again, as the SDK did")
    void aRetryableFailureIsRetried() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                "!529 {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}",
                "!429 {\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\"}}",
                reply("end_turn", "{\"type\":\"text\",\"text\":\"done\"}")))) {
            assertTrue(fixThrough(endpoint).ok());
            assertEquals(3, endpoint.requests().size());
        }
    }

    @Test
    @DisplayName("a request that keeps failing is given up after two retries, with the endpoint's answer")
    void aPersistentFailureIsReported() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                "!500 {\"error\":\"one\"}", "!500 {\"error\":\"two\"}", "!500 {\"error\":\"three\"}"))) {
            FixResult result = fixThrough(endpoint);
            assertFalse(result.ok());
            assertTrue(result.output().contains("answered 500"));
            assertTrue(result.output().contains("three"));
            assertEquals(3, endpoint.requests().size());
        }
    }

    @Test
    @DisplayName("a request the API rejects is not sent again")
    void aRejectedRequestIsNotRetried() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                CannedEndpoint.FAILURE_PREFIX + "{\"error\":{\"message\":\"bad model\"}}"))) {
            FixResult result = fixThrough(endpoint);
            assertFalse(result.ok());
            assertTrue(result.output().contains("bad model"));
            assertEquals(1, endpoint.requests().size());
        }
    }

    @Test
    @DisplayName("an input holding something other than strings is refused back, not crashed on")
    void aNonTextualInputIsRefused() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("tool_use", writeCall("{\"path\":\"app/A.java\",\"content\":42}")),
                reply("end_turn", "{\"type\":\"text\",\"text\":\"done\"}")))) {
            assertTrue(fixThrough(endpoint).ok());
        }
        assertEquals(ORIGINAL, Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    @DisplayName("the endpoint is the base URL with /v1/messages, Anthropic's own when none is set")
    void theEndpointFollowsTheBaseUrl() {
        PromptLog log = new PromptLog(repository.resolve("logs"));
        assertEquals("https://api.anthropic.com/v1/messages",
                new AnthropicApiFixer("key", "", "m", 1L, 1, repository, log).endpoint());
        assertEquals("https://api.anthropic.com/v1/messages",
                new AnthropicApiFixer("key", "   ", "m", 1L, 1, repository, log).endpoint());
        assertEquals("http://gateway:8080/v1/messages",
                new AnthropicApiFixer("key", "http://gateway:8080//", "m", 1L, 1, repository, log).endpoint());
    }

    @Test
    @DisplayName("without a usable delay asked for, the wait doubles from half a second up to eight")
    void theBackoffDoubles() {
        assertEquals(Duration.ofMillis(500), AnthropicApiFixer.delayBefore(0, Optional.empty()));
        assertEquals(Duration.ofSeconds(1), AnthropicApiFixer.delayBefore(1, Optional.empty()));
        assertEquals(Duration.ofSeconds(8), AnthropicApiFixer.delayBefore(6, Optional.empty()));
        assertEquals(Duration.ofMillis(500),
                AnthropicApiFixer.delayBefore(0, Optional.of(answer(429, Map.of("retry-after", "soon")))));
        assertEquals(Duration.ofMillis(500),
                AnthropicApiFixer.delayBefore(0, Optional.of(answer(429, Map.of("retry-after", "3600")))));
    }

    @Test
    @DisplayName("a delay the server asks for is the one waited, in milliseconds before seconds")
    void theAskedDelayIsWaited() {
        assertEquals(Duration.ofMillis(1500),
                AnthropicApiFixer.delayBefore(0, Optional.of(answer(429, Map.of("retry-after", "1.5")))));
        assertEquals(Duration.ofMillis(20), AnthropicApiFixer.delayBefore(2,
                Optional.of(answer(429, Map.of("retry-after", "9", "retry-after-ms", "20")))));
    }

    @Test
    @DisplayName("timeouts, conflicts, rate limits and server errors are retried, other answers are not")
    void whatIsRetried() {
        assertTrue(AnthropicApiFixer.retried(answer(408, Map.of())));
        assertTrue(AnthropicApiFixer.retried(answer(409, Map.of())));
        assertTrue(AnthropicApiFixer.retried(answer(429, Map.of())));
        assertTrue(AnthropicApiFixer.retried(answer(500, Map.of())));
        assertTrue(AnthropicApiFixer.retried(answer(529, Map.of())));
        assertFalse(AnthropicApiFixer.retried(answer(400, Map.of())));
        assertFalse(AnthropicApiFixer.retried(answer(401, Map.of())));
        assertFalse(AnthropicApiFixer.retried(answer(200, Map.of())));
    }

    @Test
    @DisplayName("the server's own x-should-retry wins over the status")
    void theServerDecidesWhenItSays() {
        assertTrue(AnthropicApiFixer.retried(answer(400, Map.of("x-should-retry", "true"))));
        assertFalse(AnthropicApiFixer.retried(answer(503, Map.of("x-should-retry", "false"))));
        assertTrue(AnthropicApiFixer.retried(answer(503, Map.of("x-should-retry", "maybe"))));
    }

    private static HttpResponse<String> answer(int status, Map<String, String> headers) {
        Map<String, List<String>> lists = new HashMap<>();
        headers.forEach((name, value) -> lists.put(name, List.of(value)));
        HttpHeaders carried = HttpHeaders.of(lists, (name, value) -> true);
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return null;
            }

            @Override
            public Optional<HttpResponse<String>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return carried;
            }

            @Override
            public String body() {
                return "";
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return URI.create("http://127.0.0.1/");
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }
}
