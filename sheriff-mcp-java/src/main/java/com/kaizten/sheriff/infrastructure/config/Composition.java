package com.kaizten.sheriff.infrastructure.config;

import com.kaizten.sheriff.application.service.FixLoop;
import com.kaizten.sheriff.application.usecase.FixCodeUntilClean;
import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.port.AutomaticRepair;
import com.kaizten.sheriff.domain.port.CodeAnalyzer;
import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.port.FixerScriptSource;
import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.port.TestRunner;
import com.kaizten.sheriff.domain.port.VersionControl;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.antigravity.AntigravityCliFixer;
import com.kaizten.sheriff.infrastructure.antigravity.AntigravityHome;
import com.kaizten.sheriff.infrastructure.api.AnthropicApiFixer;
import com.kaizten.sheriff.infrastructure.api.OpenAiApiFixer;
import com.kaizten.sheriff.infrastructure.catalog.CatalogFreshness;
import com.kaizten.sheriff.infrastructure.catalog.CatalogProvisioning;
import com.kaizten.sheriff.infrastructure.catalog.JsonRuleCatalog;
import com.kaizten.sheriff.infrastructure.catalog.MarkdownRuleCatalog;
import com.kaizten.sheriff.infrastructure.claude.ClaudeCliFixer;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import com.kaizten.sheriff.infrastructure.codex.CodexCliFixer;
import com.kaizten.sheriff.infrastructure.docker.DeterministicFixer;
import com.kaizten.sheriff.infrastructure.docker.DockerFixerScriptSource;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer;
import com.kaizten.sheriff.infrastructure.docker.SheriffImage;
import com.kaizten.sheriff.infrastructure.extractor.RuleCatalogExtractor;
import com.kaizten.sheriff.infrastructure.git.GitVersionControl;
import com.kaizten.sheriff.infrastructure.git.UntestedMethods;
import com.kaizten.sheriff.infrastructure.hook.ComponentGate;
import com.kaizten.sheriff.infrastructure.hook.SheriffStopHook;
import com.kaizten.sheriff.infrastructure.hook.StopBlocks;
import com.kaizten.sheriff.infrastructure.hook.TurnStart;
import com.kaizten.sheriff.infrastructure.mock.MockSheriffAnalyzer;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import com.kaizten.sheriff.infrastructure.shell.ShellTestRunner;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The composition root: the one place that decides which implementation
 * satisfies each port.
 *
 * <p>Everything else in this codebase depends on interfaces, which is only
 * worth anything if the wiring lives somewhere single and obvious. Adding a
 * fixer backend means writing one adapter and one line here; nothing in the
 * domain or the application layer changes, and no test of either has to.
 */
public final class Composition {

    private static final String CLAUDE_CLI_BACKEND = "claude_cli";
    private static final String ANTHROPIC_API_BACKEND = "anthropic_api";
    private static final String CODEX_CLI_BACKEND = "codex_cli";
    private static final String ANTIGRAVITY_CLI_BACKEND = "antigravity_cli";
    private static final String OPENAI_API_BACKEND = "openai_api";
    private static final String LOCAL_BACKEND = "local";
    private static final String UNKNOWN_BACKEND = "Unknown AI_BACKEND '%s' -- expected one of: %s";
    private static final String BACKEND_SEPARATOR = ", ";
    private static final List<String> BACKENDS = List.of(CLAUDE_CLI_BACKEND, ANTHROPIC_API_BACKEND, CODEX_CLI_BACKEND,
            ANTIGRAVITY_CLI_BACKEND, OPENAI_API_BACKEND, LOCAL_BACKEND);
    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";
    private static final String OPENAI_MODEL = "gpt-4o-mini";
    private static final String LOCAL_BASE_URL = "http://localhost:11434/v1";
    private static final String LOCAL_MODEL = "qwen2.5-coder";
    private static final String MARKDOWN_SUFFIX = ".md";
    private static final String MARKDOWN_LONG_SUFFIX = ".markdown";
    private static final String BRANCH_STAMP_PATTERN = "yyyyMMdd-HHmmss";
    private static final DateTimeFormatter BRANCH_STAMP = DateTimeFormatter.ofPattern(BRANCH_STAMP_PATTERN);
    private static final String NOTHING_REPAIRED = "";
    private static final String NO_BRANCH_STAMP = "";
    private static final AutomaticRepair NO_AUTOMATIC_REPAIR = () -> NOTHING_REPAIRED;

