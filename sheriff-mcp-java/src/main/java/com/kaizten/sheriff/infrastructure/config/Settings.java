package com.kaizten.sheriff.infrastructure.config;

import com.kaizten.sheriff.infrastructure.coverage.CoverageThresholds;
import com.kaizten.sheriff.infrastructure.process.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The settings a user can change, with their defaults, and where each one is
 * read from: an environment variable, then the project's
 * {@code .sheriff.properties}, then the user's own file, then the default.
 *
 * <p>Environment variables alone were the only way, and a poor one for whoever
 * uses the tools: Codex passes a server and its hooks almost none of them, so
 * each had to be written into every assistant's configuration, by a name only
 * the README gave. The user's file is written at its defaults, every line
 * commented out, so it lists what can be changed, and it is the same for
 * every assistant. A project's file lets a team fix a setting for everyone who
 * opens it, such as one build of Sheriff's image; only the settings a cloned
 * project may choose for whoever opens it are read from there.
 *
 * <p>The user's file is found through the environment given, never the
 * process's own home: a test built from a bare map reads no one's file.
 */
public final class Settings {

    private static final String CATALOG = "/settings.tsv";
    private static final String COMMENT_START = "#";
    private static final String LINE_BREAK = "\\R";
    private static final String NO_CATALOG = "The settings catalog %s is not in this jar.";

