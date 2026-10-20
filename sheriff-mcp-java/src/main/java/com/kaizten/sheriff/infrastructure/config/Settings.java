package com.kaizten.sheriff.infrastructure.config;

import com.kaizten.sheriff.infrastructure.process.Platform;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * anything else in it stays as it is.
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
            String value = setting.project() && fromProject.containsKey(setting.name())
                    ? fromProject.get(setting.name())
                    : fromUser.get(setting.name());
            if (value != null && !value.isBlank()) {
                layered.put(setting.name(), value.strip());
            }
        }
        return layered;
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
            String value;
            String source;
            if (isSet(environment, setting.name())) {
                value = environment.get(setting.name()).strip();
                source = SOURCE_ENVIRONMENT;
            } else if (setting.project() && isSet(fromProject, setting.name())) {
                value = fromProject.get(setting.name()).strip();
                source = projectFile.toString();
            } else if (isSet(fromUser, setting.name())) {
                value = fromUser.get(setting.name()).strip();
                source = userFile.map(Path::toString).orElse(SOURCE_DEFAULT);
            } else {
                value = setting.defaultValue().isEmpty() ? WORKED_OUT : setting.defaultValue();
                source = SOURCE_DEFAULT;
            }
            text.append(String.format(REPORT_LINE, setting.name(), value, source, setting.help()));
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
     * Settings as the user's file lists them: each one's help, then its line
     * at its default, commented out, a blank line before each.
     *
     * @param settings the settings
     * @return those lines
     */
    private static String entries(List<Setting> settings) {
        StringBuilder text = new StringBuilder();
        for (Setting setting : settings) {
            text.append(NEWLINE).append(COMMENT).append(setting.help()).append(NEWLINE)
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
     * Writes one setting into a file: over its line, commented out or not,
     * when it has one, at the end otherwise; commented out at its default for
     * an empty value.
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
        Pattern existing = lineOf(setting);
        String written;
        if (existing.matcher(text).find()) {
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
