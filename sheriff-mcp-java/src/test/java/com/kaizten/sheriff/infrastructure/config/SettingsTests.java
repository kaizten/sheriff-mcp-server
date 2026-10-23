package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    }

    @Test
    @DisplayName("the user's file says, above each setting's line, the values it takes")
    void theUserFileListsTheValuesEachSettingTakes() throws IOException {
        Settings.ensureUserFile(environment);
        String written = Files.readString(userFile());
        for (Setting setting : Settings.ALL) {
            assertTrue(written.contains("\n# " + setting.accepted() + "\n# " + setting.name() + "="), setting.name());
        }
        assertTrue(written.contains("\n# Takes one of always, check, never. Empty puts the default back.\n"
                + "# SHERIFF_PULL=always\n"), written);
        assertTrue(written.contains("\n# Takes 0 or 1. Empty puts the default back.\n# SHERIFF_FAIL_FAST=0\n"),
                written);
        assertTrue(written.contains("\n# Takes a whole number, 0 or more. Empty puts the default back.\n"
                + "# SHERIFF_STOP_MAX_STALLS=1\n"), written);
    }

    @Test
    @DisplayName("a file of the user's that lacks settings gets them at its end, at their defaults, and what it had "
            + "stays as it was; a second start adds nothing")
    void aUsersFileGetsTheSettingsItLacks() throws IOException {
        user("# mine\nSHERIFF_STOP_MAX_BLOCKS=2\n# SHERIFF_IMAGE=kaizten/sheriff:latest");
        Settings.ensureUserFile(environment);
        String written = Files.readString(userFile());
        assertTrue(written.startsWith("# mine\nSHERIFF_STOP_MAX_BLOCKS=2\n# SHERIFF_IMAGE=kaizten/sheriff:latest\n\n# "),
                written);
        assertTrue(written.contains("\n# SHERIFF_REPAIR_ROUNDS=\n"), written);
        assertEquals(1, written.split("SHERIFF_STOP_MAX_BLOCKS=", -1).length - 1, "a line it has is not added again");
        assertEquals(1, written.split("SHERIFF_IMAGE=", -1).length - 1, "nor one it has commented out");
        assertEquals("2", Settings.layered(environment, project).get("SHERIFF_STOP_MAX_BLOCKS"));
        Settings.ensureUserFile(environment);
        assertEquals(written, Files.readString(userFile()));
    }

    @Test
    @DisplayName("a file that ends its last line gets the settings it lacks after it, with no line left empty twice; "
            + "one that cannot be read is left alone")
    void theSettingsALineEndedFileLacksFollowIt() throws IOException {
        user("SHERIFF_STOP_MAX_BLOCKS=2\n");
        Settings.ensureUserFile(environment);
        assertTrue(Files.readString(userFile()).startsWith("SHERIFF_STOP_MAX_BLOCKS=2\n\n# "));
        Files.delete(userFile());
        Files.createDirectories(userFile());
        Settings.ensureUserFile(environment);
        assertTrue(Files.isDirectory(userFile()));
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
    @DisplayName("a value in a file that a setting does not take is ignored, so the next file, or the default, "
            + "decides, and the report says what is wrong")
    void aBadValueInAFileIsIgnoredAndNamed() throws IOException {
        user("SHERIFF_GATE_TTL=60\nSHERIFF_PULL=siempre\n");
        Files.writeString(project.resolve(".sheriff.properties"), "SHERIFF_GATE_TTL=soon\n");

        Map<String, String> layered = Settings.layered(environment, project);
        String report = Settings.report(environment, project);

        assertEquals("60", layered.get("SHERIFF_GATE_TTL"), "the project's value is wrong, so the user's decides");
        assertFalse(layered.containsKey("SHERIFF_PULL"), "a wrong value with nothing under it leaves the default");
        assertTrue(report.contains("SHERIFF_GATE_TTL = 60 (" + userFile() + ")"), report);
        assertTrue(report.contains("SHERIFF_PULL = always (default)"), report);
        assertTrue(report.contains("What is wrong in them, and so not used:"), report);
        assertEquals(List.of(
                "In " + project.resolve(".sheriff.properties") + ", SHERIFF_GATE_TTL takes a whole number, not "
                        + "'soon'. It is ignored.",
                "In " + userFile() + ", SHERIFF_PULL takes one of always, check, never, not 'siempre'. It is "
                        + "ignored."),
                Settings.problems(environment, project));
    }

    @Test
    @DisplayName("a name in a file that is no setting is named, with the closest one; a project's own keys and "
            + "a setting only the user's file may set are told apart")
    void aNameThatIsNoSettingIsNamed() throws IOException {
        user("SHERIFF_TIMEOT=600\nprofile=JAVA\nCOMPLETELY_ELSE=1\n");
        Files.writeString(project.resolve(".sheriff.properties"),
                "profile=JAVA_DDD\ncoverage=90\nbranches=80\nmutation=70\nmutators=ALL\nAI_BACKEND=local\n");

        List<String> problems = Settings.problems(environment, project);

        assertEquals(List.of(
                "In " + project.resolve(".sheriff.properties") + ", AI_BACKEND is ignored: it is read only from your "
                        + "own file, as a cloned project must not choose it for whoever opens it.",
                "In " + userFile() + ", COMPLETELY_ELSE is not a setting, so it does nothing.",
                "In " + userFile() + ", SHERIFF_TIMEOT is not a setting, so it does nothing. Did you mean "
                        + "SHERIFF_TIMEOUT?",
                "In " + userFile() + ", profile is not a setting, so it does nothing."), problems);
    }

    @Test
    @DisplayName("a value in the environment that a setting does not take is named and left to what reads it; "
            + "a file that cannot be read is named; settings that are all well say nothing")
    void theEnvironmentAndUnreadableFilesAreNamed() throws IOException {
        Map<String, String> given = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_TIMEOUT", "soon");
        assertEquals(List.of(), Settings.problems(environment, project));
        Files.writeString(project.resolve(".sheriff.properties"), "SHERIFF_IMAGE=\\u12\n");

        List<String> problems = Settings.problems(given, project);

        assertEquals("In the environment, SHERIFF_TIMEOUT takes a whole number, not 'soon'. Change the variable or "
                + "unset it.", problems.get(0));
        assertTrue(problems.get(1).startsWith(project.resolve(".sheriff.properties") + " could not be read ("),
                problems.toString());
        assertEquals("soon", Settings.layered(given, project).get("SHERIFF_TIMEOUT"),
                "what reads the variable says when it cannot use it");
    }

    @Test
    @DisplayName("the edits between two names count each letter added, taken out or changed, case aside")
    void editsBetweenNames() {
        assertEquals(3, Settings.edits("", "ABC"));
        assertEquals(3, Settings.edits("ABC", ""));
        assertEquals(1, Settings.edits("A", "B"));
        assertEquals(0, Settings.edits("a", "A"));
        assertEquals(3, Settings.edits("KITTEN", "SITTING"));
        assertEquals(1, Settings.edits("AB", "XAB"));
        assertEquals(1, Settings.edits("XAB", "AB"));
        assertEquals(1, Settings.edits("ABC", "AC"));
        assertEquals(1, Settings.edits("AC", "ABC"));
    }

    @Test
    @DisplayName("a name three edits from a setting is told the setting, one four away is not; a project's file "
            + "names what is no setting too, and a user's file may set what only it may")
    void theClosestSettingIsAtMostThreeEditsAway() throws IOException {
        user("SHERIFF_FAIL_F=1\nSHERIFF_FAIL_=1\nAI_BACKEND=local\n");
        Files.writeString(project.resolve(".sheriff.properties"), "SHERIFF_GATE_T=5\n");

        assertEquals(List.of(
                "In " + project.resolve(".sheriff.properties") + ", SHERIFF_GATE_T is not a setting, so it does "
                        + "nothing. Did you mean SHERIFF_GATE_TTL?",
                "In " + userFile() + ", SHERIFF_FAIL_ is not a setting, so it does nothing.",
                "In " + userFile() + ", SHERIFF_FAIL_F is not a setting, so it does nothing. Did you mean "
                        + "SHERIFF_FAIL_FAST?"),
                Settings.problems(environment, project));
    }

    @Test
    @DisplayName("a blank variable is no value, and a report with nothing wrong has no list of what is")
    void aBlankVariableAndACleanReport() {
        Map<String, String> given = Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_PULL", "  ");

        String report = Settings.report(given, project);

        assertTrue(report.contains("SHERIFF_PULL = always (default)"), report);
        assertFalse(report.contains("What is wrong"), report);
    }

    @Test
    @DisplayName("the list of what is wrong starts on a paragraph of its own")
    void theListOfWhatIsWrongIsAParagraph() throws IOException {
        user("SHERIFF_PULL=siempre\n");
        String newline = System.lineSeparator();

        String report = Settings.report(environment, project);

        assertTrue(report.contains("." + newline + newline + "What is wrong in them, and so not used:" + newline
                + "  - In "), report);
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
    @DisplayName("a project's file may set Sheriff's image, a tag or a digest of it, but no other image, which "
            + "only the user's own file may choose")
    void aProjectChoosesOnlySheriffsImage() throws IOException {
        Path file = project.resolve(".sheriff.properties");
        user("SHERIFF_IMAGE=registry.local/sheriff:mine\n");
        Files.writeString(file, "SHERIFF_IMAGE=evil/miner:latest\n");

        assertEquals("registry.local/sheriff:mine", Settings.layered(environment, project).get("SHERIFF_IMAGE"));
        assertEquals(List.of("In " + file + ", SHERIFF_IMAGE takes only kaizten/sheriff in a project's file, or a "
                + "tag or a digest of it (kaizten/sheriff@sha256:...), not 'evil/miner:latest': a project that is "
                + "cloned must not choose which image runs on the machine of whoever opens it. Set another image in "
                + "your own file. It is ignored."), Settings.problems(environment, project));
        assertTrue(Settings.report(environment, project).contains("SHERIFF_IMAGE = registry.local/sheriff:mine ("
                + userFile() + ")"));
        assertThrows(IllegalArgumentException.class, () -> Settings.change(environment, project, "SHERIFF_IMAGE",
                "evil/miner:latest", true));
        Settings.change(environment, project, "SHERIFF_IMAGE", "kaizten/sheriff@sha256:abc", true);
        assertEquals("kaizten/sheriff@sha256:abc", Settings.layered(environment, project).get("SHERIFF_IMAGE"));
        assertTrue(Settings.problems(environment, project).isEmpty());
    }

    @Test
    @DisplayName("a project's own threshold keys are checked as their settings are, in the name that was written")
    void aProjectsThresholdKeysAreChecked() throws IOException {
        Path file = project.resolve(".sheriff.properties");
        Files.writeString(file, "coverage=9O\nbranches=85%\nmutators=STRONGER, ALL\nmutation=x\n");

        assertEquals(List.of(
                "In " + file + ", coverage takes a share from 0 to 100, not '9O'. It is ignored.",
                "In " + file + ", mutation takes a share from 0 to 100, not 'x'. It is ignored."),
                Settings.problems(environment, project));
    }

    @Test
    @DisplayName("a change goes over every line that sets the setting, so that it is the one read, and putting the "
            + "default back brings no older line back")
    void aChangeGoesOverEveryLineThatSetsIt() throws IOException {
        user("# SHERIFF_TIMEOUT=300\nSHERIFF_TIMEOUT=400\nSHERIFF_GATE_TTL=60\nSHERIFF_TIMEOUT=600\n");

        Settings.change(environment, project, "SHERIFF_TIMEOUT", "900", false);
        assertEquals("900", Settings.layered(environment, project).get("SHERIFF_TIMEOUT"));
        assertTrue(Files.readString(userFile()).startsWith(
                "# SHERIFF_TIMEOUT=300\nSHERIFF_TIMEOUT=900\nSHERIFF_GATE_TTL=60\nSHERIFF_TIMEOUT=900\n"),
                "the settings the file did not name are added after it");
        Settings.change(environment, project, "SHERIFF_TIMEOUT", "", false);
        assertNull(Settings.layered(environment, project).get("SHERIFF_TIMEOUT"));
        assertEquals("60", Settings.layered(environment, project).get("SHERIFF_GATE_TTL"));
        Settings.change(environment, project, "SHERIFF_IMAGE", "kaizten/sheriff:$1", false);
        Settings.change(environment, project, "SHERIFF_IMAGE", "kaizten/sheriff:$2", false);
        assertEquals("kaizten/sheriff:$2", Settings.layered(environment, project).get("SHERIFF_IMAGE"));
    }

    @Test
    @DisplayName("a line written with : or a space, as a properties file takes, is the one changed, and is not "
            + "listed again commented out")
    void everySeparatorIsALine() throws IOException {
        user("SHERIFF_TIMEOUT: 600\nSHERIFF_GATE_TTL 60\nSHERIFF_MUTATION_TIMEOUT=900\n");

        Settings.ensureUserFile(environment);
        String listed = Files.readString(userFile());
        Settings.change(environment, project, "SHERIFF_TIMEOUT", "", false);
        Settings.change(environment, project, "SHERIFF_GATE_TTL", "30", false);
        Settings.change(environment, project, "SHERIFF_MUTATION", "70", false);

        assertFalse(listed.contains("# SHERIFF_TIMEOUT="), listed);
        assertFalse(listed.contains("# SHERIFF_GATE_TTL="), listed);
        assertNull(Settings.layered(environment, project).get("SHERIFF_TIMEOUT"));
        assertEquals("30", Settings.layered(environment, project).get("SHERIFF_GATE_TTL"));
        assertEquals("900", Settings.layered(environment, project).get("SHERIFF_MUTATION_TIMEOUT"),
                "a setting whose name starts another's is not that one");
        assertEquals("70", Settings.layered(environment, project).get("SHERIFF_MUTATION"));
    }

    @Test
    @DisplayName("a setting only commented out is written over its own line, not added again at the end")
    void aCommentedSettingIsWrittenInPlace() throws IOException {
        Files.writeString(project.resolve(".sheriff.properties"), "# SHERIFF_GATE_TTL=900\nprofile=JAVA\n");

        Settings.change(environment, project, "SHERIFF_GATE_TTL", "60", true);

        assertEquals("SHERIFF_GATE_TTL=60\nprofile=JAVA\n", Files.readString(project.resolve(".sheriff.properties")));
    }

    @Test
    @DisplayName("a setting in a module's own file is named, as only the project's file gives the session's "
            + "settings; the module's profile and thresholds are its own")
    void aModulesSettingIsNamed() throws IOException {
        Path module = Files.createDirectories(project.resolve("app"));
        Files.writeString(module.resolve(".sheriff.properties"), "profile=JAVA_DDD\ncoverage=90\nSHERIFF_GATE_TTL=60\n");

        assertEquals(List.of("In " + module.resolve(".sheriff.properties") + ", SHERIFF_GATE_TTL does nothing: "
                + "settings hold for the whole session, so they are read from " + project.resolve(".sheriff.properties")
                + ", and a module's file declares only its profile and coverage thresholds."),
                Settings.problems(environment, project));
        assertNull(Settings.layered(environment, project).get("SHERIFF_GATE_TTL"));
        assertEquals(List.of(), Settings.problems(Map.of(), project.resolve("missing")), "no project, nothing wrong");
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