    private final Configuration configuration;
    private final ProcessRunner processes;
    private final UnaryOperator<String> profiles;
    private final UnaryOperator<String> verifications;
    private final UnaryOperator<String> modelTests;
    private Path provisionedCatalog;

    /**
     * Wires a composition to a configuration, analyzing every component
     * under the configured profile.
     *
     * @param configuration what to build from
     * @param processes how external commands are run
     */
    public Composition(Configuration configuration, ProcessRunner processes) {
        this(configuration, processes, component -> configuration.testType());
    }

    /**
     * Wires a composition that chooses the profile component by component.
     *
     * @param configuration what to build from
     * @param processes how external commands are run
     * @param profiles the profile for each component the hooks guard; the
     *     MCP server's jar passes the language its sources are in, so the
     *     hooks guard a TypeScript component as TypeScript
     */
    public Composition(Configuration configuration, ProcessRunner processes, UnaryOperator<String> profiles) {
        this(configuration, processes, profiles, configuration::verificationCommandFor);
    }

    /**
     * Wires a composition that chooses the profile and the test command
     * component by component.
     *
     * @param configuration what to build from
     * @param processes how external commands are run
     * @param profiles the profile for each component the hooks guard
     * @param verifications the command that runs each component's tests,
     *     from the target repository
     */
    public Composition(Configuration configuration, ProcessRunner processes, UnaryOperator<String> profiles,
            UnaryOperator<String> verifications) {
        this(configuration, processes, profiles, verifications, verifications);
    }

    /**
     * Wires a composition that also knows the test command a model is told
     * to run, which runs from wherever its shell is.
     *
     * @param configuration what to build from
     * @param processes how external commands are run
     * @param profiles the profile for each component the hooks guard
     * @param verifications the command that runs each component's tests,
     *     from the target repository
     * @param modelTests the command a model runs for each component's tests
     */
    public Composition(Configuration configuration, ProcessRunner processes, UnaryOperator<String> profiles,
            UnaryOperator<String> verifications, UnaryOperator<String> modelTests) {
        this.configuration = configuration;
        this.processes = processes;
        this.profiles = profiles;
        this.verifications = verifications;
        this.modelTests = modelTests;
    }

    /**
     * A composition for real use, running real processes.
     *
     * @param configuration what to build from
     * @return that composition
     */
    public static Composition real(Configuration configuration) {
        return new Composition(configuration, new SystemProcessRunner());
    }

    /**
     * A composition for real use that chooses the profile per component.
     *
     * @param configuration what to build from
     * @param profiles the profile for each component
     * @return that composition
     */
    public static Composition real(Configuration configuration, UnaryOperator<String> profiles) {
        return new Composition(configuration, new SystemProcessRunner(), profiles);
    }

    /**
     * A composition for real use that chooses the profile and the test
     * command per component.
     *
     * @param configuration what to build from
     * @param profiles the profile for each component
     * @param verifications the test command for each component
     * @return that composition
     */
    public static Composition real(
            Configuration configuration, UnaryOperator<String> profiles, UnaryOperator<String> verifications) {
        return new Composition(configuration, new SystemProcessRunner(), profiles, verifications);
    }

    /**
     * A composition for real use that chooses the profile, the test command
     * and the one a model is told to run, per component.
     *
     * @param configuration what to build from
     * @param profiles the profile for each component
     * @param verifications the test command for each component
     * @param modelTests the command a model runs for each component's tests
     * @return that composition
     */
    public static Composition real(Configuration configuration, UnaryOperator<String> profiles,
            UnaryOperator<String> verifications, UnaryOperator<String> modelTests) {
        return new Composition(configuration, new SystemProcessRunner(), profiles, verifications, modelTests);
    }

    /**
     * Where the text of a fixer's script comes from.
     *
     * @return that source, reading from the configured image
     */
    public FixerScriptSource fixerScripts() {
        return new DockerFixerScriptSource(processes, configuration.image(), configuration.sheriffTimeout());
    }

    /**
     * The analyzer: Sheriff in Docker, or the mock when Docker is not wanted.
     *
     * @return that analyzer
     */
    public CodeAnalyzer analyzer() {
        if (configuration.mockMode()) {
            return new MockSheriffAnalyzer(
                    configuration.targetRepository(),
                    configuration.targetRepository().resolve(configuration.component()),
                    configuration.mockMarker());
        }
        return exporting(new SheriffDockerAnalyzer(
                processes,
                configuration.image(),
                configuration.testType(),
                configuration.component(),
                configuration.targetRepository(),
                configuration.sheriffTimeout()));
    }

