package com.kaizten.sheriff.infrastructure.config;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.infrastructure.catalog.CatalogProvisioning;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Everything configurable, read from the environment once.
 *
 * <p>Two roots, not one, and the distinction matters: {@code agentDirectory} is
 * where this tool's own files live — its rule catalog and its prompt logs —
 * while {@code targetRepository} is the project being analyzed. They are the
 * same repository by default and different the moment the agent is pointed
 * somewhere else, at which point anchoring the catalog to the target would
 * mean losing the rules.
 */
public final class Configuration {

    private static final String TARGET_REPOSITORY = "TARGET_REPO";
    private static final String IMAGE = "SHERIFF_IMAGE";
    private static final String TEST_TYPE = "SHERIFF_TEST_TYPE";
    private static final String COMPONENT = "SHERIFF_COMPONENT";
    private static final String SHERIFF_TIMEOUT = "SHERIFF_TIMEOUT";
    private static final String RULES_CATALOG = "RULES_CATALOG";
    private static final String CACHE_HOME = "XDG_CACHE_HOME";
    private static final String HOME = "HOME";
    private static final String WINDOWS_HOME = "USERPROFILE";
    private static final List<String> HOME_VARIABLES = List.of(HOME, WINDOWS_HOME);
    private static final String MAVEN_USER_HOME = "MAVEN_USER_HOME";
    private static final String MAVEN_DIRECTORY = ".m2";
    private static final String GRADLE_USER_HOME = "GRADLE_USER_HOME";
    private static final String GRADLE_DIRECTORY = ".gradle";
    private static final String NPM_CACHE = "npm_config_cache";
    private static final String NPM_CACHE_DIRECTORY = ".npm";
    private static final String NPM_CONFIGURATION = "npm_config_userconfig";
    private static final String NPM_CONFIGURATION_FILE = ".npmrc";
    private static final Map<String, String> BUILD_TOOL_HOMES = Map.of(MAVEN_USER_HOME, MAVEN_DIRECTORY,
            GRADLE_USER_HOME, GRADLE_DIRECTORY, NPM_CACHE, NPM_CACHE_DIRECTORY, NPM_CONFIGURATION,
            NPM_CONFIGURATION_FILE);
    private static final String DEFAULT_CACHE_DIRECTORY = ".cache";
    private static final String MAX_RULES = "SHERIFF_MAX_RULES_IN_PROMPT";
    private static final String GIT_SAFETY = "SHERIFF_AGENT_GIT_SAFETY";
    private static final String MAX_ITERATIONS = "SHERIFF_MAX_ITERATIONS";
    private static final String MAX_FILES_PER_BATCH = "SHERIFF_MAX_FILES_PER_BATCH";
    private static final String BACKEND = "AI_BACKEND";
    private static final String CLAUDE_TIMEOUT = "SHERIFF_AGENT_CLAUDE_TIMEOUT";
    private static final String API_KEY = "ANTHROPIC_API_KEY";
    private static final String OPENAI_KEY = "OPENAI_API_KEY";
    private static final String OPENAI_BASE_URL = "OPENAI_BASE_URL";
    private static final String OPENAI_MODEL = "OPENAI_MODEL";
    private static final String OPENAI_MAX_TOKENS = "OPENAI_MAX_TOKENS";
    private static final String OPENAI_TOOL_ITERATIONS = "OPENAI_MAX_TOOL_ITERATIONS";
    private static final String CODEX_COMMAND = "CODEX_COMMAND";
    private static final String CODEX_TIMEOUT = "SHERIFF_AGENT_CODEX_TIMEOUT";
    private static final String ANTIGRAVITY_COMMAND = "ANTIGRAVITY_COMMAND";
    private static final String ANTIGRAVITY_TIMEOUT = "SHERIFF_AGENT_ANTIGRAVITY_TIMEOUT";
    private static final String API_MODEL = "ANTHROPIC_MODEL";
    private static final String API_BASE_URL = "ANTHROPIC_BASE_URL";
    private static final String API_MAX_TOKENS = "ANTHROPIC_MAX_TOKENS";
    private static final String API_MAX_TOOL_ITERATIONS = "ANTHROPIC_MAX_TOOL_ITERATIONS";
    private static final String VERIFICATION_COMMAND = "VERIFICATION_TEST_CMD";
    private static final String VERIFICATION_TIMEOUT = "VERIFICATION_TIMEOUT";
    private static final String PROMPT_LOG = "PROMPT_LOG_DIR";
    private static final String EXPORT_DIRECTORY = "SHERIFF_EXPORT_DIR";
    private static final String FAIL_FAST = "SHERIFF_FAIL_FAST";
    private static final String MOCK = "SHERIFF_MOCK";
    private static final String MOCK_MARKER = "SHERIFF_MOCK_MARKER";
    private static final String AGENT_RUNNING = "SHERIFF_AGENT_RUNNING";
    private static final String PROJECT_DIRECTORY = "CLAUDE_PROJECT_DIR";
    private static final String STOP_RUNS_TESTS = "SHERIFF_STOP_RUNS_TESTS";
    private static final String STOP_MAX_BLOCKS = "SHERIFF_STOP_MAX_BLOCKS";
    private static final int DEFAULT_STOP_MAX_BLOCKS = 5;
    private static final String AGENT_DIRECTORY = "SHERIFF_AGENT_DIR";
    private static final String CURRENT_DIRECTORY = ".";
    private static final String BUILD_DIRECTORY = "target";
    private static final String JAR_SUFFIX = ".jar";

