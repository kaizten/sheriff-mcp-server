package com.kaizten.sheriff.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the chat-completions fixer, driven through a local endpoint with
 * canned replies: the same refusals the Anthropic fixer makes, in the shape
 * OpenAI, Ollama and the other compatible servers answer with.
 */
class OpenAiApiFixerTests {

    private static final String ORIGINAL = "class A {}";

    @TempDir
    private Path repository;

    @BeforeEach
    void writeAFixture() throws IOException {
        Files.createDirectories(repository.resolve("app"));
        Files.writeString(repository.resolve("app/A.java"), ORIGINAL);
    }

    private static String reply(String finishReason, String toolCalls) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"","tool_calls":[%s]},
                             "finish_reason":"%s"}]}
                """.formatted(toolCalls, finishReason);
    }

    private static String writeCall(String arguments) {
        return "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"write_file\",\"arguments\":"
                + arguments + "}}";
    }

    private FixResult fixThrough(CannedEndpoint endpoint) {
        OpenAiApiFixer fixer = new OpenAiApiFixer("", endpoint.url(), "local-model", 8000L, 5, false,
                repository, new PromptLog(repository.resolve("logs")));
        return fixer.fix(FixRequest.scoped("fix it", "iteration-1", Set.of("app/A.java")));
    }

    @Test
    @DisplayName("a reply that ran out of tokens fails the pass and touches nothing")
    void aTruncatedReplyIsNotActedOn() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("length", writeCall("\"{\\\"path\\\":\\\"app/A.java\\\"}\""))))) {
            FixResult result = fixThrough(endpoint);
            assertFalse(result.ok());
            assertTrue(result.output().contains("cut off"));
        }
        assertEquals(ORIGINAL, Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    void aFilteredReplyFailsThePass() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(reply("content_filter", "")))) {
            assertFalse(fixThrough(endpoint).ok());
        }
    }

    @Test
    @DisplayName("a write without content is refused back to the model, and the file survives")
    void aWriteWithoutContentLeavesTheFileAlone() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(
                reply("tool_calls", writeCall("\"{\\\"path\\\":\\\"app/A.java\\\"}\"")),
                reply("stop", "")))) {
            assertTrue(fixThrough(endpoint).ok());
            assertTrue(endpoint.requests().get(1).contains("Refused"));
        }
        assertEquals(ORIGINAL, Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    @DisplayName("a prompt over the model's context window says how to widen it")
    void aContextOverflowExplainsTheFix() throws IOException {
        String overflow = CannedEndpoint.FAILURE_PREFIX + "{\"error\":{\"message\":\"request (9056 tokens) "
                + "exceeds the available context size (4096 tokens)\",\"type\":\"exceed_context_size_error\"}}";
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(overflow))) {
            FixResult result = fixThrough(endpoint);
            assertFalse(result.ok());
            assertTrue(result.output().contains("num_ctx 16384"), result.output());
        }
    }

    private static String textReply(String content) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + content
                + "},\"finish_reason\":\"stop\"}]}";
    }

    @Test
    @DisplayName("a call written as JSON in the reply, as small local models do, is run like a real one")
    void aCallWrittenAsTextIsRun() throws IOException {
        String written = "\"```json\\n[{\\\"name\\\": \\\"write_file\\\", \\\"arguments\\\": "
                + "{\\\"path\\\": \\\"app/A.java\\\", \\\"content\\\": \\\"class A { }\\\"}}]\\n```\"";
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(textReply(written), textReply("\"done\"")))) {
            assertTrue(fixThrough(endpoint).ok());
            assertTrue(endpoint.requests().get(1).contains("written as text") || endpoint.requests().get(1).contains("as text"));
        }
        assertEquals("class A { }", Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    @DisplayName("prose that merely mentions a tool is not a call")
    void proseIsNotACall() throws IOException {
        try (CannedEndpoint endpoint = new CannedEndpoint(List.of(textReply("\"I would use write_file here.\"")))) {
            assertTrue(fixThrough(endpoint).ok());
            assertEquals(1, endpoint.requests().size());
        }
        assertEquals(ORIGINAL, Files.readString(repository.resolve("app/A.java")));
    }
}
