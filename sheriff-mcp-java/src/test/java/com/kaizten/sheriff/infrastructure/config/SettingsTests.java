package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings a user can change: where each is read from, and how one is
 * changed.
 */
class SettingsTests {

    @TempDir
    Path folder;

    private Path project;
    private Path config;
    private Map<String, String> environment;

    @BeforeEach
    void folders() throws IOException {
        project = Files.createDirectories(folder.resolve("project"));
        config = Files.createDirectories(folder.resolve("config"));
        environment = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString());
    }

    private Path userFile() {
        return config.resolve("sheriff-mcp/sheriff.properties");
    }

    private void user(String text) throws IOException {
        Files.createDirectories(userFile().getParent());
        Files.writeString(userFile(), text);
    }

    @Test
    @DisplayName("an environment variable wins, then the project's file, then the user's; the rest stays as it is")
    void settingsAreLayered() throws IOException {
        user("SHERIFF_STOP_MAX_BLOCKS=7\nSHERIFF_GATE_TTL=60\nSHERIFF_IMAGE=user/image\n");
        Files.writeString(project.resolve(".sheriff.properties"), "profile=JAVA_DDD\nSHERIFF_GATE_TTL=30\n");
        Map<String, String> given = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_IMAGE", "env/image", "OTHER", "kept");

        Map<String, String> layered = Settings.layered(given, project);

        assertEquals("env/image", layered.get("SHERIFF_IMAGE"));
        assertEquals("30", layered.get("SHERIFF_GATE_TTL"));
        assertEquals("7", layered.get("SHERIFF_STOP_MAX_BLOCKS"));
        assertEquals("kept", layered.get("OTHER"));
        assertFalse(layered.containsKey("profile"), "a file's other keys are not settings");
        assertFalse(layered.containsKey("SHERIFF_PULL"), "a setting nothing sets keeps its default in the code");
    }

    @Test
    @DisplayName("a cloned project does not choose who answers in the repair loop for whoever opens it")
    void aProjectCannotSetAUserOnlySetting() throws IOException {
        Files.writeString(project.resolve(".sheriff.properties"), "AI_BACKEND=openai_api\n");
        assertFalse(Settings.layered(environment, project).containsKey("AI_BACKEND"));
        assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "AI_BACKEND", "openai_api", true));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows looks under APPDATA first")
    @DisplayName("the user's file is found through the environment given, never the process's own home")
    void theUserFileIsFoundThroughTheEnvironment() {
        assertEquals(Optional.of(Path.of("/x/sheriff-mcp/sheriff.properties")),
                Settings.userFile(Map.of("XDG_CONFIG_HOME", "/x", "HOME", "/h")));
        assertEquals(Optional.of(Path.of("/h/.config/sheriff-mcp/sheriff.properties")),
                Settings.userFile(Map.of("HOME", "/h")));
        assertEquals(Optional.empty(), Settings.userFile(Map.of()));
    }

    @Test
    @DisplayName("the user's file is written at its defaults, every setting commented out, and never over one")
    void theUserFileStartsAtItsDefaults() throws IOException {
        Settings.ensureUserFile(environment);
        String written = Files.readString(userFile());
        for (Setting setting : Settings.ALL) {
            assertTrue(written.contains("\n# " + setting.name() + "=" + setting.defaultValue() + "\n"), setting.name());
        }
        assertTrue(Settings.layered(environment, project).keySet().stream().noneMatch(name -> name.startsWith(
                "SHERIFF_STOP")), "a file at its defaults sets nothing");
        user("SHERIFF_STOP_MAX_BLOCKS=2\n");
        Settings.ensureUserFile(environment);
        assertEquals("SHERIFF_STOP_MAX_BLOCKS=2\n", Files.readString(userFile()));
    }

    @Test
    @DisplayName("a change takes the setting's line out of its comment, and an empty value comments it out again")
    void aChangeIsWrittenOverItsLine() throws IOException {
        String done = Settings.change(environment, project, "SHERIFF_STOP_MAX_STALLS", "0", false);

        assertTrue(done.startsWith("SHERIFF_STOP_MAX_STALLS is 0 in " + userFile()), done);
        assertTrue(Files.readString(userFile()).contains("\nSHERIFF_STOP_MAX_STALLS=0\n"));
        assertEquals("0", Settings.layered(environment, project).get("SHERIFF_STOP_MAX_STALLS"));
        Settings.change(environment, project, "SHERIFF_STOP_MAX_STALLS", "", false);
        assertTrue(Files.readString(userFile()).contains("\n# SHERIFF_STOP_MAX_STALLS=1\n"));
        assertFalse(Settings.layered(environment, project).containsKey("SHERIFF_STOP_MAX_STALLS"));
    }

    @Test
    @DisplayName("a project's setting goes into its own .sheriff.properties, beside what it declares already")
    void aProjectSettingGoesIntoItsFile() throws IOException {
        Files.writeString(project.resolve(".sheriff.properties"), "profile=JAVA_HEXAGONAL");
        Settings.change(environment, project, "SHERIFF_IMAGE", "kaizten/sheriff@sha256:abc", true);
        assertEquals("profile=JAVA_HEXAGONAL\nSHERIFF_IMAGE=kaizten/sheriff@sha256:abc\n",
                Files.readString(project.resolve(".sheriff.properties")));
    }

    @Test
    @DisplayName("a value the tools could not use is refused before it is written, naming what the setting takes")
    void aBadValueIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "SHERIFF_STOP_MAX_BLOCKS", "0", false));
        assertEquals("SHERIFF_STOP_MAX_BLOCKS takes 1 or more, not '0'.", refused.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "SHERIFF_PULL", "sometimes", false));
        assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "SHERIFF_COVERAGE", "120", false));
        assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "SHERIFF_IMAGE", "a b", false));
        assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "SHERIFF_MUTATORS", "all; rm", false));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, project, "SHERIFF_NOPE", "1", false)).getMessage()
                .contains("SHERIFF_IMAGE, SHERIFF_PULL"));
        assertFalse(Files.exists(userFile()), "nothing refused is written");
    }

    @Test
    @DisplayName("a setting the environment also sets says the variable wins while it is set")
    void anEnvironmentVariableIsNamed() {
        Map<String, String> given = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_GATE_TTL", "5");
        assertTrue(Settings.change(given, project, "SHERIFF_GATE_TTL", "60", false)
                .endsWith("While the environment variable SHERIFF_GATE_TTL is set (to 5), it wins over the file."));
    }

    @Test
    @DisplayName("the report gives each setting's value and where it comes from")
    void theReportNamesTheSource() throws IOException {
        user("SHERIFF_STOP_MAX_BLOCKS=7\n");
        Map<String, String> given = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_PULL", "never");

        String report = Settings.report(given, project);

        assertTrue(report.contains("SHERIFF_PULL = never (the environment)"), report);
        assertTrue(report.contains("SHERIFF_STOP_MAX_BLOCKS = 7 (" + userFile() + ")"), report);
        assertTrue(report.contains("SHERIFF_IMAGE = kaizten/sheriff:latest (default)"), report);
        assertTrue(report.contains("SHERIFF_MAX_ITERATIONS = worked out (default)"), report);
    }

    @Test
    @DisplayName("each kind of setting takes its own values, and every one takes the empty value")
    void eachKindTakesItsValues() {
        Setting flag = Settings.named("SHERIFF_FAIL_FAST").orElseThrow();
        assertEquals(Optional.empty(), flag.problemWith("1"));
        assertTrue(flag.problemWith("yes").isPresent());
        assertEquals(Optional.empty(), Settings.named("SHERIFF_STOP_MAX_STALLS").orElseThrow().problemWith("0"));
        assertTrue(Settings.named("SHERIFF_TIMEOUT").orElseThrow().problemWith("0").isPresent());
        assertTrue(Settings.named("SHERIFF_TIMEOUT").orElseThrow().problemWith("ten").isPresent());
        assertEquals(Optional.empty(), Settings.named("SHERIFF_MUTATORS").orElseThrow().problemWith("ALL"));
        assertEquals(Optional.empty(), Settings.named("SHERIFF_COVERAGE").orElseThrow().problemWith(""));
        assertTrue(Settings.named("SHERIFF_COVERAGE").orElseThrow().problemWith("x").isPresent());
        assertEquals(Optional.empty(), Settings.named("SHERIFF_NOPE"));
    }

    @Test
    @DisplayName("a catalog skips its blank lines and comments, and a missing one says which")
    void aCatalogIsReadLineByLine() {
        assertEquals(List.of("A_FLAG", "B_SECONDS"),
                Settings.catalog("/settings-sample.tsv").stream().map(Setting::name).toList());
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> Settings.catalog("/missing.tsv"));
        assertEquals("The settings catalog /missing.tsv is not in this jar.", missing.getMessage());
    }

    @Test
    @DisplayName("Windows looks under APPDATA first; elsewhere it is the last but one place, then USERPROFILE")
    void theUserFileOnEachSystem() {
        Map<String, String> both = Map.of("APPDATA", "/appdata", "HOME", "/h", "XDG_CONFIG_HOME", " ");
        assertEquals(Optional.of(Path.of("/appdata/sheriff-mcp/sheriff.properties")), Settings.userFile(both, true));
        assertEquals(Optional.of(Path.of("/h/.config/sheriff-mcp/sheriff.properties")), Settings.userFile(both, false),
                "a blank XDG_CONFIG_HOME is none");
        assertEquals(Optional.of(Path.of("/appdata/sheriff-mcp/sheriff.properties")),
                Settings.userFile(Map.of("APPDATA", "/appdata", "USERPROFILE", "/u"), false));
        assertEquals(Optional.of(Path.of("/u/.config/sheriff-mcp/sheriff.properties")),
                Settings.userFile(Map.of("USERPROFILE", "/u"), false));
    }

    @Test
    @DisplayName("with no home there is no file to write, and one that cannot be written leaves the defaults")
    void theUserFileIsBestEffort() throws IOException {
        Settings.ensureUserFile(Map.of());
        Path blocking = Files.writeString(folder.resolve("not-a-folder"), "x");
        Settings.ensureUserFile(Map.of("XDG_CONFIG_HOME", blocking.toString(), "APPDATA", blocking.toString()));
        assertEquals("x", Files.readString(blocking));
        assertEquals("There is no file of yours to write to: none of HOME, XDG_CONFIG_HOME or APPDATA is set.",
                assertThrows(IllegalArgumentException.class, () -> Settings.change(Map.of(), project,
                        "SHERIFF_GATE_TTL", "60", false)).getMessage());
    }

    @Test
    @DisplayName("a project's change writes only the project's file, at its end, on a line of its own")
    void aProjectChangeLeavesTheUserFileAlone() throws IOException {
        Settings.change(environment, project, "SHERIFF_GATE_TTL", "60", true);
        assertEquals("SHERIFF_GATE_TTL=60\n", Files.readString(project.resolve(".sheriff.properties")));
        assertFalse(Files.exists(userFile()), "the user's file is written only for a change of the user's");
        Settings.change(environment, project, "SHERIFF_PULL", "check", true);
        assertEquals("SHERIFF_GATE_TTL=60\nSHERIFF_PULL=check\n",
                Files.readString(project.resolve(".sheriff.properties")));
    }

    @Test
    @DisplayName("a value with a $ is written as it is, over the line it replaces")
    void aDollarIsNoGroupReference() throws IOException {
        Settings.change(environment, project, "SHERIFF_IMAGE", "registry/$1:latest", false);
        assertTrue(Files.readString(userFile()).contains("\nSHERIFF_IMAGE=registry/$1:latest\n"));
    }

    @Test
    @DisplayName("each refusal and each change says exactly what happened")
    void theMessagesSayWhatHappened() {
        assertEquals("AI_BACKEND is only for your own file: a project that is cloned must not choose it for whoever "
                + "opens it.", assertThrows(IllegalArgumentException.class, () -> Settings.change(environment,
                        project, "AI_BACKEND", "local", true)).getMessage());
        assertEquals("SHERIFF_MAX_ITERATIONS is back to its default (worked out) in " + userFile() + ". The tools take "
                + "it when the next session starts; the hooks, from their next run.",
                Settings.change(environment, project, "SHERIFF_MAX_ITERATIONS", " ", false));
        assertEquals("SHERIFF_GATE_TTL is 60 in " + userFile() + ". The tools take it when the next session starts; "
                + "the hooks, from their next run.", Settings.change(environment, project, "SHERIFF_GATE_TTL", "60",
                        false));
    }

    @Test
    @DisplayName("a project's file that cannot be written refuses the change, in the file system's words")
    void anUnwritableFileRefusesTheChange() throws IOException {
        Path notAFolder = Files.writeString(folder.resolve("file-project"), "x");
        assertThrows(IllegalArgumentException.class,
                () -> Settings.change(environment, notAFolder, "SHERIFF_GATE_TTL", "60", true));
    }

    @Test
    @DisplayName("a blank value sets nothing: in the environment the files are read, in a file the default stays")
    void blankValuesSetNothing() throws IOException {
        user("SHERIFF_GATE_TTL=60\nSHERIFF_TIMEOUT=   \n");
        Map<String, String> given = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_GATE_TTL", " ");
        Map<String, String> layered = Settings.layered(given, project);
        assertEquals("60", layered.get("SHERIFF_GATE_TTL"));
        assertFalse(layered.containsKey("SHERIFF_TIMEOUT"));
    }

    @Test
    @DisplayName("a file Java cannot read as properties sets nothing")
    void anUnreadableFileSetsNothing() throws IOException {
        user("SHERIFF_GATE_TTL=\\uZZZZ\n");
        assertFalse(Settings.layered(environment, project).containsKey("SHERIFF_GATE_TTL"));
    }

    @Test
    @DisplayName("the report names both files, and a project's file does not speak for a user-only setting")
    void theReportNamesBothFiles() throws IOException {
        Files.writeString(project.resolve(".sheriff.properties"), "AI_BACKEND=local\n");
        String report = Settings.report(environment, project);
        assertTrue(report.startsWith("Settings, each from the first that sets it: an environment variable, the "
                + "project's " + project.resolve(".sheriff.properties") + ", yours, " + userFile()
                + ", then the default."), report);
        assertTrue(report.contains("AI_BACKEND = claude_cli (default)"), report);
        assertTrue(Settings.report(Map.of(), project).contains("yours, (none: no HOME, XDG_CONFIG_HOME or APPDATA)"));
    }

    @Test
    @DisplayName("it is never instantiated: every member is static")
    void itIsAUtilityClass() throws ReflectiveOperationException {
        Constructor<Settings> constructor = Settings.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException refused = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(refused.getCause() instanceof UnsupportedOperationException);
    }

    @Test
    @DisplayName("a project's setting is reported from its file, a user-only one changes in the user's, and a reset "
            + "names the default it puts back")
    void projectSettingsAndResetsAreNamed() throws IOException {
        Files.writeString(project.resolve(".sheriff.properties"), "SHERIFF_GATE_TTL=30\n");
        assertTrue(Settings.report(environment, project).contains("SHERIFF_GATE_TTL = 30 ("
                + project.resolve(".sheriff.properties") + ")"));
        assertTrue(Settings.change(environment, project, "AI_BACKEND", "local", false).startsWith(
                "AI_BACKEND is local in "));
        assertTrue(Settings.change(environment, project, "SHERIFF_STOP_MAX_STALLS", "", false).startsWith(
                "SHERIFF_STOP_MAX_STALLS is back to its default (1) in "));
    }
}