    /**
     * Every setting, in the order a person reads them, from the catalog in
     * {@code settings.tsv}, where one is added by adding its line.
     */
    public static final List<Setting> ALL = catalog(CATALOG);

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String FILE_NAME = "sheriff.properties";
    private static final String FOLDER = "sheriff-mcp";
    private static final String CONFIG_HOME = "XDG_CONFIG_HOME";
    private static final String HOME = "HOME";
    private static final String APPLICATION_DATA = "APPDATA";
    private static final String USER_PROFILE = "USERPROFILE";
    private static final String DEFAULT_CONFIG_FOLDER = ".config";
    private static final String NEWLINE = "\n";
    private static final String NOTHING = "";
    private static final String COMMENT = "# ";
    private static final String ASSIGNMENT = "=";
    private static final String LINE_START = "(?m)^[ \\t]*#?[ \\t]*";
    private static final String ACTIVE_LINE_START = "(?m)^[ \\t]*";
    private static final String LINE_END = "[ \\t]*=.*$";
    private static final String TEMPLATE_HEADER = """
            # Sheriff's settings for every project you open. Each one below is at its
            # default, commented out: take the # off a line and change its value, or ask
            # the assistant to (sheriff_config). A project's .sheriff.properties may set
            # the same ones for that project, and an environment variable wins over both.
            # The tools read this file when a session starts; the hooks, on every run.
            """;
    private static final String SOURCE_ENVIRONMENT = "the environment";
    private static final String SOURCE_DEFAULT = "default";
    private static final String WORKED_OUT = "worked out";
    private static final String REPORT_HEADER = "Settings, each from the first that sets it: an environment "
            + "variable, the project's %s, yours, %s, then the default.";
    private static final String NO_USER_FILE = "(none: no HOME, XDG_CONFIG_HOME or APPDATA)";
    private static final String REPORT_LINE = "%n  %s = %s (%s). %s";
    private static final String UNKNOWN = "There is no setting '%s'. The settings are: %s.";
    private static final String NOT_FOR_PROJECTS = "%s is only for your own file: a project that is cloned must not "
            + "choose it for whoever opens it.";
    private static final String NO_FILE = "There is no file of yours to write to: none of HOME, XDG_CONFIG_HOME or "
            + "APPDATA is set.";
    private static final String CHANGED = "%s is %s in %s. The tools take it when the next session starts; the "
            + "hooks, from their next run.";
    private static final String RESET = "%s is back to its default (%s) in %s. The tools take it when the next "
            + "session starts; the hooks, from their next run.";
    private static final String OVERRIDDEN = " While the environment variable %s is set (to %s), it wins over the "
            + "file.";
    private static final String NAME_SEPARATOR = ", ";
    private static final String IGNORED = "In %s, %s It is ignored.";
    private static final String IN_ENVIRONMENT = "In the environment, %s Change the variable or unset it.";
    private static final String ONLY_YOURS = "In %s, %s is ignored: it is read only from your own file, as a "
            + "cloned project must not choose it for whoever opens it.";
    private static final String IN_A_MODULE = "In %s, %s does nothing: settings hold for the whole session, so "
            + "they are read from %s, and a module's file declares only its profile and coverage thresholds.";
    private static final String NOT_A_SETTING = "In %s, %s is not a setting, so it does nothing.";
    private static final String CLOSEST = " Did you mean %s?";
    private static final String UNREADABLE = "%s could not be read (%s), so none of it is used.";
    private static final String PROBLEMS_HEADER = "%n%nWhat is wrong in them, and so not used:";
    private static final String PROBLEM_LINE = "%n  - %s";
    private static final List<String> PROJECT_KEYS = List.of(DeclaredProfile.FILE_KEY,
            CoverageThresholds.LINES_KEY, CoverageThresholds.BRANCHES_KEY, CoverageThresholds.MUTANTS_KEY,
            CoverageThresholds.MUTATORS_KEY);
    private static final String LINES_SETTING = "SHERIFF_COVERAGE";
    private static final String BRANCHES_SETTING = "SHERIFF_BRANCHES";
    private static final String MUTANTS_SETTING = "SHERIFF_MUTATION";
    private static final String MUTATORS_SETTING = "SHERIFF_MUTATORS";
    private static final Map<String, String> PROJECT_KEY_SETTINGS = Map.of(
            CoverageThresholds.LINES_KEY, LINES_SETTING, CoverageThresholds.BRANCHES_KEY, BRANCHES_SETTING,
            CoverageThresholds.MUTANTS_KEY, MUTANTS_SETTING, CoverageThresholds.MUTATORS_KEY, MUTATORS_SETTING);
    private static final int MOST_EDITS = 3;
    private static final int NO_EDITS = 0;
    private static final int ONE_EDIT = 1;

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private Settings() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Reads a catalog of settings this jar carries: a line per setting, and
     * blank lines and comments, which start with {@code #}, between them.
     *
     * @param resource the catalog, as a resource of this jar
     * @return every setting, in its order
     * @throws IllegalStateException when the catalog is missing or a line of
     *     it is not a setting
     */
    static List<Setting> catalog(String resource) {
        InputStream input = Settings.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException(String.format(NO_CATALOG, resource));
        }
        try (Scanner lines = new Scanner(input, StandardCharsets.UTF_8)) {
            return lines.useDelimiter(LINE_BREAK).tokens()
                    .filter(line -> !line.isBlank() && !line.startsWith(COMMENT_START))
                    .map(Setting::parse)
                    .toList();
        }
    }

    /**
     * A setting by its name.
     *
     * @param name the name, as its environment variable
     * @return the setting, or empty when there is none
     */
    public static Optional<Setting> named(String name) {
        return ALL.stream().filter(setting -> setting.name().equals(name)).findFirst();
    }

    /**
     * The user's own file: under {@code XDG_CONFIG_HOME}, else
     * {@code ~/.config}; on Windows under {@code APPDATA} first.
     *
     * @param environment the environment to find it through
     * @return {@code <config>/sheriff-mcp/sheriff.properties}, or empty when
     *     the environment names no home at all
     */
    public static Optional<Path> userFile(Map<String, String> environment) {
        return userFile(environment, Platform.windows());
    }

    /**
     * The user's own file, on one system or the other.
     *
     * @param environment the environment to find it through
     * @param windows whether the system is Windows, where {@code APPDATA}
     *     comes first
     * @return the file, or empty when the environment names no home at all
     */
    static Optional<Path> userFile(Map<String, String> environment, boolean windows) {
        Optional<Path> folder = windows ? named(environment, APPLICATION_DATA) : Optional.empty();
        folder = folder.or(() -> named(environment, CONFIG_HOME))
                .or(() -> named(environment, HOME).map(home -> home.resolve(DEFAULT_CONFIG_FOLDER)))
                .or(() -> named(environment, APPLICATION_DATA))
                .or(() -> named(environment, USER_PROFILE).map(home -> home.resolve(DEFAULT_CONFIG_FOLDER)));
        return folder.map(config -> config.resolve(FOLDER).resolve(FILE_NAME));
    }

