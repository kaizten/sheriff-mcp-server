package com.kaizten.sheriff.infrastructure.process;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What differs between Windows and the rest, in one place: the shell a
 * command line runs in, how a path is written when it is compared with what
 * Sheriff or git say, and how an executable is found.
 *
 * <p>Every method has a form that takes the platform as an argument, so the
 * Windows behaviour is tested on any machine, and a form that asks the
 * running JVM. Found by building on Windows for the first time: git paths
 * came back with backslashes, so every file a pass edited looked out of
 * scope; the test command ran through {@code /bin/sh}, which Windows lacks;
 * and {@code claude} installed through npm is {@code claude.cmd}, which a
 * process cannot be started by under its bare name.
 */
public final class Platform {

    private static final String OS_NAME = "os.name";
    private static final String WINDOWS_PREFIX = "windows";
    private static final String POSIX_SHELL = "/bin/sh";
    private static final String POSIX_COMMAND_FLAG = "-c";
    private static final String WINDOWS_SHELL = "cmd.exe";
    private static final String WINDOWS_COMMAND_FLAG = "/c";
    private static final char BACKSLASH = '\\';
    private static final char SLASH = '/';
    private static final String SINGLE_QUOTE = "'";
    private static final String DOUBLE_QUOTE = "\"";
    private static final String ROOT = "/";
    private static final char DRIVE_SEPARATOR = ':';
    private static final int DRIVE_LETTER = 0;
    private static final int DRIVE_COLON = 1;
    private static final int AFTER_DRIVE = 2;
    private static final String PATH_VARIABLE = "PATH";
    private static final String PATH_EXTENSIONS_VARIABLE = "PATHEXT";
    private static final String DEFAULT_PATH_EXTENSIONS = ".COM;.EXE;.BAT;.CMD";
    private static final String EXTENSION_SEPARATOR = ";";
    private static final String WINDOWS_PATH_SEPARATOR = ";";
    private static final String DOT = ".";
    private static final String NOTHING = "";
    private static final int FIRST = 0;
    private static final String DOCKER_VARIABLE = "SHERIFF_DOCKER";
    private static final String DOCKER_EXECUTABLE = "docker";
    private static final String WSL_EXECUTABLE = "wsl.exe";
    private static final String WSL_VALUE = "wsl";
    private static final String WSL_DISTRO_PREFIX = "wsl:";
    private static final String WSL_DISTRO_FLAG = "-d";
    private static final String WSL_EXEC_FLAG = "-e";
    private static final String WSL_MOUNT_ROOT = "/mnt/";
    private static final String WINDOWS_SOURCE_REGEX = "(source=)([A-Za-z]):[\\\\/](.*?)(?=\"|,[A-Za-z]+=|$)";
    private static final Pattern WINDOWS_SOURCE = Pattern.compile(WINDOWS_SOURCE_REGEX);
    private static final int SOURCE_PREFIX_GROUP = 1;
    private static final int SOURCE_DRIVE_GROUP = 2;
    private static final int SOURCE_REST_GROUP = 3;
    private static final int SECOND = 1;
    private static final char QUOTE = '"';
    private static final int NO_BACKSLASHES = 0;
    private static final int TWICE = 2;
    private static final String WINDOWS_COMMAND_INTERPRETER = "cmd";
    private static final String COMMAND_SCRIPT_EXTENSION = ".cmd";
    private static final String BATCH_SCRIPT_EXTENSION = ".bat";
    private static final String EXECUTABLE_EXTENSION = ".exe";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    /**
     * The Java property that stands for {@code SHERIFF_DOCKER} where the
     * variable cannot be set, as on a Codex hook's line.
     */
    public static final String DOCKER_PROPERTY = "sheriff.docker";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private Platform() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Whether this JVM runs on Windows.
     *
     * @return {@code true} on Windows
     */
    public static boolean windows() {
        return System.getProperty(OS_NAME, ROOT).toLowerCase(Locale.ROOT).startsWith(WINDOWS_PREFIX);
    }

    /**
     * The command that runs a command line in this platform's shell.
     *
     * @param commandLine what to run
     * @return {@code cmd.exe /c} on Windows, {@code /bin/sh -c} elsewhere
     */
    public static List<String> shell(String commandLine) {
        return shell(commandLine, windows());
    }

    /**
     * The command that runs a command line in a given platform's shell.
     *
     * @param commandLine what to run
     * @param windows whether the platform is Windows
     * @return the command
     */
    public static List<String> shell(String commandLine, boolean windows) {
        return windows
                ? List.of(WINDOWS_SHELL, WINDOWS_COMMAND_FLAG, commandLine)
                : List.of(POSIX_SHELL, POSIX_COMMAND_FLAG, commandLine);
    }

