package com.kaizten.sheriff.infrastructure.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * How a step names the next call.
 */
class ToolCallTest {

    @Test
    @DisplayName("through the MCP, a tool call with the component named, and the profile only when it was asked for")
    void namesAToolCall() {
        ToolCall mcp = ToolCall.throughMcp();
        assertEquals("call the sheriff_fix tool with component 'api'", mcp.phrase("sheriff_fix", "api", ""));
        assertEquals("call the sheriff_fix tool with component 'api' and profile 'JAVA,JAVA_DDD'",
                mcp.phrase("sheriff_fix", "api", "JAVA,JAVA_DDD"));
        assertEquals("call the sheriff_test tool", mcp.phrase("sheriff_test", "", ""));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "cmd.exe takes double quotes")
    @DisplayName("through the command line, the command that makes the same call, runnable as it stands")
    void namesACommand() {
        ToolCall command = ToolCall.throughCommandLine(Path.of("/work/my app"), Path.of("/opt/sheriff-mcp.jar"));
        String phrase = command.phrase("sheriff_fix", "api", "");
        assertEquals("run `cd '/work/my app' && java -jar '/opt/sheriff-mcp.jar' --call sheriff_fix component='api'`",
                phrase);
        assertTrue(command.phrase("sheriff_test", "it's", "").contains("component='it'\\''s'"), "a quote is escaped");
    }
}
