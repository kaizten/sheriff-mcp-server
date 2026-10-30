package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.infrastructure.catalog.CatalogProvisioning;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.config.Settings;
import com.kaizten.sheriff.infrastructure.coverage.CoverageThresholds;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.docker.SheriffRepair;
import com.kaizten.sheriff.infrastructure.process.Platform;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where this server finds Sheriff, the project, and the rule catalog -- read
 * from the environment when it says, and worked out from the project when it
 * does not, because an MCP server is launched by a client (Claude Code,
 * Claude Desktop, an OpenAI agent) that passes configuration as environment
 * variables and gives it no command line.
 *
 * <p>Nothing is required. Registered once at user scope, the server is
 * started by the client in whatever project is open, and {@link
 * ProjectLayout} works out from that directory what Sheriff mounts, which
 * component is meant and which profile its sources call for. Every variable
 * here only overrides one of those answers.
 */
public final class McpConfig {

    private static final String REPOSITORY = "SHERIFF_REPO";
    private static final String TARGET_REPOSITORY = "TARGET_REPO";
    private static final String TEST_TYPE = "SHERIFF_TEST_TYPE";
    private static final String VERIFICATION = "VERIFICATION_TEST_CMD";
    private static final String IMAGE = "SHERIFF_IMAGE";
    private static final String PROFILE = "SHERIFF_PROFILE";
    private static final String COMPONENT = "SHERIFF_COMPONENT";
    private static final String TIMEOUT = "SHERIFF_TIMEOUT";
    private static final String RULES_CATALOG = "SHERIFF_RULES_CATALOG";
    private static final String TOOL_PREFIX = "SHERIFF_TOOL_PREFIX";
    private static final String SERVER_NAME = "SHERIFF_SERVER_NAME";
    private static final String CACHE_HOME = "XDG_CACHE_HOME";
    private static final String STATE_HOME = "XDG_STATE_HOME";
    private static final String DEFAULT_STATE_DIRECTORY = ".local/state";
    private static final String TASKS_SUBDIRECTORY = "tasks";
    private static final String REPORTS_SUBDIRECTORY = "reports";
    private static final String REPORT = "SHERIFF_REPORT";
    private static final String USER_DIRECTORY = "user.dir";
    private static final String USER_HOME = "user.home";
    private static final String CACHE_SUBDIRECTORY = "sheriff-mcp";
    private static final String CATALOG_FILE_NAME = "rules_catalog.json";
    private static final String DEFAULT_IMAGE = "kaizten/sheriff:latest";
    private static final String DEFAULT_PROFILE = "JAVA";
    private static final String NOT_CONFIGURED = "";
    private static final String DEFAULT_TOOL_PREFIX = "sheriff";
    private static final String NOT_A_NUMBER = "%s must be a whole number of seconds, but it is '%s'.";
    private static final int MINIMUM_TIMEOUT_SECONDS = 1;
    private static final String TEST_TIMEOUT = "SHERIFF_TEST_TIMEOUT";
    private static final String REPAIR_ROUNDS = "SHERIFF_REPAIR_ROUNDS";
    private static final int MINIMUM_ROUNDS = 1;
    private static final String COVERAGE_TIMEOUT = "SHERIFF_COVERAGE_TIMEOUT";
    private static final String MUTATION_TIMEOUT = "SHERIFF_MUTATION_TIMEOUT";
    private static final Duration DEFAULT_TEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_COVERAGE_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration DEFAULT_MUTATION_TIMEOUT = Duration.ofMinutes(30);
    private static final String LINES_SETTING = "SHERIFF_COVERAGE";
    private static final String BRANCHES_SETTING = "SHERIFF_BRANCHES";
    private static final String MUTANTS_SETTING = "SHERIFF_MUTATION";
    private static final String MUTATORS_SETTING = "SHERIFF_MUTATORS";
    private static final String NO_VALUE = "";
    private static final String PROTOTYPE = "SHERIFF_PROTOTYPE";
    private static final String ENABLED = "1";
    private static final String NOT_POSITIVE = "%s must be at least 1 second, but it is '%s'.";
    private final Map<String, String> environment;
    private final Path workingDirectory;
    private ProjectLayout layout;
    private Map<String, String> settings;

