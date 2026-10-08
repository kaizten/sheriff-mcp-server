package com.kaizten.sheriff.infrastructure.antigravity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code HOME} Antigravity's CLI runs with for one pass, so that it may
 * run the project's tests and {@code git mv} with no rule left behind in the
 * user's own settings.
 *
 * <p>{@code agy} takes its permissions from
 * {@code ~/.gemini/antigravity-cli/settings.json} only, through
 * {@code permissions.allow}, and from no flag. Its login lives in the
 * system's keyring, so with another {@code HOME} it still answers, and reads
 * that home's settings instead: a copy of the user's, with the two rules
 * added. Measured with {@code agy} 1.1.16: {@code command(X)} allows
 * {@code X} and {@code X} with more arguments, and refuses anything chained
 * to it with {@code &&}, {@code ;} or {@code $(...)}, a pipe or a
 * redirection.
 *
 * <p>The commands {@code agy} runs inherit that {@code HOME}. Maven and
 * Gradle find their caches through Java's {@code user.home}, which does not
 * follow it; the Maven wrapper and npm do, so the variables that point them
 * back home are set too.
 *
 * <p>On Windows {@code agy} finds its home through {@code USERPROFILE}, which
 * every program there relies on, and this has not been tried there: it runs
 * with the user's own home and no command, as before.
 */
public final class AntigravityHome {

    /**
     * The directory that holds Antigravity's configuration.
     */
    private static final String GEMINI_DIRECTORY = ".gemini";

    /**
     * The directory of the CLI's own configuration.
     */
    private static final String CLI_DIRECTORY = "antigravity-cli";

    /**
     * The CLI's settings file.
     */
    private static final String SETTINGS_FILE = "settings.json";

    /**
     * The settings key that holds the permission rules.
     */
    private static final String PERMISSIONS = "permissions";

    /**
     * The key of the rules that allow without asking.
     */
    private static final String ALLOW = "allow";

    /**
     * How a rule that allows a command is written.
     */
    private static final String COMMAND_RULE = "command(%s)";

    /**
     * The command that renames a file, which the loop follows as a rename.
     */
    private static final String RENAME_COMMAND = "git mv";

    /**
     * The variable {@code agy} finds its home through.
     */
    private static final String HOME_VARIABLE = "HOME";

    /**
     * How the temporary home is named.
     */
    private static final String TEMPORARY_PREFIX = "sheriff-agy-";

    /**
     * The test command when there is none to allow.
     */
    private static final String NO_TEST_COMMAND = "";

    /**
     * Reads and writes the settings.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The user's home, whose settings are copied, or empty to run in it.
     */
    private final Optional<Path> userHome;

    /**
     * The variables that point the build tools back to the user's home.
     */
    private final Map<String, String> buildToolHomes;

    /**
     * The test command the rules allow, empty for none.
     */
    private final String testCommand;

    /**
     * A home for passes that may run the project's tests and rename files.
     *
     * @param userHome the user's home, whose Antigravity settings are copied
     * @param buildToolHomes the variables that point the build tools back to
     *     that home
     * @param testCommand the test command to allow, as run from inside the
     *     component, empty for none
     */
    public AntigravityHome(Path userHome, Map<String, String> buildToolHomes, String testCommand) {
        this(Optional.of(userHome), buildToolHomes, testCommand);
    }

    /**
     * Every field.
     *
     * @param userHome the user's home, or empty to run in it
     * @param buildToolHomes the variables for the build tools
     * @param testCommand the test command to allow
     */
    private AntigravityHome(Optional<Path> userHome, Map<String, String> buildToolHomes, String testCommand) {
        this.userHome = userHome;
        this.buildToolHomes = Map.copyOf(buildToolHomes);
        this.testCommand = testCommand;
    }

    /**
     * The user's own home, and no command: on Windows, or with no home known.
     *
     * @return that home
     */
    public static AntigravityHome none() {
        return new AntigravityHome(Optional.empty(), Map.of(), NO_TEST_COMMAND);
    }

    /**
     * Whether a pass may run commands at all.
     *
     * @return {@code true} when it runs with a home of its own
     */
    public boolean grantsCommands() {
        return userHome.isPresent();
    }

    /**
     * The test command a pass may run.
     *
     * @return that command, empty when there is none or no command is granted
     */
    public String testCommand() {
        return grantsCommands() ? testCommand : NO_TEST_COMMAND;
    }

    /**
     * The rules the settings of a pass allow.
     *
     * @return {@code command(...)} for the tests, when there are any, and for
     *     {@code git mv}
     */
    List<String> rules() {
        String rename = String.format(COMMAND_RULE, RENAME_COMMAND);
        return testCommand.isEmpty() ? List.of(rename) : List.of(String.format(COMMAND_RULE, testCommand), rename);
    }

    /**
     * Creates the home for one pass.
     *
     * @return that home, which deletes itself when closed
     * @throws IOException when it could not be written
     */
    public AntigravityPass open() throws IOException {
        if (userHome.isEmpty()) {
            return new AntigravityPass(Optional.empty(), Map.of());
        }
        Path home = Files.createTempDirectory(TEMPORARY_PREFIX);
        Path settings = settingsUnder(home);
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(settings()));
        Map<String, String> environment = new HashMap<>(buildToolHomes);
        environment.put(HOME_VARIABLE, home.toString());
        return new AntigravityPass(Optional.of(home), environment);
    }

    /**
     * The user's settings with the rules added, every other key as it was.
     *
     * @return those settings
     */
    ObjectNode settings() {
        ObjectNode settings = userSettings();
        JsonNode permissions = settings.path(PERMISSIONS);
        ObjectNode granted = permissions.isObject() ? (ObjectNode) permissions : settings.putObject(PERMISSIONS);
        JsonNode allowed = granted.path(ALLOW);
        ArrayNode allow = allowed.isArray() ? (ArrayNode) allowed : granted.putArray(ALLOW);
        for (String rule : rules()) {
            boolean present = false;
            for (JsonNode existing : allow) {
                present = present || rule.equals(existing.asText());
            }
            if (!present) {
                allow.add(rule);
            }
        }
        return settings;
    }

    /**
     * The user's own settings, or none when they cannot be read as an object.
     *
     * @return a copy of them
     */
    private ObjectNode userSettings() {
        Path settings = settingsUnder(userHome.orElseThrow());
        if (!Files.isRegularFile(settings)) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode read = JSON.readTree(settings.toFile());
            return read != null && read.isObject() ? (ObjectNode) read : JSON.createObjectNode();
        } catch (IOException unreadable) {
            return JSON.createObjectNode();
        }
    }

    /**
     * Where a home keeps the CLI's settings.
     *
     * @param home the home
     * @return the settings file
     */
    private static Path settingsUnder(Path home) {
        return home.resolve(GEMINI_DIRECTORY).resolve(CLI_DIRECTORY).resolve(SETTINGS_FILE);
    }
}