    /**
     * Any analyzer, copying Sheriff's findings and tracked files out after
     * each run when {@code SHERIFF_EXPORT_DIR} says where.
     *
     * <p>Every analyzer built here goes through this, because the variable
     * promises "after every analysis" and an exception nobody remembers is
     * worse than the copy being overwritten by the next run.
     *
     * @param analyzer the analyzer to extend
     * @return the same analyzer, exporting or not
     */
    private SheriffDockerAnalyzer exporting(SheriffDockerAnalyzer analyzer) {
        return configuration.exportDirectory().map(analyzer::exportingTo).orElse(analyzer);
    }

    /**
     * An analyzer for a component other than the configured one, which is what
     * the hooks need: they guard whatever component the edited file belongs
     * to, not whichever one configuration happens to name.
     *
     * @param component the component to analyze
     * @return that analyzer
     */
    public CodeAnalyzer analyzerFor(String component) {
        if (configuration.mockMode()) {
            return analyzer();
        }
        SheriffDockerAnalyzer analyzer = exporting(new SheriffDockerAnalyzer(
                processes,
                configuration.image(),
                profiles.apply(component),
                component,
                configuration.targetRepository(),
                configuration.sheriffTimeout()));
        return configuration.failFast() ? analyzer.failingFast() : analyzer;
    }

    /**
     * The fixer, chosen by configured backend.
     *
     * @return that fixer
     * @throws IllegalArgumentException when the configured backend is unknown,
     *     which is worth one clear line rather than a stack trace
     */
    public CodeFixer fixer() {
        PromptLog log = new PromptLog(configuration.promptLogDirectory());
        String backend = configuration.backend();
        if (CLAUDE_CLI_BACKEND.equals(backend)) {
            return new ClaudeCliFixer(processes, log, configuration.targetRepository(), configuration.component(),
                    configuration.verificationCommand(), configuration.claudeTimeout());
        }
        if (ANTHROPIC_API_BACKEND.equals(backend)) {
            return new AnthropicApiFixer(
                    configuration.apiKey(),
                    configuration.apiBaseUrl(),
                    configuration.apiModel(),
                    configuration.apiMaxTokens(),
                    configuration.apiMaxToolIterations(),
                    configuration.targetRepository(),
                    componentDirectory(),
                    log);
        }
        if (CODEX_CLI_BACKEND.equals(backend)) {
            return new CodexCliFixer(processes, log, configuration.targetRepository(), configuration.component(),
                    configuration.codexCommand(), configuration.codexTimeout());
        }
        if (ANTIGRAVITY_CLI_BACKEND.equals(backend)) {
            return new AntigravityCliFixer(processes, log, configuration.targetRepository(), configuration.component(),
                    configuration.antigravityCommand(), configuration.antigravityTimeout(), antigravityHome());
        }
        if (OPENAI_API_BACKEND.equals(backend)) {
            return openAiFixer(log, OPENAI_BASE_URL, OPENAI_MODEL, true);
        }
        if (LOCAL_BACKEND.equals(backend)) {
            return openAiFixer(log, LOCAL_BASE_URL, LOCAL_MODEL, false);
        }
        throw new IllegalArgumentException(
                String.format(UNKNOWN_BACKEND, backend, String.join(BACKEND_SEPARATOR, BACKENDS)));
    }

    /**
     * The home Antigravity's passes run with: one of their own, allowing the
     * project's tests and {@code git mv}, except on Windows, where it has not
     * been tried, and in an environment that names no home.
     *
     * @return that home
     */
    private AntigravityHome antigravityHome() {
        Optional<Path> userHome = configuration.userHome();
        if (Platform.windows() || userHome.isEmpty()) {
            return AntigravityHome.none();
        }
        return new AntigravityHome(userHome.get(), configuration.buildToolHomes(),
                ClaudeCliFixer.testCommandCore(configuration.verificationCommand()));
    }