    /**
     * A project's own file, the one its profile can be declared in.
     *
     * @param project the project's folder
     * @return its {@code .sheriff.properties}
     */
    public static Path projectFile(Path project) {
        return project.resolve(DeclaredProfile.FILE_NAME);
    }

    /**
     * An environment with every setting it leaves unset taken from the
     * project's file, then the user's. Settings already in it win, and
     * anything else in it stays as it is, as what reads a variable says when
     * it cannot use one. A value in a file that a setting does not take is
     * ignored, as if its line were not there, so the next file, or the
     * default, decides; {@link #problems} says which.
     *
     * @param environment the environment
     * @param project the project's folder, whose file is read
     * @return that environment
     */
    public static Map<String, String> layered(Map<String, String> environment, Path project) {
        Map<String, String> fromProject = read(projectFile(project));
        Map<String, String> fromUser = userFile(environment).map(Settings::read).orElse(Map.of());
        Map<String, String> layered = new LinkedHashMap<>(environment);
        for (Setting setting : ALL) {
            if (isSet(environment, setting.name())) {
                continue;
            }
            fromFiles(setting, fromProject, fromUser).ifPresent(value -> layered.put(setting.name(), value));
        }
        return layered;
    }

    /**
     * What is wrong in the settings, as a person reads it: a value a setting
     * does not take, in the environment or either file; a name in a file that
     * is no setting, with the closest one; a setting the project's file may
     * not choose; a file that cannot be read. What is wrong in a file is
     * ignored.
     *
     * @param environment the environment the tools were started with
     * @param project the project's folder
     * @return a sentence per problem, none when all is well
     */
    public static List<String> problems(Map<String, String> environment, Path project) {
        List<String> problems = new ArrayList<>();
        for (Setting setting : ALL) {
            if (isSet(environment, setting.name())) {
                setting.problemWith(environment.get(setting.name()).strip()).ifPresent(problem -> problems.add(
                        String.format(IN_ENVIRONMENT, problem)));
            }
        }
        problems.addAll(fileProblems(projectFile(project), true));
        problems.addAll(moduleProblems(project));
        userFile(environment).ifPresent(file -> problems.addAll(fileProblems(file, false)));
        return problems;
    }