    /**
     * A relative path written with forward slashes, the way Sheriff and git
     * name files whatever the platform.
     *
     * @param path the path
     * @return it, with {@code /} between its parts
     */
    public static String slashes(Path path) {
        return path.toString().replace(BACKSLASH, SLASH);
    }

    /**
     * A path quoted so that a model's shell on Windows reads it as it is,
     * whichever it is: Git Bash, Windows PowerShell or PowerShell 7. In
     * single quotes, with forward slashes, all three take a {@code $} or a
     * backtick literally, which double quotes expanded in every one of them
     * (measured 13 October); a path holding a single quote is double quoted,
     * as nothing else reads the same in all three.
     *
     * @param path the path, with either slash
     * @return it, with {@code /} and quoted
     */
    public static String quotedForAnyWindowsShell(String path) {
        String slashed = path.replace(BACKSLASH, SLASH);
        if (!slashed.contains(SINGLE_QUOTE)) {
            return SINGLE_QUOTE + slashed + SINGLE_QUOTE;
        }
        return DOUBLE_QUOTE + slashed + DOUBLE_QUOTE;
    }

    /**
     * An absolute path the way Claude Code matches it in a permission rule:
     * as it is on POSIX, and on Windows with the drive turned into its first
     * segment, {@code C:\Users\a} as {@code /c/Users/a}.
     *
     * @param absolute the path, as this platform writes it
     * @return it, in the form a rule's {@code //} prefix expects after it
     */
    public static String posixForm(String absolute) {
        String slashed = absolute.replace(BACKSLASH, SLASH);
        boolean drive = slashed.length() > DRIVE_COLON && slashed.charAt(DRIVE_COLON) == DRIVE_SEPARATOR;
        if (!drive) {
            return slashed;
        }
        String letter = String.valueOf(Character.toLowerCase(slashed.charAt(DRIVE_LETTER)));
        return ROOT + letter + slashed.substring(AFTER_DRIVE);
    }

    /**
     * The command, run through the Docker of a WSL distribution when {@code
     * SHERIFF_DOCKER} says so: for a Windows machine whose Docker is the
     * command-line engine inside WSL rather than Docker Desktop. The value is
     * {@code wsl} for the default distribution or {@code wsl:<name>}.
     *
     * @param command the command, its executable first
     * @return the command, unchanged unless it is a {@code docker} one and the variable asks
     */
    public static List<String> dockerCommand(List<String> command) {
        return dockerCommand(command, windows(), environment());
    }

    /**
     * This process's environment, with {@code SHERIFF_DOCKER} taken from the
     * {@code sheriff.docker} property when the variable is not set.
     *
     * @return that environment
     */
    public static Map<String, String> environment() {
        return withDockerProperty(System.getenv(), System.getProperty(DOCKER_PROPERTY));
    }

    /**
     * An environment with {@code SHERIFF_DOCKER} taken from a property when
     * the variable is not in it: Codex's hooks on Windows receive it as
     * {@code -Dsheriff.docker=wsl}, since Codex passes a hook none of the
     * user's variables and a {@code set} on its line printed every one of
     * them to the model instead (13 October).
     *
     * @param environment the environment
     * @param property the property's value, or {@code null}
     * @return the environment, with the variable when only the property had it
     */
    static Map<String, String> withDockerProperty(Map<String, String> environment, String property) {
        boolean variableSet = !environment.getOrDefault(DOCKER_VARIABLE, NOTHING).isBlank();
        if (variableSet || property == null || property.isBlank()) {
            return environment;
        }
        Map<String, String> withDocker = new HashMap<>(environment);
        withDocker.put(DOCKER_VARIABLE, property.trim());
        return withDocker;
    }