    /**
     * The fixer for an endpoint speaking OpenAI's chat completions API.
     *
     * <p>Two backends share it: OpenAI's own service and a model served on
     * this machine. What separates them is where to send the request, which
     * model to ask for, and whether a missing key is a reason to stop -- and
     * all three stay overridable, so pointing {@code local} at LM Studio or
     * vLLM instead of Ollama is one variable rather than another adapter.
     *
     * @param log where to record the exchange
     * @param defaultBaseUrl the endpoint to use when none is configured
     * @param defaultModel the model to ask for when none is configured
     * @param keyRequired whether a missing key stops the run
     * @return that fixer
     */
    private CodeFixer openAiFixer(PromptLog log, String defaultBaseUrl, String defaultModel, boolean keyRequired) {
        String baseUrl = configuration.openAiBaseUrl();
        String model = configuration.openAiModel();
        return new OpenAiApiFixer(
                configuration.openAiKey(),
                baseUrl.isEmpty() ? defaultBaseUrl : baseUrl,
                model.isEmpty() ? defaultModel : model,
                configuration.openAiMaxTokens(),
                configuration.openAiMaxToolIterations(),
                keyRequired,
                configuration.targetRepository(),
                componentDirectory(),
                log);
    }

    /**
     * The folder the API backends' file tools are kept to: the component,
     * as the command-line backends are, and never the mount, which for a
     * project of one module is the folder of every other project beside it.
     *
     * @return that folder
     */
    private Path componentDirectory() {
        return configuration.targetRepository().resolve(configuration.component());
    }

    /**
     * The git safety net.
     *
     * <p>Git runs from inside the component, which is inside the repository
     * even when the directory Sheriff mounts is not: a repository that is
     * itself one module is mounted through its parent.
     *
     * @return that adapter
     */
    public VersionControl versionControl() {
        Path mount = configuration.targetRepository();
        Path component = mount.resolve(configuration.component());
        Path workingDirectory = Files.isDirectory(component) ? component : mount;
        return new GitVersionControl(processes, mount, workingDirectory, LocalDateTime.now().format(BRANCH_STAMP));
    }

    /**
     * The verification gate.
     *
     * @return that adapter
     */
    public TestRunner testRunner() {
        return new ShellTestRunner(
                processes,
                configuration.verificationCommand(),
                configuration.targetRepository(),
                configuration.verificationTimeout());
    }

    /**
     * A test runner for one command in one directory, which is what the
     * three-way check needs: its first step runs this module's own suite, not
     * the target project's.
     *
     * @param command the shell command to run
     * @param workingDirectory where to run it
     * @return that runner
     */
    public TestRunner commandRunner(String command, Path workingDirectory) {
        return new ShellTestRunner(processes, command, workingDirectory, configuration.verificationTimeout());
    }

    /**
     * The rule catalog, read from whichever format it is in.
     *
     * @return that catalog, empty when there is no readable file
     */
    public RuleCatalog ruleCatalog() {
        Path catalog = catalogPath();
        String path = catalog.toString().toLowerCase(Locale.ROOT);
        if (path.endsWith(MARKDOWN_SUFFIX) || path.endsWith(MARKDOWN_LONG_SUFFIX)) {
            return new MarkdownRuleCatalog(catalog);
        }
        return new JsonRuleCatalog(catalog);
    }

    /**
     * The catalog this run reads: the one provisioned for the image in use,
     * once {@link #provisionCatalog()} has run, or the configured one.
     *
     * @return that file, which may not exist
     */
    public Path catalogPath() {
        return provisionedCatalog == null ? configuration.rulesCatalog() : provisionedCatalog;
    }

    /**
     * Makes the catalog this run reads match Sheriff's image, extracting a
     * new one into the shared cache when the one there is missing or came
     * from another image.
     *
     * <p>A catalog named by hand is never replaced, only reported when it is
     * out of date. Nor is anything extracted in mock mode, or when the
     * environment names no home to keep a cache in.
     *
     * @return a warning to print, empty when there is nothing to say
     */
    public Optional<String> provisionCatalog() {
        Optional<Path> cache = configuration.cachedCatalog();
        if (configuration.catalogConfigured() || configuration.mockMode() || cache.isEmpty()) {
            return CatalogFreshness.check(
                    processes, configuration.agentDirectory(), configuration.rulesCatalog(), configuration.image());
        }
        provisionedCatalog = CatalogProvisioning.choose(null, List.of(configuration.rulesCatalog()), cache.get(),
                CatalogProvisioning.matching(processes, configuration.image()),
                CatalogProvisioning.extraction(processes, configuration.image()));
        return Optional.empty();
    }

