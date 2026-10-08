package com.kaizten.sheriff.infrastructure.antigravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The home an Antigravity pass runs with: the user's settings, the two rules
 * added, nothing of the user's changed, and nothing left behind.
 */
class AntigravityHomeTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USER_SETTINGS = """
            {"model": "Gemini 3.7 Flash (High)", "trustedWorkspaces": ["/home/me"],
             "permissions": {"allow": ["command(gh pr list)"], "deny": ["command(rm)"]}}
            """;

    @TempDir
    Path userHome;

    private Path userSettingsFile() {
        return userHome.resolve(".gemini/antigravity-cli/settings.json");
    }

    private void userSettings(String content) throws Exception {
        Files.createDirectories(userSettingsFile().getParent());
        Files.writeString(userSettingsFile(), content);
    }

    private AntigravityHome home(String testCommand) {
        return new AntigravityHome(userHome, Map.of("MAVEN_USER_HOME", "/home/me/.m2"), testCommand);
    }

    @Test
    @DisplayName("the user's own settings are kept, model included, and the two rules added to theirs")
    void keepsTheUsersSettingsAndAddsTheRules() throws Exception {
        userSettings(USER_SETTINGS);
        JsonNode settings = home("./mvnw test").settings();
        assertEquals("Gemini 3.7 Flash (High)", settings.path("model").asText());
        assertEquals("/home/me", settings.path("trustedWorkspaces").get(0).asText());
        List<String> allow = JSON.convertValue(settings.at("/permissions/allow"), List.class);
        assertEquals(List.of("command(gh pr list)", "command(./mvnw test)", "command(git mv)"), allow);
        assertEquals("command(rm)", settings.at("/permissions/deny/0").asText(), "a deny rule still wins");
    }

    @Test
    void addsNoRuleTwice() throws Exception {
        userSettings("{\"permissions\": {\"allow\": [\"command(git mv)\"]}}");
        List<String> allow = JSON.convertValue(home("").settings().at("/permissions/allow"), List.class);
        assertEquals(List.of("command(git mv)"), allow);
    }

    @Test
    @DisplayName("with no settings of the user's, or none that can be read, the rules alone")
    void startsFromNothingWhenTheUsersSettingsCannotBeRead() throws Exception {
        assertEquals(2, home("npm test").settings().at("/permissions/allow").size());
        userSettings("not json");
        assertEquals(2, home("npm test").settings().at("/permissions/allow").size());
        userSettings("[1, 2]");
        assertEquals(2, home("npm test").settings().at("/permissions/allow").size());
    }

    @Test
    @DisplayName("with no test command, only git mv")
    void allowsOnlyRenamesWithNoTestCommand() {
        assertEquals(List.of("command(git mv)"), home("").rules());
        assertEquals(List.of("command(./gradlew test)", "command(git mv)"), home("./gradlew test").rules());
    }

    @Test
    void grantsCommandsWithAHomeAndNoneWithout() {
        assertTrue(home("./mvnw test").grantsCommands());
        assertEquals("./mvnw test", home("./mvnw test").testCommand());
        assertFalse(AntigravityHome.none().grantsCommands());
        assertEquals("", AntigravityHome.none().testCommand());
        assertEquals(List.of("command(git mv)"), AntigravityHome.none().rules());
    }

    @Test
    @DisplayName("a pass's home holds the settings, sets HOME and the build tools' homes, and is deleted on close")
    void opensAHomeOfItsOwnAndDeletesIt() throws Exception {
        userSettings(USER_SETTINGS);
        String before = Files.readString(userSettingsFile());
        Path home;
        try (AntigravityPass pass = home("./mvnw test").open()) {
            home = Path.of(pass.environment().get("HOME"));
            assertNotEquals(userHome, home);
            assertEquals("/home/me/.m2", pass.environment().get("MAVEN_USER_HOME"));
            String written = Files.readString(home.resolve(".gemini/antigravity-cli/settings.json"));
            assertTrue(written.contains("command(./mvnw test)"), written);
            Files.writeString(home.resolve(".gemini/antigravity-cli/conversation.db"), "what agy writes");
        }
        assertFalse(Files.exists(home), "the pass's home must not outlive it");
        assertEquals(before, Files.readString(userSettingsFile()), "the user's settings are never written");
    }

    @Test
    @DisplayName("with no home of its own, a pass adds no variable and has nothing to delete")
    void aPassWithoutAHomeIsEmpty() throws Exception {
        try (AntigravityPass pass = AntigravityHome.none().open()) {
            assertEquals(Map.of(), pass.environment());
            pass.close();
        }
    }
}