    /**
     * The command, run through WSL's Docker, for a given platform and environment.
     *
     * <p>A bind mount's source is the one thing the engine reads as a path of
     * its own, so {@code C:\Users\a} is written {@code /mnt/c/Users/a}. It
     * ends at the next field or, for a path with a comma, which
     * {@code SheriffContainer} quotes whole, at the closing quote.
     *
     * <p>{@code docker} runs with {@code -e}, with no shell: without it
     * {@code wsl.exe} hands the command line to the distribution's shell,
     * which expanded a {@code $} in a path, so a project under
     * {@code pay$app} was mounted from {@code pay} and never analyzed
     * (measured 13 October).
     *
     * @param command the command, its executable first
     * @param windows whether the platform is Windows
     * @param environment where {@code SHERIFF_DOCKER} is read
     * @return the command, unchanged unless it is a {@code docker} one and the variable asks
     */
    public static List<String> dockerCommand(List<String> command, boolean windows, Map<String, String> environment) {
        String setting = environment.getOrDefault(DOCKER_VARIABLE, NOTHING).trim();
        boolean wanted = dockerThroughWsl(windows, environment) && !command.isEmpty()
                && DOCKER_EXECUTABLE.equals(command.get(FIRST));
        if (!wanted) {
            return command;
        }
        List<String> through = new ArrayList<>(List.of(WSL_EXECUTABLE));
        if (setting.startsWith(WSL_DISTRO_PREFIX)) {
            through.addAll(List.of(WSL_DISTRO_FLAG, setting.substring(WSL_DISTRO_PREFIX.length())));
        }
        through.addAll(List.of(WSL_EXEC_FLAG, DOCKER_EXECUTABLE));
        command.stream().skip(SECOND).map(Platform::wslPath).forEach(through::add);
        return through;
    }

    /**
     * Whether Sheriff's Docker is the one inside WSL: on Windows, with
     * {@code SHERIFF_DOCKER} set to {@code wsl} or {@code wsl:<name>}. Never
     * on Linux or macOS, whatever the variable says.
     *
     * @param windows whether the platform is Windows
     * @param environment where {@code SHERIFF_DOCKER} is read
     * @return {@code true} when every docker command goes through WSL
     */
    public static boolean dockerThroughWsl(boolean windows, Map<String, String> environment) {
        String setting = environment.getOrDefault(DOCKER_VARIABLE, NOTHING).trim();
        return windows && (setting.equals(WSL_VALUE) || setting.startsWith(WSL_DISTRO_PREFIX));
    }

    /**
     * The {@code SHERIFF_DOCKER} an installer writes into what an assistant
     * starts, as {@code wsl} or {@code wsl:<name>}: Codex starts its servers
     * and hooks with almost none of the user's variables (measured with Codex
     * 0.162: {@code HOME}, {@code PATH} and a few more), so one that is not in
     * its entry never reaches the jar, which then runs a {@code docker} that
     * is not there. Empty wherever {@link #dockerThroughWsl} is false.
     *
     * @param windows whether the platform is Windows
     * @param environment where {@code SHERIFF_DOCKER} is read
     * @return the value to pass on, or empty
     */
    public static Optional<String> dockerSetting(boolean windows, Map<String, String> environment) {
        if (!dockerThroughWsl(windows, environment)) {
            return Optional.empty();
        }
        return Optional.of(environment.get(DOCKER_VARIABLE).trim());
    }

    /**
     * An argument with the Windows directory of a bind mount's source turned
     * into the path WSL sees it at.
     *
     * @param argument one argument of a docker command
     * @return it, with {@code source=C:\x} as {@code source=/mnt/c/x}
     */
    private static String wslPath(String argument) {
        Matcher matcher = WINDOWS_SOURCE.matcher(argument);
        return matcher.replaceAll(found -> Matcher.quoteReplacement(found.group(SOURCE_PREFIX_GROUP)
                + WSL_MOUNT_ROOT + found.group(SOURCE_DRIVE_GROUP).toLowerCase(Locale.ROOT) + ROOT
                + found.group(SOURCE_REST_GROUP).replace(BACKSLASH, SLASH)));
    }

    /**
     * The command with every argument that holds a double quote quoted for
     * the command line Windows hands a program, so that the program reads
     * the argument it was given. On Windows a process gets one line, not a
     * list, and Java quotes an argument only when it holds a space, leaving
     * a double quote inside it as it is: {@code wsl.exe} and {@code docker}
     * then ended the argument at that quote. Sheriff's catalog was never
     * extracted on Windows, the script reaching {@code sh -c} cut after its
     * first quote, and a project under a folder with a comma was never
     * analyzed, its mount's quotes dropped (both measured 19 October).
     *
     * <p>{@code cmd.exe} is left alone, as it reads its line by rules of its
     * own and the test command it runs already quotes for them, and so are
     * {@code .cmd} and {@code .bat} scripts, which Java refuses an argument
     * with a quote inside.
     *
     * @param command the command, its executable first
     * @param windows whether the platform is Windows
     * @return the command, unchanged off Windows or with no double quote
     */
    public static List<String> quotedForCommandLine(List<String> command, boolean windows) {
        if (!windows || command.isEmpty() || readsItsOwnCommandLine(command.get(FIRST))) {
            return command;
        }
        List<String> quoted = new ArrayList<>(List.of(command.get(FIRST)));
        command.stream().skip(SECOND).map(Platform::quotedArgument).forEach(quoted::add);
        return quoted;
    }