    /**
     * The rules text every fixer prompt carries.
     *
     * <p>Rendered here rather than inside the domain, so the domain never has
     * to know where configuration comes from.
     *
     * @return that text
     */
    public String rulesContext() {
        Integer cap = configuration.maxRulesInPrompt();
        return Rules.sheriffRulesContext(
                configuration.testType(),
                ruleCatalog().allRules(),
                cap == null ? Rules.DEFAULT_MAX_RULES_IN_PROMPT : cap);
    }

    /**
     * The rule-catalog extractor, which reads the Sheriff image itself.
     *
     * @return that extractor
     */
    public RuleCatalogExtractor ruleCatalogExtractor() {
        return new RuleCatalogExtractor(processes, configuration.agentDirectory());
    }

    /**
     * Sheriff's image, as this configuration names it.
     *
     * @return the image, to ask whether it is here or out of date
     */
    public SheriffImage sheriffImage() {
        return new SheriffImage(processes, configuration.image());
    }

    /**
     * What makes sure of Sheriff's image before an entry point uses it.
     *
     * @param log where progress is written
     * @return that provisioning, not yet started
     */
    public ImageProvisioning imageProvisioning(PrintStream log) {
        return new ImageProvisioning(sheriffImage(), configuration.pullPolicy(), log);
    }

    /**
     * Sheriff's own fixers, as a pass to run before paying for an AI one.
     *
     * <p>The analyzer it gets is deliberately one that keeps Sheriff's state:
     * the fix subcommand reads the findings file an analysis writes, and
     * repairs nothing without it.
     *
     * @return that pass
     */
    public DeterministicFixer deterministicFixer() {
        SheriffDockerAnalyzer stateKeeping = exporting(new SheriffDockerAnalyzer(
                processes, configuration.image(), configuration.testType(), configuration.component(),
                configuration.targetRepository(), configuration.sheriffTimeout(), false));
        return new DeterministicFixer(processes, ruleCatalog(), stateKeeping, configuration.image(),
                configuration.component(), configuration.targetRepository(), configuration.sheriffTimeout());
    }

    /**
     * The shared component gate both hooks use.
     *
     * @return that gate
     */
    public ComponentGate componentGate() {
        return new ComponentGate(configuration, this);
    }

    /**
     * The end-of-turn hook.
     *
     * @return that hook
     */
    public SheriffStopHook stopHook() {
        GitVersionControl git = new GitVersionControl(
                processes, configuration.targetRepository(), configuration.projectDirectory(), NO_BRANCH_STAMP);
        Function<String, VerificationResult> tests = configuration.stopRunsTests()
                ? component -> commandRunner(verifications.apply(component), configuration.targetRepository()).run()
                : null;
        return new SheriffStopHook(componentGate(), git, tests, new StopBlocks(), new TurnStart(),
                configuration.stopMaxBlocks()).withTestCommands(modelTests)
                .withUntestedMethods(new UntestedMethods(processes));
    }

    /**
     * The use case itself, fully wired.
     *
     * @return the fix loop, ready to run
     */
    public FixCodeUntilClean fixLoop() {
        return fixLoop(configuration.loopSettings());
    }

    /**
     * The use case, with settings the command line asked for instead of the
     * configured ones.
     *
     * @param settings the iteration cap, git safety and batch size to use
     * @return the fix loop, ready to run
     */
    public FixCodeUntilClean fixLoop(LoopSettings settings) {
        return fixLoop(settings, true);
    }

    /**
     * The use case, choosing whether Sheriff's own fixers go first.
     *
     * <p>When they do, they run inside the loop's branch, after it exists and
     * before the first iteration. The mock analyzer has no fixers to offer, so
     * mock mode never runs them.
     *
     * @param settings the iteration cap, git safety and batch size to use
     * @param automaticRepair whether Sheriff's own fixers run first
     * @return the fix loop, ready to run
     */
    public FixCodeUntilClean fixLoop(LoopSettings settings, boolean automaticRepair) {
        AutomaticRepair repair = automaticRepair && !configuration.mockMode()
                ? deterministicFixer()
                : NO_AUTOMATIC_REPAIR;
        return new FixLoop(analyzer(), fixer(), versionControl(), testRunner(), repair, rulesContext(),
                ruleCatalog().allRules(), settings);
    }
}