    private static final String DEFAULT_IMAGE = "kaizten/sheriff:latest";
    private static final String DEFAULT_TEST_TYPE = "JAVA";
    private static final String DEFAULT_COMPONENT = "sheriff-mcp-java";
    private static final String DEFAULT_MODEL = "claude-opus-5";
    private static final String DEFAULT_MARKER = "// SHERIFF: TODO";
    private static final String DEFAULT_VERIFICATION = "cd %s && mvn -q test";
    private static final String CATALOG_FILE = "rules_catalog.json";
    private static final String LOGS_DIRECTORY = "logs";
    private static final String ENABLED = "1";
    private static final String DISABLED = "0";
    private static final String DEFAULT_BACKEND = "claude_cli";
    private static final String NO_API_KEY = "";
    private static final int DEFAULT_SHERIFF_TIMEOUT = 300;
    private static final int DEFAULT_CLAUDE_TIMEOUT = 600;
    private static final int DEFAULT_VERIFICATION_TIMEOUT = 300;
    private static final int DEFAULT_BATCH = 5;
    private static final long DEFAULT_MAX_TOKENS = 8000L;
    private static final long DEFAULT_API_MAX_TOKENS = 16000L;
    private static final int DEFAULT_TOOL_ITERATIONS = 20;
    private static final String NOT_CONFIGURED = "";
    private static final String DEFAULT_CODEX_COMMAND = "codex exec --sandbox workspace-write";
    private static final String DEFAULT_ANTIGRAVITY_COMMAND = "agy --mode accept-edits";
    private static final String WHITESPACE = "\\s+";
    private static final String NOT_A_NUMBER = "%s must be a whole number, but it is '%s'.";
    private static final String NOT_POSITIVE = "%s must be at least 1, but it is '%s'.";
    private static final int MINIMUM_POSITIVE = 1;

    private final Map<String, String> environment;
    private final Path agentDirectory;

    /**
     * Reads configuration from a set of environment variables.
     *
     * @param environment the variables to read, typically {@code System.getenv()}
     * @param agentDirectory where this tool's own files live
     */
    public Configuration(Map<String, String> environment, Path agentDirectory) {
        this.environment = environment;
        this.agentDirectory = agentDirectory.toAbsolutePath().normalize();
    }

    /**
     * Configuration from the real environment, with the agent's directory
     * taken as the current one.
     *
     * @return that configuration
     */
    public static Configuration fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    /**
     * Configuration from a given environment, with the agent's directory
     * taken from where its code is, as {@link #fromEnvironment()} does.
     *
     * @param environment the variables to read
     * @return that configuration
     */
    public static Configuration fromEnvironment(Map<String, String> environment) {
        return new Configuration(environment, defaultAgentDirectory());
    }

