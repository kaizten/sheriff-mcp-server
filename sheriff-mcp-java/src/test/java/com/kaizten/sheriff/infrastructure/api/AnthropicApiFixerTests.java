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
}
