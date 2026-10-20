package com.kaizten.sheriff.infrastructure.mcp;

/**
 * A shell a component's test command is written for. Shells differ in how one
 * command follows another and in how a wrapper in the current folder is named,
 * and on Windows the server, the hooks and a model each run a different one.
 * Each command is a format taking the quoted directory, then Maven's options;
 * Maven's test command is its command with goals, given {@code test}.
 */
final class TestShell {

    private static final String TEST_GOAL = " test";
    private static final String MAVEN_WRAPPER_RUN = "cd %1$s && ./mvnw%2$s";
    private static final String MAVEN_RUN = "cd %1$s && mvn%2$s";
    private static final String GRADLE_WRAPPER_RUN = "cd %1$s && ./gradlew test";
    private static final String GRADLE_RUN = "cd %1$s && gradle test";
    private static final String NPM_RUN = "cd %1$s && npm test";
    private static final String MAVEN_WRAPPER_RUN_CMD = "cd %1$s && .\\mvnw.cmd%2$s";
    private static final String GRADLE_WRAPPER_RUN_CMD = "cd %1$s && .\\gradlew.bat test";
    private static final String MAVEN_WRAPPER_RUN_WINDOWS = "cd %1$s; ./mvnw.cmd%2$s";
    private static final String MAVEN_RUN_WINDOWS = "mvn -f %1$s%2$s";
    private static final String GRADLE_WRAPPER_RUN_WINDOWS = "cd %1$s; ./gradlew.bat test";
    private static final String GRADLE_RUN_WINDOWS = "gradle -p %1$s test";
    private static final String NPM_RUN_WINDOWS = "npm --prefix %1$s test";

    /**
     * {@code /bin/sh}, Linux's and macOS's, where every command runs.
     */
    static final TestShell POSIX = new TestShell(MAVEN_WRAPPER_RUN, MAVEN_RUN, GRADLE_WRAPPER_RUN, GRADLE_RUN,
            NPM_RUN);

    /**
     * {@code cmd.exe}, which runs the server's and the hooks' own commands on
     * Windows. The wrapper is named from the current folder, {@code .\}:
     * with {@code NoDefaultCurrentDirectoryInExePath} set, as Claude Code
     * sets it for the commands it runs, {@code cmd.exe} no longer looks for a
     * bare {@code mvnw.cmd} there and answers that it is not recognized.
     */
    static final TestShell CMD = new TestShell(MAVEN_WRAPPER_RUN_CMD, MAVEN_RUN, GRADLE_WRAPPER_RUN_CMD, GRADLE_RUN,
            NPM_RUN);

    /**
     * A model's shell on Windows: Git Bash under Claude Code, PowerShell,
     * Windows' or 7, under Codex. None of them runs a wrapper in the current
     * folder without {@code ./}, and Windows PowerShell does not know
     * {@code &&}, so a {@code cd} that fails is followed by the tests anyway,
     * in whatever folder the shell is in. A build tool on the PATH is
     * therefore given the directory instead ({@code -f}, {@code -p},
     * {@code --prefix}), with no {@code cd} to fail: it also reads a folder
     * named with {@code [ ]}, which PowerShell's {@code cd} takes as a
     * wildcard. Only a wrapper, which has to be named from its folder, keeps
     * the {@code cd}.
     */
    static final TestShell ANY_WINDOWS_SHELL = new TestShell(MAVEN_WRAPPER_RUN_WINDOWS, MAVEN_RUN_WINDOWS,
            GRADLE_WRAPPER_RUN_WINDOWS, GRADLE_RUN_WINDOWS, NPM_RUN_WINDOWS);

    private final String mavenWrapper;
    private final String maven;
    private final String gradleWrapper;
    private final String gradle;
    private final String npm;

    /**
     * A shell's way of writing a test command.
     *
     * @param mavenWrapper Maven's wrapper
     * @param maven Maven on the PATH
     * @param gradleWrapper Gradle's wrapper
     * @param gradle Gradle on the PATH
     * @param npm npm's test script
     */
    private TestShell(String mavenWrapper, String maven, String gradleWrapper, String gradle, String npm) {
        this.mavenWrapper = mavenWrapper;
        this.maven = maven;
        this.gradleWrapper = gradleWrapper;
        this.gradle = gradle;
        this.npm = npm;
    }

    /**
     * The test command for Maven's wrapper.
     *
     * @return a format taking the directory and Maven's options
     */
    String mavenWrapper() {
        return mavenWrapper + TEST_GOAL;
    }

    /**
     * The test command for Maven on the PATH.
     *
     * @return a format taking the directory and Maven's options
     */
    String maven() {
        return maven + TEST_GOAL;
    }

    /**
     * Maven's wrapper with goals of the caller's.
     *
     * @return a format taking the directory, then the options and goals
     */
    String mavenWrapperGoals() {
        return mavenWrapper;
    }

    /**
     * Maven on the PATH with goals of the caller's.
     *
     * @return a format taking the directory, then the options and goals
     */
    String mavenGoals() {
        return maven;
    }

    /**
     * The command for Gradle's wrapper.
     *
     * @return a format taking the directory
     */
    String gradleWrapper() {
        return gradleWrapper;
    }

    /**
     * The command for Gradle on the PATH.
     *
     * @return a format taking the directory
     */
    String gradle() {
        return gradle;
    }

    /**
     * The command for npm's test script.
     *
     * @return a format taking the directory
     */
    String npm() {
        return npm;
    }
}