    /**
     * Where this tool's own files live, worked out from where its own code is
     * rather than from the current directory.
     *
     * <p>This is not a detail. The prompt log and the rule catalog are
     * anchored here, and the current directory is whatever the caller happened
     * to be in — so taking it from there wrote the agent's logs into the
     * <em>analyzed</em> repository, where the loop's own scope check saw an
     * unexpected file and cut the run. Found by running the agent against its
     * own code; no unit test had noticed. The Python original never had the
     * bug because it derives the same path from its module's location.
     *
     * @return that directory
     */
    static Path defaultAgentDirectory() {
        String configured = System.getenv(AGENT_DIRECTORY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        try {
            Path location = Path.of(Configuration.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
            return moduleRootOf(location);
        } catch (URISyntaxException | RuntimeException exception) {
            return Path.of(CURRENT_DIRECTORY);
        }
    }

    /**
     * The tool's own directory, given where the running code was loaded from.
     *
     * <p>Both shapes Maven produces lead to the module root: a jar inside
     * {@code target/}, and an exploded {@code target/classes/}. A jar anywhere
     * else has been installed, and its own folder is its home: that is where
     * the installer puts the catalog beside it, and where its logs belong.
     * Treating an installed jar like a built one sent both two levels up, to
     * a directory that was never this tool's.
     *
     * @param location the jar or classes directory
     * @return the directory the tool's catalog and logs live in
     */
    public static Path moduleRootOf(Path location) {
        Path directory = location.toString().endsWith(JAR_SUFFIX) ? location.getParent() : location;
        if (directory == null) {
            return Path.of(CURRENT_DIRECTORY);
        }
        if (isBuildDirectory(directory)) {
            return directory.getParent();
        }
        if (isBuildDirectory(directory.getParent())) {
            return directory.getParent().getParent();
        }
        return directory;
    }

    /**
     * Whether a directory is Maven's build output.
     *
     * @param directory the directory, possibly {@code null}
     * @return {@code true} when it is named {@code target}
     */
    private static boolean isBuildDirectory(Path directory) {
        return directory != null && directory.getFileName() != null
                && BUILD_DIRECTORY.equals(directory.getFileName().toString());
    }

    /**
     * Where this tool's own files live.
     *
     * @return the agent's directory
     */
    public Path agentDirectory() {
        return agentDirectory;
    }

    /**
     * The repository being analyzed.
     *
     * @return its root, the agent's parent directory unless overridden
     */
    public Path targetRepository() {
        String configured = environment.get(TARGET_REPOSITORY);
        Path root = configured == null ? agentDirectory.getParent() : Path.of(configured);
        return root.toAbsolutePath().normalize();
    }

    /**
     * The project the Claude Code session is open on, which is where its
     * hooks look for what changed.
     *
     * <p>Not always the directory Sheriff mounts: in a repository that is a
     * single module, the mount is the repository's parent.
     *
     * @return {@code CLAUDE_PROJECT_DIR} when Claude Code set it, otherwise
     *     the target repository
     */
    public Path projectDirectory() {
        String configured = environment.get(PROJECT_DIRECTORY);
        return configured == null || configured.isBlank()
                ? targetRepository()
                : Path.of(configured).toAbsolutePath().normalize();
    }

    /**
     * What to do about Sheriff's image when it is missing or out of date.
     *
     * @return the {@code SHERIFF_PULL} policy
     */
    public String pullPolicy() {
        return ImageProvisioning.policyIn(environment);
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
     * The rule profiles to apply: the one configured, with the base profile
     * of its language added when it is an architecture profile, which does
     * not include the base rules ({@link Rules#withBaseProfiles}).
     *
     * @return the {@code --test} profiles, comma-separated
     */
    public String testType() {
        return Rules.withBaseProfiles(value(TEST_TYPE, DEFAULT_TEST_TYPE));
    }

    /**
     * The folder to analyze.
     *
     * @return the component, relative to the repository root
     */
    public String component() {
        return value(COMPONENT, DEFAULT_COMPONENT);
    }

    /**
     * How long Sheriff is given.
     *
     * @return that deadline
     */
    public Duration sheriffTimeout() {
        return Duration.ofSeconds(positive(SHERIFF_TIMEOUT, DEFAULT_SHERIFF_TIMEOUT));
    }

    /**
     * Where the rule catalog lives. Anchored to the agent, never to the target
     * repository: the catalog describes the Sheriff image, not the project.
     *
     * @return the catalog file, which may not exist
     */
    public Path rulesCatalog() {
        String configured = environment.get(RULES_CATALOG);
        return configured == null ? agentDirectory.resolve(CATALOG_FILE) : Path.of(configured);
    }

    /**
     * Whether the catalog was named by hand, in which case it is read as it
     * is and never replaced.
     *
     * @return {@code true} when {@code RULES_CATALOG} is set
     */
    public boolean catalogConfigured() {
        return environment.get(RULES_CATALOG) != null;
    }

    /**
     * Where the catalog extracted for the image in use is kept, shared with
     * the MCP server: {@code $XDG_CACHE_HOME/sheriff-mcp/}, or the same under
     * the user's {@code .cache}.
     *
     * <p>Read from this environment only, never from the JVM's: an
     * environment that does not say where home is has no cache, rather than
     * one somewhere it was not asked to write.
     *
     * @return that file, or empty when the environment names no home
     */
    public Optional<Path> cachedCatalog() {
        String cacheHome = environment.get(CACHE_HOME);
        if (cacheHome != null && !cacheHome.isBlank()) {
            return Optional.of(CatalogProvisioning.cacheIn(Path.of(cacheHome)));
        }
        return HOME_VARIABLES.stream().map(environment::get).filter(home -> home != null && !home.isBlank())
                .findFirst().map(home -> CatalogProvisioning.cacheIn(Path.of(home, DEFAULT_CACHE_DIRECTORY)));
    }

    /**
     * The user's home, from this environment only, as for the catalog's
     * cache: where Antigravity keeps its settings.
     *
     * @return that directory, or empty when the environment names none
     */
    public Optional<Path> userHome() {
        return HOME_VARIABLES.stream().map(environment::get).filter(home -> home != null && !home.isBlank())
                .findFirst().map(Path::of);
    }

    /**
     * Where the build tools keep what they have downloaded, for a process
     * run with another {@code HOME}: each variable the environment does not
     * already set, pointing where the tool would look under the real home.
     * Maven itself and Gradle find their caches through Java's
     * {@code user.home}, which does not follow {@code HOME}; the Maven
     * wrapper's own copy of Maven and npm's cache and configuration do.
     *
     * @return those variables, empty when the environment names no home
     */
    public Map<String, String> buildToolHomes() {
        Optional<Path> home = userHome();
        if (home.isEmpty()) {
            return Map.of();
        }
        Map<String, String> homes = new TreeMap<>();
        BUILD_TOOL_HOMES.forEach((variable, directory) -> {
            if (value(variable, NOT_CONFIGURED).isEmpty()) {
                homes.put(variable, home.get().resolve(directory).toString());
            }
        });
        return Map.copyOf(homes);
    }

    /**
     * How many of a profile's rules to put in one prompt.
     *
     * @return that cap, or {@code null} to use the domain's default
     */
    public Integer maxRulesInPrompt() {
        return optionalNumber(MAX_RULES);
    }

    /**
     * The knobs of the fix loop.
     *
     * @return those settings
     */
    public LoopSettings loopSettings() {
        return new LoopSettings(
                optionalPositive(MAX_ITERATIONS),
                !environment.getOrDefault(GIT_SAFETY, ENABLED).equals(DISABLED),
                number(MAX_FILES_PER_BATCH, DEFAULT_BATCH));
    }

    /**
     * Which fixer backend to build.
     *
     * @return the backend name
     */
    public String backend() {
        return value(BACKEND, DEFAULT_BACKEND);
    }

    /**
     * How long one Claude Code invocation is given.
     *
     * @return that deadline
     */
    public Duration claudeTimeout() {
        return Duration.ofSeconds(positive(CLAUDE_TIMEOUT, DEFAULT_CLAUDE_TIMEOUT));
    }

    /**
     * The API key for the billed backend.
     *
     * @return the key, empty when none is set
     */
    public String apiKey() {
        return value(API_KEY, NO_API_KEY);
    }

    /**
     * Where the billed backend sends its requests.
     *
     * @return the configured base URL, or the empty string for Anthropic's own
     *     endpoint
     */
    public String apiBaseUrl() {
        return value(API_BASE_URL, NOT_CONFIGURED);
    }

    /**
     * The model the billed backend asks.
     *
     * @return its identifier
     */
    public String apiModel() {
        return value(API_MODEL, DEFAULT_MODEL);
    }

    /**
     * The key for an OpenAI-compatible endpoint.
     *
     * @return that key, or empty when none is set -- which is normal for a
     *     model served locally, since those ignore it
     */
    public String openAiKey() {
        return value(OPENAI_KEY, NOT_CONFIGURED);
    }

    /**
     * The base URL of the OpenAI-compatible endpoint to call.
     *
     * @return that URL, or empty to take the backend's own default
     */
    public String openAiBaseUrl() {
        return value(OPENAI_BASE_URL, NOT_CONFIGURED);
    }

    /**
     * The model to ask for on that endpoint.
     *
     * @return that model, or empty to take the backend's own default
     */
    public String openAiModel() {
        return value(OPENAI_MODEL, NOT_CONFIGURED);
    }

    /**
     * The ceiling on one OpenAI-compatible answer.
     *
     * @return that number of tokens
     */
    public long openAiMaxTokens() {
        return positive(OPENAI_MAX_TOKENS, (int) DEFAULT_MAX_TOKENS);
    }

    /**
     * How many tool round trips one OpenAI-compatible fix may take.
     *
     * @return that number of iterations
     */
    public int openAiMaxToolIterations() {
        return positive(OPENAI_TOOL_ITERATIONS, DEFAULT_TOOL_ITERATIONS);
    }

    /**
     * The command that runs Codex non-interactively, prompt excluded.
     *
     * <p>Configurable as a whole because Codex's own flags have moved between
     * releases, and a wrong guess here should be fixable with a variable
     * rather than with a rebuild.
     *
     * @return that command, split on spaces
     */
    public List<String> codexCommand() {
        return List.of(value(CODEX_COMMAND, DEFAULT_CODEX_COMMAND).trim().split(WHITESPACE));
    }

    /**
     * How long one Codex invocation is given.
     *
     * @return that duration
     */
    public Duration codexTimeout() {
        return Duration.ofSeconds(positive(CODEX_TIMEOUT, DEFAULT_CLAUDE_TIMEOUT));
    }

    /**
     * The command that runs Antigravity's CLI without its interface, prompt
     * excluded.
     *
     * <p>Configurable as a whole, as Codex's is, and for the model too:
     * {@code agy --mode accept-edits --model claude-sonnet-5-5-high}.
     *
     * @return that command, split on spaces
     */
    public List<String> antigravityCommand() {
        return List.of(value(ANTIGRAVITY_COMMAND, DEFAULT_ANTIGRAVITY_COMMAND).trim().split(WHITESPACE));
    }

    /**
     * How long one Antigravity invocation is given.
     *
     * @return that duration
     */
    public Duration antigravityTimeout() {
        return Duration.ofSeconds(positive(ANTIGRAVITY_TIMEOUT, DEFAULT_CLAUDE_TIMEOUT));
    }

    /**
     * The cap on one API response.
     *
     * @return that many tokens
     */
    public long apiMaxTokens() {
        return positive(API_MAX_TOKENS, (int) DEFAULT_API_MAX_TOKENS);
    }

    /**
     * How many tool-calling turns the billed backend may take.
     *
     * @return that cap
     */
    public int apiMaxToolIterations() {
        return positive(API_MAX_TOOL_ITERATIONS, DEFAULT_TOOL_ITERATIONS);
    }

    /**
     * The target project's own test command.
     *
     * <p>The default follows the component rather than naming a project, so
     * pointing the agent elsewhere moves the gate with it. A fixed default
     * would verify the wrong project silently, which is a real bug this
     * project has already had once.
     *
     * @return the shell command, empty to disable the gate
     */
    public String verificationCommand() {
        return value(VERIFICATION_COMMAND, String.format(DEFAULT_VERIFICATION, component()));
    }

    /**
     * How long the verification command is given.
     *
     * @return that deadline
     */
    public Duration verificationTimeout() {
        return Duration.ofSeconds(positive(VERIFICATION_TIMEOUT, DEFAULT_VERIFICATION_TIMEOUT));
    }

    /**
     * Where prompts and responses are recorded.
     *
     * @return that directory, anchored to the agent
     */
    public Path promptLogDirectory() {
        String configured = environment.get(PROMPT_LOG);
        return configured == null ? agentDirectory.resolve(LOGS_DIRECTORY) : Path.of(configured);
    }

    /**
     * Where each analysis copies Sheriff's findings and tracked files, when
     * anywhere.
     *
     * @return that directory, or empty when {@code SHERIFF_EXPORT_DIR} is unset
     */
    public Optional<Path> exportDirectory() {
        String configured = environment.get(EXPORT_DIRECTORY);
        return configured == null || configured.isBlank() ? Optional.empty() : Optional.of(Path.of(configured));
    }

    /**
     * Whether the hooks ask Sheriff to stop at the first error. Off unless
     * asked for: the model then sees one finding per edit rather than all.
     *
     * @return {@code true} when {@code SHERIFF_FAIL_FAST=1}
     */
    public boolean failFast() {
        return ENABLED.equals(environment.get(FAIL_FAST));
    }

    /**
     * Whether this process was started from inside a run of the fix loop.
     *
     * <p>The CLI fixers set {@code SHERIFF_AGENT_RUNNING=1} on the assistant
     * they launch, and everything that assistant runs, hooks included,
     * inherits it.
     *
     * @return {@code true} when the marker is set
     */
    public boolean insideAgentRun() {
        return ENABLED.equals(environment.get(AGENT_RUNNING));
    }

    /**
     * Whether the Stop hook also requires the touched components' tests to
     * pass. Off unless asked for: it runs a build at the end of every turn.
     *
     * @return {@code true} when {@code SHERIFF_STOP_RUNS_TESTS=1}
     */
    public boolean stopRunsTests() {
        return ENABLED.equals(environment.get(STOP_RUNS_TESTS));
    }

    /**
     * How many times in a row the Stop hook may send a session back to work
     * before it lets the turn end anyway.
     *
     * @return that number, at least one
     */
    public int stopMaxBlocks() {
        return positive(STOP_MAX_BLOCKS, DEFAULT_STOP_MAX_BLOCKS);
    }

    /**
     * The command that runs one component's tests, from the target
     * repository.
     *
     * @param component the component
     * @return {@code VERIFICATION_TEST_CMD} when set, else Maven's in that
     *     component
     */
    public String verificationCommandFor(String component) {
        return value(VERIFICATION_COMMAND, String.format(DEFAULT_VERIFICATION, component));
    }

    /**
     * Whether to analyze with the mock instead of Docker.
     *
     * @return {@code true} when mock mode is on
     */
    public boolean mockMode() {
        return ENABLED.equals(environment.get(MOCK));
    }

    /**
     * The marker the mock analyzer counts as an error.
     *
     * @return that marker
     */
    public String mockMarker() {
        return value(MOCK_MARKER, DEFAULT_MARKER);
    }

    /**
     * One variable's value, treating an empty one as unset so that exporting a
     * variable to nothing does not override the default with a blank.
     *
     * @param name the variable to read
     * @param fallback what to use when it is unset or empty
     * @return the configured value, or the fallback
     */
    private String value(String name, String fallback) {
        String configured = environment.get(name);
        return configured == null || configured.isEmpty() ? fallback : configured;
    }

    /**
     * One variable's value as a whole number of at least one: a timeout,
     * a token limit or a cap. Zero or a negative used to be taken, and then
     * every run failed, a timeout of 0 s most visibly.
     *
     * @param name the variable
     * @param fallback its value when it is not set
     * @return the value
     * @throws IllegalArgumentException when it is set to something else
     */
    private int positive(String name, int fallback) {
        Integer configured = optionalPositive(name);
        return configured == null ? fallback : configured;
    }

    /**
     * One variable's value as a whole number of at least one, when it is set.
     *
     * @param name the variable
     * @return the value, or {@code null} when it is not set
     * @throws IllegalArgumentException when it is set to something else
     */
    private Integer optionalPositive(String name) {
        Integer configured = optionalNumber(name);
        if (configured != null && configured < MINIMUM_POSITIVE) {
            throw new IllegalArgumentException(String.format(NOT_POSITIVE, name, configured));
        }
        return configured;
    }

    /**
     * The same, for a variable that carries a number.
     *
     * @param name the variable to read
     * @param fallback what to use when it is unset or empty
     * @return the configured number, or the fallback
     */
    private int number(String name, int fallback) {
        Integer configured = optionalNumber(name);
        return configured == null ? fallback : configured;
    }

    /**
     * One variable's value as a whole number, when it is set at all.
     *
     * <p>A value that is not a number is refused with the variable's name in
     * the message: Java's own "For input string: abc" says what was wrong but
     * not where it came from, and the agent reads some thirty variables.
     *
     * @param name the variable to read
     * @return the number, or {@code null} when the variable is unset or empty
     * @throws IllegalArgumentException when it is set to something else
     */
    private Integer optionalNumber(String name) {
        String configured = environment.get(name);
        if (configured == null || configured.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(configured.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(String.format(NOT_A_NUMBER, name, configured), exception);
        }
    }
}
