package com.kaizten.sheriff.infrastructure.mcp;

/**
 * A shell a component's test command is written for. Shells differ in how one
 * command follows another and in how a wrapper in the current folder is named,
 * and on Windows the server, the hooks and a model each run a different one.
 */
final class TestShell {

    private static final String IN_DIRECTORY = "cd %s && %s";
    private static final String IN_DIRECTORY_ANY_WINDOWS_SHELL = "cd %s; %s";
    private static final String MAVEN_WRAPPER_RUN = "./mvnw%s test";
    private static final String GRADLE_WRAPPER_RUN = "./gradlew test";
    private static final String MAVEN_WRAPPER_RUN_CMD = ".\\mvnw.cmd%s test";
    private static final String GRADLE_WRAPPER_RUN_CMD = ".\\gradlew.bat test";
    private static final String MAVEN_WRAPPER_RUN_WINDOWS = "./mvnw.cmd%s test";
    private static final String GRADLE_WRAPPER_RUN_WINDOWS = "./gradlew.bat test";

    /**
     * {@code /bin/sh}, Linux's and macOS's, where every command runs.
     */
    static final TestShell POSIX = new TestShell(IN_DIRECTORY, MAVEN_WRAPPER_RUN, GRADLE_WRAPPER_RUN);

    /**
     * {@code cmd.exe}, which runs the server's and the hooks' own commands on
     * Windows. The wrapper is named from the current folder, {@code .\}:
     * with {@code NoDefaultCurrentDirectoryInExePath} set, as Claude Code
     * sets it for the commands it runs, {@code cmd.exe} no longer looks for a
     * bare {@code mvnw.cmd} there and answers that it is not recognized.
     */
    static final TestShell CMD = new TestShell(IN_DIRECTORY, MAVEN_WRAPPER_RUN_CMD, GRADLE_WRAPPER_RUN_CMD);

    /**
     * A model's shell on Windows: Git Bash under Claude Code, PowerShell,
     * Windows' or 7, under Codex. None of them runs a wrapper in the current
     * folder without {@code ./}, and Windows PowerShell does not know
     * {@code &&}.
     */
    static final TestShell ANY_WINDOWS_SHELL = new TestShell(IN_DIRECTORY_ANY_WINDOWS_SHELL,
            MAVEN_WRAPPER_RUN_WINDOWS, GRADLE_WRAPPER_RUN_WINDOWS);

    private final String inDirectory;
    private final String mavenWrapper;
    private final String gradleWrapper;

    /**
     * A shell's way of writing a test command.
     *
     * @param inDirectory the format of a command run in a directory
     * @param mavenWrapper the format of Maven's wrapper, with its options
     * @param gradleWrapper Gradle's wrapper
     */
    private TestShell(String inDirectory, String mavenWrapper, String gradleWrapper) {
        this.inDirectory = inDirectory;
        this.mavenWrapper = mavenWrapper;
        this.gradleWrapper = gradleWrapper;
    }

    /**
     * The format of a command run in a directory.
     *
     * @return a format taking the directory and the command
     */
    String inDirectory() {
        return inDirectory;
    }

    /**
     * The format of Maven's wrapper.
     *
     * @return a format taking Maven's options
     */
    String mavenWrapper() {
        return mavenWrapper;
    }

    /**
     * Gradle's wrapper.
     *
     * @return the command
     */
    String gradleWrapper() {
        return gradleWrapper;
    }
}
