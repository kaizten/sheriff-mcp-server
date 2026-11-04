package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.mcp.tool.Tools;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The instructions the server hands a client when it connects, which replaced
 * the skill: they must name the tools as the server actually registers them,
 * the drift that left the skill talking about tools no server offered.
 */
final class ServerInstructionsTest {

    @Test
    void nameEveryToolTheServerRegisters() throws Exception {
        String instructions = McpServer.instructions();
        Tools tools = new Tools(new McpConfig(Map.of(), Path.of(".")), null, List::of);

        for (Map<String, Object> definition : tools.definitions()) {
            String name = (String) definition.get("name");
            assertTrue(instructions.contains(name), name + " is missing from the instructions");
        }
    }

    @Test
    void stayShortEnoughForAClientNotToCutThem() throws Exception {
        assertTrue(McpServer.instructions().length() < 2000);
    }

    @Test
    @DisplayName("a demo asked twice whether to clear the errors it found, and recommended leaving them")
    void clearTheErrorsAlreadyThereWithoutAsking() throws Exception {
        String instructions = McpServer.instructions();

        assertTrue(instructions.contains("Never ask whether to"));
        assertTrue(instructions.contains("never end the turn with such a question"));
        assertTrue(instructions.contains("nor offer to leave them"));
    }

    @Test
    @DisplayName("Haiku undid with git what sheriff_fix had repaired, and then offered to, as not what the request named")
    void keepWhatTheRepairChanged() throws Exception {
        String instructions = McpServer.instructions();

        assertTrue(instructions.contains("What sheriff_fix changes is part of that work"));
        assertTrue(instructions.contains("never undo it, with git, a formatter or by hand, nor offer to"));
    }

    @Test
    @DisplayName("Codex asks for the first 512 characters to stand on their own")
    void openWithTheRuleAboutTheErrorsAlreadyThere() throws Exception {
        String opening = McpServer.instructions().substring(0, 512);

        assertTrue(opening.contains("clear them all first"));
        assertTrue(opening.contains("Never ask whether to"));
    }

    @Test
    void endWithOneMoreCheck() throws Exception {
        assertTrue(McpServer.instructions().contains("Last, call sheriff_test once more"));
    }

    @Test
    @DisplayName("asked which Sheriff it used, Claude Code said no tool told: it sees an MCP tool's name, not its description")
    void sayWhereTheVersionIs() throws Exception {
        assertTrue(McpServer.instructions().contains("version, Sheriff's image or this session's report, call "
                + "sheriff_task with no id"));
    }

    @Test
    @DisplayName("the same state must lead every model to the same step, so the order of work is the answers'")
    void sendTheModelToTheNextStepOfEachAnswer() throws Exception {
        assertTrue(McpServer.instructions().contains("Do exactly that step"));
    }

    @Test
    @DisplayName("Codex ran spring-javaformat:apply after sheriff_fix, which put back 886 errors, and changed its settings")
    void runNoFormatterOfTheProjectsOver() throws Exception {
        String instructions = McpServer.instructions();

        assertTrue(instructions.contains("Run no formatter of the project's"));
        assertTrue(instructions.contains("change no tool's settings"));
        assertTrue(instructions.contains("keep Sheriff's and tell the user which check and rule"));
    }

    @Test
    @DisplayName("Codex ended the turn on a step to fix 92 errors by hand, calling them false positives of compatibility")
    void endTheTurnOnlyWhereAStepDoes() throws Exception {
        String instructions = McpServer.instructions();

        assertTrue(instructions.contains("until one says the component is done or to tell the user"));
        assertTrue(instructions.contains("Never end the turn before, nor leave an error as a false positive yourself"));
    }

    @Test
    @DisplayName("the instructions said to leave the profile out while the step for a component with none named it")
    void nameAProfileOnlyWhereTheUserOrAStepDoes() throws Exception {
        assertTrue(McpServer.instructions().contains("Name a profile only where the user or a step does"));
    }
}