    /**
     * The settings set in a module's own file, inside the project, which
     * nothing reads: the settings hold for the whole session, so only the
     * project's file gives them, and a module's declares only its profile
     * and coverage thresholds.
     *
     * @param project the project's folder
     * @return a sentence per setting set there
     */
    private static List<String> moduleProblems(Path project) {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> modules = Files.list(project)) {
            for (Path file : modules.map(Settings::projectFile).filter(Files::isRegularFile).sorted().toList()) {
                read(file).keySet().forEach(name -> problems.add(String.format(IN_A_MODULE, file, name,
                        projectFile(project))));
            }
        } catch (IOException exception) {
            return problems;
        }
        return problems;
    }

    /**
     * What is wrong in one file of settings.
     *
     * @param file the file; a missing one has nothing wrong
     * @param forProject whether it is a project's, which may also declare
     *     the profile and the coverage thresholds, and may not set a
     *     setting that is only the user's
     * @return a sentence per problem
     */
    private static List<String> fileProblems(Path file, boolean forProject) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return List.of(String.format(UNREADABLE, file, exception.getMessage()));
        }
        List<String> problems = new ArrayList<>();
        for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
            Optional<Setting> setting = named(key);
            if (setting.isEmpty()) {
                if (!forProject || !PROJECT_KEYS.contains(key)) {
                    problems.add(String.format(NOT_A_SETTING, file, key) + closest(key));
                } else {
                    Optional.ofNullable(PROJECT_KEY_SETTINGS.get(key)).flatMap(Settings::named)
                            .map(same -> same.named(key))
                            .flatMap(same -> same.problemWith(properties.getProperty(key).strip()))
                            .ifPresent(problem -> problems.add(String.format(IGNORED, file, problem)));
                }
            } else if (forProject && !setting.get().project()) {
                problems.add(String.format(ONLY_YOURS, file, key));
            } else {
                String value = properties.getProperty(key).strip();
                setting.get().problemWith(value)
                        .or(() -> forProject ? setting.get().projectProblemWith(value) : Optional.empty())
                        .ifPresent(problem -> problems.add(String.format(IGNORED, file, problem)));
            }
        }
        return problems;
    }

    /**
     * The setting a name that is none was most likely meant to be.
     *
     * @param name the name
     * @return a question naming it, or nothing when none is close
     */
    private static String closest(String name) {
        return ALL.stream().map(Setting::name)
                .filter(candidate -> edits(name, candidate) <= MOST_EDITS)
                .min((first, second) -> Integer.compare(edits(name, first), edits(name, second)))
                .map(candidate -> String.format(CLOSEST, candidate))
                .orElse(NOTHING);
    }

    /**
     * How many letters must be added, taken out or changed to turn one name
     * into another, case aside.
     *
     * @param from the first name
     * @param to the second
     * @return that number
     */
    static int edits(String from, String to) {
        String first = from.toUpperCase(Locale.ROOT);
        String second = to.toUpperCase(Locale.ROOT);
        int[] previous = new int[second.length() + ONE_EDIT];
        for (int column = NO_EDITS; column <= second.length(); column++) {
            previous[column] = column;
        }
        for (int row = ONE_EDIT; row <= first.length(); row++) {
            int[] current = new int[second.length() + ONE_EDIT];
            current[NO_EDITS] = row;
            for (int column = ONE_EDIT; column <= second.length(); column++) {
                int change = first.charAt(row - ONE_EDIT) == second.charAt(column - ONE_EDIT) ? NO_EDITS : ONE_EDIT;
                current[column] = Math.min(Math.min(previous[column], current[column - ONE_EDIT]) + ONE_EDIT,
                        previous[column - ONE_EDIT] + change);
            }
            previous = current;
        }
        return previous[second.length()];
    }

    /**
     * Every setting with its value now and where that comes from.
     *
     * @param environment the environment the tools were started with
     * @param project the project's folder
     * @return the report, a line per setting
     */
    public static String report(Map<String, String> environment, Path project) {
        Path projectFile = projectFile(project);
        Optional<Path> userFile = userFile(environment);
        Map<String, String> fromProject = read(projectFile);
        Map<String, String> fromUser = userFile.map(Settings::read).orElse(Map.of());
        StringBuilder text = new StringBuilder(String.format(REPORT_HEADER, projectFile,
                userFile.map(Path::toString).orElse(NO_USER_FILE)));
        for (Setting setting : ALL) {
            Optional<String> fromProjectFile = usableInProject(fromProject, setting);
            Optional<Map.Entry<String, String>> found = Optional.ofNullable(environment.get(setting.name()))
                    .filter(value -> !value.isBlank())
                    .map(value -> Map.entry(value.strip(), SOURCE_ENVIRONMENT))
                    .or(() -> fromProjectFile.map(value -> Map.entry(value, projectFile.toString())))
                    .or(() -> usable(fromUser, setting).map(value -> Map.entry(value,
                            userFile.map(Path::toString).orElse(SOURCE_DEFAULT))));
            String shownDefault = setting.defaultValue().isEmpty() ? WORKED_OUT : setting.defaultValue();
            text.append(String.format(REPORT_LINE, setting.name(), found.map(Map.Entry::getKey).orElse(shownDefault),
                    found.map(Map.Entry::getValue).orElse(SOURCE_DEFAULT), setting.help()));
        }
        List<String> problems = problems(environment, project);
        if (!problems.isEmpty()) {
            text.append(String.format(PROBLEMS_HEADER));
            problems.forEach(problem -> text.append(String.format(PROBLEM_LINE, problem)));
        }
        return text.toString();
    }

    /**
     * Sets one setting in the user's file or the project's, or puts its
     * default back with an empty value.
     *
     * @param environment the environment the tools were started with
     * @param project the project's folder
     * @param name the setting
     * @param value its new value, empty for the default
     * @param forProject whether it goes in the project's file
     * @return what was done, as a person reads it
     * @throws IllegalArgumentException when there is no such setting, the
     *     value is not one it takes, or the file cannot be written
     */
    public static String change(Map<String, String> environment, Path project, String name, String value,
            boolean forProject) {
        Setting setting = named(name).orElseThrow(() -> new IllegalArgumentException(String.format(UNKNOWN, name,
                String.join(NAME_SEPARATOR, ALL.stream().map(Setting::name).toList()))));
        String trimmed = value.strip();
        setting.problemWith(trimmed).ifPresent(problem -> {
            throw new IllegalArgumentException(problem);
        });
        if (forProject && !setting.project()) {
            throw new IllegalArgumentException(String.format(NOT_FOR_PROJECTS, name));
        }
        if (forProject) {
            setting.projectProblemWith(trimmed).ifPresent(problem -> {
                throw new IllegalArgumentException(problem);
            });
        }
        Path file = forProject ? projectFile(project)
                : userFile(environment).orElseThrow(() -> new IllegalArgumentException(NO_FILE));
        try {
            if (!forProject) {
                ensureUserFile(environment);
            }
            write(file, setting, trimmed);
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        String done = trimmed.isEmpty()
                ? String.format(RESET, name, setting.defaultValue().isEmpty() ? WORKED_OUT : setting.defaultValue(),
                        file)
                : String.format(CHANGED, name, trimmed, file);
        return isSet(environment, name) ? done + String.format(OVERRIDDEN, name, environment.get(name)) : done;
    }

    /**
     * Writes the user's file at its defaults, every setting commented out,
     * when there is none yet; when there is one, adds at its end, the same
     * way, every setting it does not name, so a setting a newer version
     * brings is listed too. Nothing already there is touched. Best effort: a
     * file that cannot be written leaves every setting at its default.
     *
     * @param environment the environment to find the file through
     */
    public static void ensureUserFile(Map<String, String> environment) {
        Optional<Path> file = userFile(environment);
        if (file.isEmpty()) {
            return;
        }
        try {
            if (!Files.exists(file.get())) {
                Files.createDirectories(file.get().getParent());
                Files.writeString(file.get(), template());
                return;
            }
            String text = Files.readString(file.get());
            List<Setting> missing = ALL.stream().filter(setting -> !lineOf(setting).matcher(text).find()).toList();
            if (!missing.isEmpty()) {
                Files.writeString(file.get(), text + (text.endsWith(NEWLINE) ? NOTHING : NEWLINE) + entries(missing));
            }
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * The user's file at its defaults: a header, then each setting's help and
     * its line, commented out.
     *
     * @return that text
     */
    static String template() {
        return TEMPLATE_HEADER + entries(ALL);
    }

    /**
     * Settings as the user's file lists them: each one's help and the values
     * it takes, then its line at its default, commented out, a blank line
     * before each.
     *
     * @param settings the settings
     * @return those lines
     */
    private static String entries(List<Setting> settings) {
        StringBuilder text = new StringBuilder();
        for (Setting setting : settings) {
            text.append(NEWLINE).append(COMMENT).append(setting.help()).append(NEWLINE)
                    .append(COMMENT).append(setting.accepted()).append(NEWLINE)
                    .append(COMMENT).append(setting.name()).append(ASSIGNMENT).append(setting.defaultValue())
                    .append(NEWLINE);
        }
        return text.toString();
    }

    /**
     * What finds a setting's line in a file, commented out or not.
     *
     * @param setting the setting
     * @return that pattern
     */
    private static Pattern lineOf(Setting setting) {
        return Pattern.compile(LINE_START + setting.name() + LINE_END);
    }

    /**
     * The lines that give a setting a value, not commented out.
     *
     * @param setting the setting
     * @return a pattern that finds each one
     */
    private static Pattern activeLineOf(Setting setting) {
        return Pattern.compile(ACTIVE_LINE_START + setting.name() + LINE_END);
    }

    /**
     * Writes one setting into a file: over every line that gives it a value
     * when there is one, as the last of them is the one read and an earlier
     * one would come back with the default; else over its line commented
     * out; else at the end. Commented out at its default for an empty value.
     *
     * @param file the file, created when missing, in a folder that exists
     * @param setting the setting
     * @param value its value, empty for the default
     * @throws IOException when the file cannot be read or written
     */
    private static void write(Path file, Setting setting, String value) throws IOException {
        String text = Files.isRegularFile(file) ? Files.readString(file) : NOTHING;
        String line = value.isEmpty() ? COMMENT + setting.name() + ASSIGNMENT + setting.defaultValue()
                : setting.name() + ASSIGNMENT + value;
        Pattern active = activeLineOf(setting);
        Pattern existing = lineOf(setting);
        String written;
        if (active.matcher(text).find()) {
            written = active.matcher(text).replaceAll(Matcher.quoteReplacement(line));
        } else if (existing.matcher(text).find()) {
            written = existing.matcher(text).replaceFirst(Matcher.quoteReplacement(line));
        } else {
            written = text + (text.isEmpty() || text.endsWith(NEWLINE) ? NOTHING : NEWLINE) + line + NEWLINE;
        }
        Files.writeString(file, written);
    }

    /**
     * The settings a file sets, read as properties; nothing when it is
     * missing or unreadable.
     *
     * @param file the file
     * @return each setting's value by its name
     */
    private static Map<String, String> read(Path file) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Setting setting : ALL) {
            String value = properties.getProperty(setting.name());
            if (value != null) {
                values.put(setting.name(), value);
            }
        }
        return values;
    }

    /**
     * A folder an environment variable names.
     *
     * @param environment the environment
     * @param variable the variable
     * @return the folder, or empty when it is unset or blank
     */
    private static Optional<Path> named(Map<String, String> environment, String variable) {
        String value = environment.get(variable);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value.strip()));
    }

    /**
     * A setting's value in a map, when it has one the setting takes.
     *
     * @param values the map
     * @param setting the setting
     * @return the value, trimmed, or empty when it is missing, blank or not
     *     one the setting takes
     */
    private static Optional<String> usable(Map<String, String> values, Setting setting) {
        if (!isSet(values, setting.name())) {
            return Optional.empty();
        }
        String value = values.get(setting.name()).strip();
        return setting.problemWith(value).isPresent() ? Optional.empty() : Optional.of(value);
    }

    /**
     * A setting's value in a project's file, when the setting is one a
     * project may set, to that value.
     *
     * @param values what the project's file sets
     * @param setting the setting
     * @return the value, trimmed, or empty
     */
    private static Optional<String> usableInProject(Map<String, String> values, Setting setting) {
        return setting.project() ? usable(values, setting).filter(value -> setting.projectProblemWith(value).isEmpty())
                : Optional.empty();
    }

    /**
     * A setting's value from the files: the project's, when it may set it,
     * then the user's.
     *
     * @param setting the setting
     * @param fromProject what the project's file sets
     * @param fromUser what the user's file sets
     * @return the first value the setting takes, or empty
     */
    private static Optional<String> fromFiles(Setting setting, Map<String, String> fromProject,
            Map<String, String> fromUser) {
        Optional<String> project = usableInProject(fromProject, setting);
        return project.or(() -> usable(fromUser, setting));
    }

    /**
     * Whether a map gives a setting a value.
     *
     * @param values the map
     * @param name the setting
     * @return {@code true} when it is there and not blank
     */
    private static boolean isSet(Map<String, String> values, String name) {
        String value = values.get(name);
        return value != null && !value.isBlank();
    }
}