    /**
     * Whether an executable reads its command line other than as Windows
     * programs written in C do: {@code cmd.exe}, and the scripts it runs.
     *
     * @param executable the executable, as the command names it
     * @return {@code true} for {@code cmd}, {@code cmd.exe}, a {@code .cmd} or a {@code .bat}
     */
    private static boolean readsItsOwnCommandLine(String executable) {
        String slashed = executable.replace(BACKSLASH, SLASH);
        String name = slashed.substring(slashed.lastIndexOf(SLASH) + SECOND).toLowerCase(Locale.ROOT);
        return name.equals(WINDOWS_COMMAND_INTERPRETER) || name.equals(WINDOWS_COMMAND_INTERPRETER + EXECUTABLE_EXTENSION)
                || name.endsWith(COMMAND_SCRIPT_EXTENSION) || name.endsWith(BATCH_SCRIPT_EXTENSION);
    }

    /**
     * One argument as a Windows program reads it back: in double quotes, a
     * quote inside it as {@code \"}, and the backslashes before a quote, or
     * before the closing one, doubled. Java passes an argument that starts
     * and ends with a quote as it is.
     *
     * @param argument the argument
     * @return it, quoted, or unchanged when it holds no double quote
     */
    static String quotedArgument(String argument) {
        if (argument.indexOf(QUOTE) < FIRST) {
            return argument;
        }
        StringBuilder quoted = new StringBuilder().append(QUOTE);
        int backslashes = NO_BACKSLASHES;
        for (char character : argument.toCharArray()) {
            if (character == BACKSLASH) {
                backslashes++;
                continue;
            }
            if (character == QUOTE) {
                quoted.append(String.valueOf(BACKSLASH).repeat(backslashes * TWICE + SECOND));
            } else {
                quoted.append(String.valueOf(BACKSLASH).repeat(backslashes));
            }
            quoted.append(character);
            backslashes = NO_BACKSLASHES;
        }
        return quoted.append(String.valueOf(BACKSLASH).repeat(backslashes * TWICE)).append(QUOTE).toString();
    }

    /**
     * The command with its executable found on the {@code PATH}, on Windows,
     * where a {@code .cmd} or {@code .bat} cannot be started by its bare name.
     *
     * @param command the command, its executable first
     * @return the command, unchanged elsewhere or when nothing better is found
     */
    public static List<String> resolved(List<String> command) {
        return resolved(command, windows(), System.getenv());
    }

    /**
     * The command with its executable found on a given {@code PATH}.
     *
     * @param command the command, its executable first
     * @param windows whether the platform is Windows
     * @param environment where {@code PATH} and {@code PATHEXT} are read
     * @return the command, with its executable resolved when it could be
     */
    public static List<String> resolved(List<String> command, boolean windows, Map<String, String> environment) {
        if (!windows || command.isEmpty()) {
            return command;
        }
        String executable = command.get(FIRST);
        if (executable.contains(DOT) || executable.indexOf(SLASH) >= FIRST || executable.indexOf(BACKSLASH) >= FIRST) {
            return command;
        }
        Optional<Path> found = find(executable, environment);
        if (found.isEmpty()) {
            return command;
        }
        List<String> resolved = new ArrayList<>(command);
        resolved.set(FIRST, found.get().toString());
        return resolved;
    }

    /**
     * Looks an executable up on a Windows {@code PATH}, trying each extension
     * {@code PATHEXT} names, in order.
     *
     * @param executable the bare name
     * @param environment where {@code PATH} and {@code PATHEXT} are read
     * @return the first file that exists, or empty
     */
    private static Optional<Path> find(String executable, Map<String, String> environment) {
        String path = environment.getOrDefault(PATH_VARIABLE, environment.getOrDefault(
                PATH_VARIABLE.toLowerCase(Locale.ROOT), NOTHING));
        String extensions = environment.getOrDefault(PATH_EXTENSIONS_VARIABLE, DEFAULT_PATH_EXTENSIONS);
        for (String directory : path.split(WINDOWS_PATH_SEPARATOR)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String extension : extensions.split(EXTENSION_SEPARATOR)) {
                Path candidate = Path.of(directory, executable + extension.toLowerCase(Locale.ROOT));
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