    /**
     * Wires the configuration to a set of environment variables, in the
     * directory the process was started in.
     *
     * @param environment the variables to read, typically {@code System.getenv()}
     */
    public McpConfig(Map<String, String> environment) {
        this(environment, Path.of(System.getProperty(USER_DIRECTORY)));
    }

    /**
     * Wires the configuration to a set of environment variables and the
     * project the server was started in.
     *
     * @param environment the variables to read
     * @param workingDirectory the project a client started the server in
     */
    public McpConfig(Map<String, String> environment, Path workingDirectory) {
        this.environment = environment;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Configuration read from the real process environment.
     *
     * @return that configuration
     */
    public static McpConfig fromEnvironment() {
        return new McpConfig(System.getenv());
    }

    /**
     * What the project looks like to Sheriff.
     *
     * @return the layout of {@code SHERIFF_REPO} when it is set, taken as
     *     the mount as it stands; otherwise the layout detected from the
     *     directory the server was started in
     */
    public ProjectLayout layout() {
        if (layout == null) {
            String configured = environment.get(REPOSITORY);
            layout = configured == null || configured.isEmpty()
                    ? ProjectLayout.detect(workingDirectory)
                    : ProjectLayout.mountedAt(Path.of(configured));
        }
        return layout;
    }

    /**
     * The directory Sheriff mounts at {@code /data}.
     *
     * @return that directory: the project, or its parent when the project is
     *     a single module, since Sheriff analyzes a subdirectory of its mount
     */
    public Path repository() {
        return layout().mount();
    }

    /**
     * The environment the agent runs with for this project: the mount, the
     * image and the time limit as this configuration resolved them, and, for
     * one component, that component with the profile it is checked under and
     * its test command, unless those two were set already.
     *
     * <p>One place for it on purpose. The hooks, {@code sheriff_autofix} and
     * the agent's command line each built this by hand, and the copies
     * drifted: the command line set neither the profile nor the tests, so the
     * loop the Stop hook suggests for a TypeScript component analyzed it as
     * Java and ran Maven.
     *
     * @param base the environment to start from
     * @param component the component the agent works on, or empty for none
     * @return that environment
     */
    public Map<String, String> agentEnvironment(Map<String, String> base, String component) {
        Map<String, String> agent = new LinkedHashMap<>(base);
        agent.put(TARGET_REPOSITORY, repository().toString());
        agent.put(IMAGE, image());
        agent.put(TIMEOUT, Long.toString(timeout().toSeconds()));
        if (!component.isEmpty()) {
            agent.put(COMPONENT, component);
            agent.putIfAbsent(TEST_TYPE, profileFor(component));
            agent.putIfAbsent(VERIFICATION, layout().verificationCommand(component));
        }
        return agent;
    }

    /**
     * The Sheriff image to run.
     *
     * @return its name and tag
     */
    public String image() {
        return value(IMAGE, DEFAULT_IMAGE);
    }

    /**
     * The profile set by hand, if any.
     *
     * @return {@code SHERIFF_PROFILE}, or the empty string
     */
    public String configuredProfile() {
        return value(PROFILE, NOT_CONFIGURED);
    }

    /**
     * The profile a call on a component falls back to when it names none.
     *
     * @param component the component the call is about, possibly empty
     * <p>A declared profile applies to the components written in its
     * language: one declared at the root of a repository that also holds a
     * TypeScript front end is not that front end's, which would otherwise be
     * analyzed under a Java profile and show nothing.
     *
     * <p>An architecture profile comes with its language's base profile, as
     * a comma-separated list, because it does not include the base rules.
     *
     * <p>{@code SHERIFF_PROFILE} keeps to its language too, as the user's
     * own file sets it for every project they open.
     *
     * @return {@code SHERIFF_PROFILE} when set; otherwise the one the
     *     project declares for it; otherwise the base profile of the
     *     language the component's sources are in; {@code JAVA} when there
     *     is nothing to tell by
     */
    public String profileFor(String component) {
        return Rules.withBaseProfiles(chosenProfile(component));
    }

    /**
     * The profile chosen for a component, before the base profile of its
     * language is added to it.
     *
     * @param component the component the call is about, possibly empty
     * @return that profile, or list of them
     */
    private String chosenProfile(String component) {
        String configured = configuredProfile();
        ProjectLayout layout = layout();
        if (!configured.isEmpty() && (component.isEmpty() || layout.holdsSourcesFor(component, configured))) {
            return configured;
        }
        Optional<String> declared = layout.declaredProfile(component)
                .filter(profile -> layout.holdsSourcesFor(component, profile));
        if (declared.isPresent() || component.isEmpty()) {
            return declared.orElse(DEFAULT_PROFILE);
        }
        return layout.profileFor(component).orElse(DEFAULT_PROFILE);
    }

    /**
     * The component a tool call falls back to when it names none.
     *
     * @return {@code SHERIFF_COMPONENT} when set; otherwise the project
     *     itself when it is one module, or the only component it holds;
     *     empty when there is no single answer
     */
    public String defaultComponent() {
        String configured = value(COMPONENT, NOT_CONFIGURED);
        return configured.isEmpty() ? layout().defaultComponent() : configured;
    }

    /**
     * What is wrong with this configuration, when something is: today, a
     * {@code SHERIFF_TIMEOUT} that is not a whole number of seconds, or is
     * not at least one. It used to be thrown at startup, which a client
     * shows only as "connection closed"; now the server starts, and every
     * tool that would run Sheriff answers with this instead.
     *
     * @return the problem, in words the user can act on, or empty
     */
    public Optional<String> problem() {
        String configured = settings().get(TIMEOUT);
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        try {
            int seconds = Integer.parseInt(configured.strip());
            return seconds >= MINIMUM_TIMEOUT_SECONDS
                    ? Optional.empty()
                    : Optional.of(String.format(NOT_POSITIVE, TIMEOUT, configured));
        } catch (NumberFormatException exception) {
            return Optional.of(String.format(NOT_A_NUMBER, TIMEOUT, configured));
        }
    }

    /**
     * How long one Sheriff invocation is given.
     *
     * @return that deadline
     * @throws IllegalArgumentException naming the variable when it is not a
     *     number
     */
    public Duration timeout() {
        String configured = settings().get(TIMEOUT);
        if (configured == null || configured.isBlank() || problem().isPresent()) {
            return Duration.ofSeconds(Configuration.defaultSheriffTimeout(Platform.windows(), settings()));
        }
        return Duration.ofSeconds(Integer.parseInt(configured.strip()));
    }

    /**
     * The catalog path set by hand, if any.
     *
     * @return {@code SHERIFF_RULES_CATALOG}, or the empty string
     */
    public String configuredCatalog() {
        return value(RULES_CATALOG, NOT_CONFIGURED);
    }

    /**
     * Where an extracted rule catalog may already be: beside the running
     * jar, which in a checkout is this module, where the extractor writes it.
     *
     * @return those candidates
     */
    public List<Path> catalogCandidates() {
        return List.of(moduleRoot().resolve(CATALOG_FILE_NAME));
    }

    /**
     * Where this server keeps the catalog it extracts for itself.
     *
     * @return {@code $XDG_CACHE_HOME/sheriff-mcp/rules_catalog.json}, or the
     *     same under {@code ~/.cache}
     */
    public Path cachedCatalog() {
        return CatalogProvisioning.cache(value(CACHE_HOME, NOT_CONFIGURED));
    }

    /**
     * Where each task keeps its record and the JSON Sheriff wrote for it.
     *
     * <p>State, not cache: a task's record is the output of a call and is
     * not rebuilt when deleted, which is the line the XDG layout draws.
     *
     * @return {@code $XDG_STATE_HOME/sheriff-mcp/tasks}, or the same under
     *     {@code ~/.local/state}
     */
    public Path tasksDirectory() {
        return stateDirectory().resolve(TASKS_SUBDIRECTORY);
    }

    /**
     * Where a session's report goes when every session is reported, the
     * debugging mode {@code SHERIFF_REPORT} turns on, beside the reports of
     * the loop's runs.
     *
     * @return {@code $XDG_STATE_HOME/sheriff-mcp/reports}, or the same under
     *     {@code ~/.local/state}, or empty when reports are off
     */
    public Optional<Path> reportsDirectory() {
        return ENABLED.equals(value(REPORT, NO_VALUE).strip())
                ? Optional.of(stateDirectory().resolve(REPORTS_SUBDIRECTORY)) : Optional.empty();
    }

    /**
     * This tools' folder of state: tasks and reports.
     *
     * @return {@code $XDG_STATE_HOME/sheriff-mcp}, or the same under
     *     {@code ~/.local/state}
     */
    private Path stateDirectory() {
        String stateHome = value(STATE_HOME, NOT_CONFIGURED);
        Path base = stateHome.isEmpty()
                ? Path.of(System.getProperty(USER_HOME), DEFAULT_STATE_DIRECTORY)
                : Path.of(stateHome);
        return base.resolve(CACHE_SUBDIRECTORY);
    }

    /**
     * Where the rule catalog is expected, without extracting anything.
     *
     * @return the configured path, else the first candidate that exists,
     *     else the cache
     */
    public Path rulesCatalog() {
        String configured = configuredCatalog();
        if (!configured.isEmpty()) {
            return Path.of(configured);
        }
        for (Path candidate : catalogCandidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return cachedCatalog();
    }

    /**
     * The tool-name prefix, which is what a client shows next to every call.
     *
     * @return that prefix, {@code sheriff} unless overridden
     */
    public String toolPrefix() {
        return value(TOOL_PREFIX, DEFAULT_TOOL_PREFIX);
    }

    /**
     * The server's own name in the MCP handshake.
     *
     * @return that name, the tool prefix unless overridden
     */
    public String serverName() {
        return value(SERVER_NAME, toolPrefix());
    }

    /**
     * Where this module's own code is running from, worked out from the
     * running jar rather than from the current directory -- the current
     * directory is the analyzed project, not this tool's own.
     *
     * @return that directory
     */
    private static Path moduleRoot() {
        try {
            Path location = Path.of(McpConfig.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            return Configuration.moduleRootOf(location);
        } catch (URISyntaxException | RuntimeException exception) {
            return Path.of(System.getProperty(USER_DIRECTORY));
        }
    }

    /**
     * The environment the server was given, with every setting it leaves
     * unset taken from the project's {@code .sheriff.properties}, then from
     * the user's own file ({@link Settings}).
     *
     * @return that environment, read once
     */
    public Map<String, String> settings() {
        if (settings == null) {
            settings = Settings.layered(environment, settingsProject());
        }
        return settings;
    }

    /**
     * What is wrong in the settings the server was started with, each of
     * which is ignored ({@link Settings#problems}).
     *
     * @return a sentence per problem, none when all is well
     */
    public List<String> settingsProblems() {
        return Settings.problems(environment, settingsProject());
    }

    /**
     * The environment the server was given, before any file: an environment
     * variable is the one setting no file overrides.
     *
     * @return that environment
     */
    public Map<String, String> givenEnvironment() {
        return environment;
    }

    /**
     * The project whose {@code .sheriff.properties} holds its settings: the
     * module itself for a project that is one, else the folder it is
     * mounted from.
     *
     * @return that folder
     */
    public Path settingsProject() {
        return layout().projectRoot();
    }

    /**
     * What to do about Sheriff's image when it is missing or out of date.
     *
     * @return the {@code SHERIFF_PULL} policy
     */
    public String pullPolicy() {
        return ImageProvisioning.policyIn(settings());
    }

    /**
     * How many rounds of Sheriff's own fixers a repair runs.
     *
     * @return {@code SHERIFF_REPAIR_ROUNDS}, or no limit, when it is unset or
     *     no whole number from one
     */
    public int repairRounds() {
        try {
            int configured = Integer.parseInt(value(REPAIR_ROUNDS, NO_VALUE).strip());
            return configured >= MINIMUM_ROUNDS ? configured : SheriffRepair.NO_LIMIT;
        } catch (NumberFormatException exception) {
            return SheriffRepair.NO_LIMIT;
        }
    }

    /**
     * Whether an idea is being tried out, so that a component is done when
     * Sheriff finds no errors: no tests asked for, run or measured.
     *
     * @return {@code true} when {@code SHERIFF_PROTOTYPE=1}
     */
    public boolean prototype() {
        return ENABLED.equals(value(PROTOTYPE, NO_VALUE).strip());
    }

    /**
     * How long the project's tests get when the tools run them.
     *
     * @return {@code SHERIFF_TEST_TIMEOUT}, ten minutes by default
     */
    public Duration testTimeout() {
        return seconds(TEST_TIMEOUT, DEFAULT_TEST_TIMEOUT);
    }

    /**
     * How long the tests get under JaCoCo.
     *
     * @return {@code SHERIFF_COVERAGE_TIMEOUT}, ten minutes by default
     */
    public Duration coverageTimeout() {
        return seconds(COVERAGE_TIMEOUT, DEFAULT_COVERAGE_TIMEOUT);
    }

    /**
     * How long PIT gets.
     *
     * @return {@code SHERIFF_MUTATION_TIMEOUT}, half an hour by default
     */
    public Duration mutationTimeout() {
        return seconds(MUTATION_TIMEOUT, DEFAULT_MUTATION_TIMEOUT);
    }

    /**
     * How well a component's tests must test what a change writes: an
     * environment variable first, then what the project declares where its
     * profile is ({@code sheriff.coverage} in a {@code pom.xml}), then the
     * project's settings file and the user's, then the defaults.
     *
     * @param component the component, relative to the mount
     * @return those thresholds
     */
    public CoverageThresholds coverageThresholds(String component) {
        return CoverageThresholds.declared(
                threshold(component, LINES_SETTING, CoverageThresholds.LINES_PROPERTY, CoverageThresholds.LINES_KEY),
                threshold(component, BRANCHES_SETTING, CoverageThresholds.BRANCHES_PROPERTY,
                        CoverageThresholds.BRANCHES_KEY),
                threshold(component, MUTANTS_SETTING, CoverageThresholds.MUTANTS_PROPERTY,
                        CoverageThresholds.MUTANTS_KEY),
                threshold(component, MUTATORS_SETTING, CoverageThresholds.MUTATORS_PROPERTY,
                        CoverageThresholds.MUTATORS_KEY));
    }

    /**
     * One threshold, from the first place that gives it one it takes: a
     * declaration it does not take is passed over, as a value in a settings
     * file is.
     *
     * @param component the component
     * @param setting the setting that gives it
     * @param property the {@code pom.xml} property that declares it
     * @param key the {@code .sheriff.properties} key that declares it
     * @return its value as written, or empty for the default
     */
    private Optional<String> threshold(String component, String setting, String property, String key) {
        String given = environment.get(setting);
        if (given != null && !given.isBlank()) {
            return Optional.of(given);
        }
        return layout().declaredSetting(component, property, key)
                .filter(declared -> Settings.named(setting).flatMap(known -> known.problemWith(declared.strip()))
                        .isEmpty())
                .or(() -> Optional.ofNullable(settings().get(setting)));
    }

    /**
     * A number of seconds a setting gives, or its default when it gives none
     * that is a whole number from one.
     *
     * @param name the setting
     * @param fallback its default
     * @return that duration
     */
    private Duration seconds(String name, Duration fallback) {
        try {
            int configured = Integer.parseInt(value(name, NO_VALUE).strip());
            return configured >= MINIMUM_TIMEOUT_SECONDS ? Duration.ofSeconds(configured) : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * One variable's value, treating an unset or empty one as the default.
     *
     * @param name the variable to read
     * @param fallback what to use when it is unset or empty
     * @return the configured value, or the fallback
     */
    private String value(String name, String fallback) {
        String configured = settings().get(name);
        return configured == null || configured.isEmpty() ? fallback : configured;
    }
}
