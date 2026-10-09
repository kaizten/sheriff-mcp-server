package com.kaizten.sheriff.infrastructure.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.domain.valueobject.RuleSelection;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.docker.DeterministicFixReport;
import com.kaizten.sheriff.infrastructure.docker.DockerFixerScriptSource;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.hook.HookExit;
import com.kaizten.sheriff.infrastructure.hook.SheriffGateHook;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The command line: parses arguments, builds the composition, runs one mode.
 *
 * <p>Deliberately thin. Every decision it appears to make belongs to something
 * else — the loop decides when to stop, the composition decides what
 * implements what, the domain decides which rules apply — so that swapping any
 * of those never means touching an argument parser.
 *
 * <p>Exit codes are part of the contract, because this is meant to sit in a
 * pipeline: 0 when whatever was asked came out clean, 1 when it did not, and 2
 * when the request itself made no sense.
 */
public final class Agent {

    /**
     * Everything asked for came out clean.
     */
    public static final int SUCCESS = 0;

    /**
     * The tool ran and the answer is no.
     */
    public static final int FAILURE = 1;

    /**
     * The invocation itself was wrong, which is not the same as a failure.
     */
    public static final int MISUSE = 2;

    private static final String CHECK_ONLY = "--check-only";
    private static final String RULES = "--rules";
    private static final String FIXER = "--fixer";
    private static final String JSON_REPORT = "--json-report";
    private static final String MAX_ITERATIONS = "--max-iterations";
    private static final String MAX_FILES = "--max-files-per-batch";
    private static final String NO_GIT_SAFETY = "--no-git-safety";
    private static final String EXTRACT_RULES = "--extract-rules";
    private static final String FULL_CHECK = "--full-check";
    private static final String SHERIFF_FIX = "--sheriff-fix";
    private static final String NO_SHERIFF_FIX = "--no-sheriff-fix";
    private static final String HOOK_GATE = "--hook-gate";
    private static final String HOOK_STOP = "--hook-stop";
    private static final String HOOK_TURN = "--hook-turn";
    private static final String ERROR_PREFIX = "error: ";
    private static final String NO_IMAGE_FOR_HOOK =
            "Sheriff's image %s is not on this machine, so the code standards hook lets this through "
            + "unchecked. Pull it (docker pull), or start the MCP server, which pulls it by itself.%n";
    private static final String HOOK_FAILED =
            "sheriff-agent's hook could not run (%s) -- letting the work through unchecked.%n";
    private static final String FLAG_NOT_A_NUMBER = "%s takes a whole number, but was given '%s'.";
    private static final String NOTHING_ANALYZED =
            "Nothing was analyzed: '%s' holds no %s sources, so the %s profile had no files to look at.%n"
            + "That is not the same as passing. Check SHERIFF_COMPONENT and SHERIFF_TEST_TYPE.%n";
    private static final String NOTHING_ANALYZED_REASON = "the component holds no sources of the profile's language";
    private static final String HELP = "--help";
    private static final String USAGE = """
            Fixes code until Sheriff stops reporting errors.

              (no arguments)          run the full fix loop
              --check-only            run Sheriff once and report; no fixer, no git
              --full-check            this module's tests, the target's build, and Sheriff
              --sheriff-fix           apply only Sheriff's own fixers; no AI, no tokens
              --no-sheriff-fix        skip that pass before the loop
              --rules [QUERY]         list the rules this profile enforces, or search them
              --fixer CODE            print the script Sheriff runs to repair CODE
              --extract-rules [PATH]  read the whole rule catalog out of the Sheriff image
              --hook-gate             Claude Code PreToolUse hook: reads the call on stdin
              --hook-stop             Claude Code Stop hook: a turn can't end dirty
              --hook-turn             Claude Code UserPromptSubmit hook: records the turn's start
              --json-report PATH      also write the result as JSON
              --max-iterations N      cap the loop's iterations
              --max-files-per-batch N cap how many files one pass may touch
              --no-git-safety         no branch, no commits, no scope check
              --help                  this text
            """;
    private static final String OPTION_PREFIX = "--";
    private static final String WHOLE_INPUT = "\\A";
    private static final String NOTHING = "";
    private static final String OK_FIELD = "ok";
    private static final String INFRASTRUCTURE_ERROR_FIELD = "infrastructure_error";
    private static final String TOTAL_FIELD = "total";
    private static final String ERRORS_FIELD = "errors";
    private static final String PROFILE_FIELD = "profile";
    private static final String QUERY_FIELD = "query";
    private static final String TOTAL_IN_CATALOG_FIELD = "total_in_catalog";
    private static final String RULES_FIELD = "rules";
    private static final String STOPPED_REASON_FIELD = "stopped_reason";
    private static final String ITERATIONS_USED_FIELD = "iterations_used";
    private static final String MAX_ITERATIONS_FIELD = "max_iterations";
    private static final String REPAIR_PASS_USED_FIELD = "repair_pass_used";
    private static final String PARKED_FILES_FIELD = "parked_files";
    private static final String WORKING_BRANCH_FIELD = "working_branch";
    private static final String TESTS_PASSED_FIELD = "tests_passed";
    private static final String CODE_FIELD = "code";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String HOW_TO_SOLVE_FIELD = "how_to_solve";
    private static final String LANGUAGE_FIELD = "language";
    private static final String CATEGORY_FIELD = "category";
    private static final String FIXER_FIELD = "fixer";
    private static final String PROFILES_FIELD = "profiles";
    private static final int NO_ERRORS = 0;
    private static final int NOT_PRESENT = -1;
    private static final String EXAMPLE_HEADING =
            "%n    The code Sheriff writes to satisfy it:%n%n".formatted();
    private static final String EXAMPLE_LINE = "      %s%n";
    private static final String SCRIPT_SUFFIX = ".py";
    private static final String NO_SUCH_RULE =
            "No rule with code '%s' in the catalog. Try: --rules %s%n";
    private static final String RULE_HAS_NO_FIXER =
            "%s has no fixer script -- Sheriff cannot repair this one itself, "
            + "so there is no canonical snippet to read.%n";
    private static final String FIXER_IS_NOT_A_SCRIPT =
            "%s's fixer is %s, which is not a script this can print.%n";
    private static final String COULD_NOT_READ_FIXER = "Could not read %s: %s%n";
    private static final String FIXER_HEADING = "%s -- %s/%s%n%n";
    private static final String MARKDOWN_NAME = "SHERIFF_RULES.md";
    private static final String OWN_TESTS_COMMAND = "mvn -q -o test";
    private static final String PASSED = "PASSED\n";
    private static final String FAILED = "FAILED\n";
    private static final String ALL_GREEN = "ALL GREEN";
    private static final String SOMETHING_IS_RED = "SOMETHING IS RED";
    private static final String OK = "ok";
    private static final String FAIL = "FAIL";
    private static final String NO_CATALOG = """
            No rule catalog at %s.
            Generate one with the extractor (it reads them out of the Sheriff image).
            """;

    private final Configuration configuration;
    private final Composition composition;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the CLI to a composition.
     *
     * @param configuration what was configured
     * @param composition what it builds
     */
    public Agent(Configuration configuration, Composition composition) {
        this.configuration = configuration;
        this.composition = composition;
    }

    /**
     * Entry point.
     *
     * @param arguments the command line
     */
    public static void main(String[] arguments) {
        main(System.getenv(), arguments);
    }

    /**
     * Runs the agent's command line with a given environment, for a caller
     * that has filled in what the user left unset.
     *
     * @param environment the variables to configure it with
     * @param arguments the command line, without the executable
     */
    public static void main(Map<String, String> environment, String[] arguments) {
        Configuration configuration = Configuration.fromEnvironment(environment);
        Agent agent = new Agent(configuration, Composition.real(configuration));
        System.exit(agent.run(List.of(arguments)));
    }

    /**
     * Runs one invocation.
     *
     * @param arguments the command line, without the executable
     * @return the exit code
     */
    public int run(List<String> arguments) {
        try {
            return dispatch(arguments);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            System.out.println(ERROR_PREFIX + exception.getMessage());
            return MISUSE;
        }
    }

    /**
     * Runs one of the Claude Code hooks.
     *
     * <p>A hook that cannot run lets the work through, like one whose Docker
     * is down: exit code 2 is how a hook blocks, so the command line's usual
     * answer to a misconfiguration would have turned a mistyped variable into
     * a gate that refuses every edit.
     *
     * <p>All stand aside when the session they guard is this agent's own
     * fixer. The loop runs its assistant inside the target repository, so a
     * project with the hooks installed would otherwise have the gate block
     * the very edits the loop asked for, on a component that fails by
     * definition, and the Stop hook refuse to let the pass end.
     *
     * @param arguments the command line, naming which hook
     * @return the hook's exit code
     */
    private int hook(List<String> arguments) {
        if (configuration.insideAgentRun()) {
            return HookExit.ALLOW;
        }
        if (arguments.contains(HOOK_TURN)) {
            return startTurn();
        }
        if (!configuration.mockMode() && !composition.sheriffImage().present()) {
            System.err.printf(NO_IMAGE_FOR_HOOK, configuration.image());
            return HookExit.ALLOW;
        }
        try {
            String payload = readStandardInput();
            if (arguments.contains(HOOK_GATE)) {
                return new SheriffGateHook(composition.componentGate()).decide(payload);
            }
            return composition.stopHook().decide(payload);
        } catch (RuntimeException exception) {
            System.err.printf(HOOK_FAILED, exception.getMessage());
            return HookExit.ALLOW;
        }
    }

    /**
     * The turn hook, which needs no image: it only records what the changed
     * components look like, and must never stand in the way of a prompt.
     *
     * @return the exit code, always 0
     */
    private int startTurn() {
        try {
            return composition.stopHook().startTurn(readStandardInput());
        } catch (RuntimeException exception) {
            System.err.printf(HOOK_FAILED, exception.getMessage());
            return HookExit.ALLOW;
        }
    }

    /**
     * Whether a mode runs Sheriff, and so needs its image first. Listing the
     * rules reads only the catalog, and a mock run has no image at all.
     *
     * @param arguments the command line
     * @return {@code true} when the image must be here
     */
    private boolean needsImage(List<String> arguments) {
        return !configuration.mockMode() && !arguments.contains(RULES);
    }

    /**
     * Whether a mode reads the rule catalog, and so needs one that matches
     * the image. A check only analyzes, and extracting a catalog it will not
     * read would only make it slower.
     *
     * @param arguments the command line
     * @return {@code true} unless the mode only checks
     */
    private boolean readsCatalog(List<String> arguments) {
        return !arguments.contains(CHECK_ONLY) && !arguments.contains(FULL_CHECK);
    }

    /**
     * Pulls Sheriff's image when it is missing, and reports it when it is
     * out of date, before a mode that runs it.
     *
     * @return {@code true} when the image is here and can be used
     */
    private boolean imageReady() {
        ImageProvisioning image = composition.imageProvisioning(System.err);
        boolean ready = image.now();
        image.blocker().ifPresent(System.err::println);
        return ready;
    }

    /**
     * Picks the mode this command line asks for.
     *
     * <p>Order matters: the modes that answer a question and exit come before
     * the one that changes files, so a mistyped command line never starts a
     * fix loop by accident.
     *
     * @param arguments the command line, without the executable
     * @return the exit code
     */
    private int dispatch(List<String> arguments) {
        if (arguments.contains(HELP)) {
            System.out.print(USAGE);
            return SUCCESS;
        }
        if (arguments.contains(HOOK_GATE) || arguments.contains(HOOK_STOP) || arguments.contains(HOOK_TURN)) {
            return hook(arguments);
        }
        if (needsImage(arguments) && !imageReady()) {
            return FAILURE;
        }
        Path report = pathAfter(arguments, JSON_REPORT);
        if (arguments.contains(EXTRACT_RULES)) {
            return extractRules(valueAfter(arguments, EXTRACT_RULES));
        }
        if (readsCatalog(arguments)) {
            composition.provisionCatalog().ifPresent(warning -> System.err.print(warning));
        }
        if (arguments.contains(FIXER)) {
            return showFixer(valueAfter(arguments, FIXER));
        }
        if (arguments.contains(RULES)) {
            return listRules(valueAfter(arguments, RULES), report);
        }
        if (arguments.contains(SHERIFF_FIX)) {
            return sheriffFix() ? SUCCESS : FAILURE;
        }
        if (arguments.contains(FULL_CHECK)) {
            return fullCheck();
        }
        if (arguments.contains(CHECK_ONLY)) {
            return checkOnly(report);
        }
        return fix(arguments, report);
    }

    /**
     * Whether the component holds any file the profile's language would put
     * under analysis.
     *
     * <p>Zero errors has two causes and Sheriff reports them the same way:
     * the code passed, or there was no code of that language to look at. In a
     * gate the second is a silent pass, and it is the likelier of the two,
     * because it is what a mistyped component or the wrong profile produces.
     * A component that does not exist at all already fails; this makes the
     * one that exists and holds nothing fail in the same way.
     *
     * @return whether anything was there to analyze
     */
    private boolean hasSourcesToAnalyze() {
        List<String> suffixes = Rules.sourceSuffixesFor(Rules.languageForProfile(configuration.testType()));
        if (suffixes.isEmpty()) {
            return true;
        }
        Path component = configuration.targetRepository().resolve(configuration.component());
        try (Stream<Path> files = Files.walk(component)) {
            return files.filter(Files::isRegularFile)
                    .anyMatch(file -> endsWithAny(file.getFileName().toString().toLowerCase(Locale.ROOT), suffixes));
        } catch (IOException exception) {
            return true;
        }
    }

    /**
     * Whether a file name ends with any of the given extensions.
     *
     * @param name the lowercased file name
     * @param suffixes the extensions to accept
     * @return whether one of them matches
     */
    private static boolean endsWithAny(String name, List<String> suffixes) {
        return suffixes.stream().anyMatch(name::endsWith);
    }

    /**
     * Runs Sheriff once and reports what it said, with no fixer and no git
     * involved. The part of this tool that is ready to sit in a CI gate.
     *
     * @param report where to write the JSON report, {@code null} to skip it
     * @return the exit code
     */
    private int checkOnly(Path report) {
        AnalysisResult result = composition.analyzer().analyze();
        if (result.error()) {
            System.out.printf("Sheriff isn't responding: %s%n", result.message());
            write(report, Map.of(OK_FIELD, false, INFRASTRUCTURE_ERROR_FIELD, result.message()));
            return FAILURE;
        }
        if (result.total() == NO_ERRORS) {
            if (!hasSourcesToAnalyze()) {
                System.out.printf(NOTHING_ANALYZED, configuration.component(),
                        Rules.languageForProfile(configuration.testType()), configuration.testType());
                write(report, Map.of(OK_FIELD, false, INFRASTRUCTURE_ERROR_FIELD, NOTHING_ANALYZED_REASON));
                return FAILURE;
            }
            System.out.println("Sheriff reports no errors.");
        } else {
            System.out.printf("Sheriff reports %d error(s):%n", result.total());
            for (SheriffFinding finding : result.errors()) {
                System.out.printf("  - %s%n", finding.describe());
            }
        }
        write(report, Map.of(OK_FIELD, result.total() == NO_ERRORS, TOTAL_FIELD, result.total(),
                ERRORS_FIELD, result.errors().stream().map(SheriffFinding::raw).toList()));
        return result.total() == NO_ERRORS ? SUCCESS : FAILURE;
    }

    /**
     * Everything on standard input, which is how Claude Code hands a hook its
     * payload.
     *
     * @return that text, empty when there was none
     */
    private static String readStandardInput() {
        try (java.util.Scanner scanner = new java.util.Scanner(System.in, StandardCharsets.UTF_8)) {
            return scanner.useDelimiter(WHOLE_INPUT).hasNext() ? scanner.next() : NOTHING;
        }
    }

    /**
     * Regenerates the rule catalog from the Sheriff image.
     *
     * @param destination where to write it, empty for the configured path
     * @return the exit code
     */
    private int extractRules(String destination) {
        Path catalog = destination.isEmpty() ? configuration.rulesCatalog() : Path.of(destination);
        Path markdown = catalog.resolveSibling(MARKDOWN_NAME);
        try {
            composition.ruleCatalogExtractor().extract(configuration.image(), catalog, markdown);
            return SUCCESS;
        } catch (IOException | IllegalStateException exception) {
            System.out.println("error: " + exception.getMessage());
            return FAILURE;
        }
    }

    /**
     * Hands everything Sheriff can repair by itself to Sheriff, before any
     * model is asked for anything.
     *
     * <p>Eighty-five of its rules ship a fixer, and a script that sorts
     * imports costs nothing to run and nothing to review. Whatever survives
     * this is what an AI is actually needed for.
     *
     * @return whether the pass ran
     */
    private boolean sheriffFix() {
        DeterministicFixReport report = composition.deterministicFixer().run();
        System.out.println(report.describe());
        return report.ran();
    }

    /**
     * The traffic light this repository runs after any change to the agent
     * itself: its own tests, a real build of the target project, and a real
     * Sheriff check. Fixes nothing and invokes no AI backend.
     *
     * <p>Three checks rather than one because they fail differently and for
     * different reasons — a green Sheriff says nothing about whether the code
     * still compiles, and a green build says nothing about style. Reporting
     * them separately is what makes a red result actionable.
     *
     * @return the exit code: 0 only when all three are green
     */
    private int fullCheck() {
        System.out.println("=== 1/3: this module's own tests ===");
        boolean unitTests = runVerification(OWN_TESTS_COMMAND);
        System.out.println(unitTests ? PASSED : FAILED);
        System.out.println("=== 2/3: target project build + tests ===");
        boolean build = composition.testRunner().run().ok();
        System.out.println(build ? PASSED : FAILED);
        System.out.println("=== 3/3: real Sheriff check ===");
        boolean sheriff = checkOnly(null) == SUCCESS;
        System.out.println(sheriff ? PASSED : FAILED);
        boolean green = unitTests && build && sheriff;
        System.out.printf("%s — own tests: %s, build/tests: %s, Sheriff: %s%n",
                green ? ALL_GREEN : SOMETHING_IS_RED,
                unitTests ? OK : FAIL, build ? OK : FAIL, sheriff ? OK : FAIL);
        return green ? SUCCESS : FAILURE;
    }

    /**
     * Runs one shell command from the agent's own directory.
     *
     * @param command what to run
     * @return whether it succeeded
     */
    private boolean runVerification(String command) {
        return composition.commandRunner(command, configuration.agentDirectory()).run().ok();
    }

    /**
     * Prints the script Sheriff runs to repair a rule, straight from the image.
     *
     * <p>Worth a mode of its own because a rule's description says what is
     * wrong and its howToSolve says the same thing again; neither says what
     * the accepted code looks like. The fixer's script does — the snippet it
     * writes is the accepted code — so reading it settles a question that
     * re-reading the description does not.
     *
     * @param code the rule to look up
     * @return the exit code
     */
    private int showFixer(String code) {
        List<SheriffRule> rules = composition.ruleCatalog().allRules();
        if (rules.isEmpty()) {
            System.out.printf(NO_CATALOG, composition.catalogPath());
            return FAILURE;
        }
        Optional<SheriffRule> found = Rules.findRule(rules, code);
        if (found.isEmpty()) {
            System.out.printf(NO_SUCH_RULE, code, code);
            return FAILURE;
        }
        SheriffRule rule = found.get();
        if (!rule.hasFixer()) {
            System.out.printf(RULE_HAS_NO_FIXER, rule.code());
            return FAILURE;
        }
        if (!rule.fixer().endsWith(SCRIPT_SUFFIX)) {
            System.out.printf(FIXER_IS_NOT_A_SCRIPT, rule.code(), rule.fixer());
            return FAILURE;
        }
        String script;
        try {
            script = composition.fixerScripts().read(rule.fixer());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            System.out.printf(COULD_NOT_READ_FIXER, rule.fixer(), failure.getMessage());
            return FAILURE;
        }
        System.out.printf(FIXER_HEADING, rule.code(), DockerFixerScriptSource.FIXERS_ROOT, rule.fixer());
        System.out.println(script);
        return SUCCESS;
    }

    /**
     * Says what Sheriff expects before a rule is broken: the rules this
     * profile runs, or the ones matching a query. No Sheriff, no fixer, no
     * git — just the catalog.
     *
     * <p>An example is the useful part and also the long part, so it is
     * printed when you asked about particular rules, not when you asked for
     * a whole profile and got fifty of them.
     *
     * @param query what to search for, empty for the whole profile
     * @param report where to write the JSON report, {@code null} to skip it
     * @return the exit code
     */
    private int listRules(String query, Path report) {
        List<SheriffRule> rules = composition.ruleCatalog().allRules();
        if (rules.isEmpty()) {
            System.out.printf(NO_CATALOG, composition.catalogPath());
            return FAILURE;
        }
        List<SheriffRule> selected;
        if (query.isEmpty()) {
            RuleSelection selection = Rules.selectRulesForProfile(rules, configuration.testType());
            selected = selection.rules();
            System.out.printf(selection.exact()
                            ? "%d rule(s) the '%s' profile runs:%n%n"
                            : "%d rule(s) for the language of '%s' (the catalog doesn't record that profile):%n%n",
                    selected.size(), configuration.testType());
        } else {
            selected = Rules.searchRules(rules, query);
            System.out.printf("%d of %d rule(s) matching '%s':%n%n", selected.size(), rules.size(), query);
        }
        for (SheriffRule rule : selected) {
            System.out.printf("- %s%n", rule.describe());
            if (rule.hasFixer()) {
                System.out.println("    (Sheriff can fix this one itself)");
            }
            if (!query.isEmpty() && rule.hasExample()) {
                System.out.print(EXAMPLE_HEADING);
                rule.example().lines().forEach(line -> System.out.printf(EXAMPLE_LINE, line));
                System.out.println();
            }
        }
        write(report, Map.of(OK_FIELD, true, PROFILE_FIELD, configuration.testType(), QUERY_FIELD, query,
                TOTAL_IN_CATALOG_FIELD, rules.size(),
                RULES_FIELD, selected.stream().map(Agent::describe).toList()));
        return SUCCESS;
    }

    /**
     * Runs the full fix loop and reports what it did.
     *
     * <p>Sheriff's own fixers are part of the loop, run inside its branch,
     * unless {@code --no-sheriff-fix} says otherwise. They used to run here,
     * before the loop, which edited whatever branch was checked out and then
     * made the loop refuse to start over the changes they had just made.
     *
     * @param arguments the command line, for the settings it overrides
     * @param report where to write the JSON report, {@code null} to skip it
     * @return the exit code
     */
    private int fix(List<String> arguments, Path report) {
        boolean automaticRepair = !arguments.contains(NO_SHERIFF_FIX);
        RunSummary summary = composition.fixLoop(settingsFor(arguments), automaticRepair).execute();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(OK_FIELD, summary.ok());
        body.put(STOPPED_REASON_FIELD, summary.stoppedReason().wireName());
        body.put(ITERATIONS_USED_FIELD, summary.iterationsUsed());
        body.put(MAX_ITERATIONS_FIELD, summary.maxIterations());
        body.put(REPAIR_PASS_USED_FIELD, summary.repairPassUsed());
        body.put(PARKED_FILES_FIELD, summary.parkedFiles());
        body.put(WORKING_BRANCH_FIELD, summary.workingBranch());
        body.put(TESTS_PASSED_FIELD, summary.testsPassed());
        write(report, body);
        return summary.ok() ? SUCCESS : FAILURE;
    }

    /**
     * One rule as the JSON report carries it.
     *
     * @param rule the rule to describe
     * @return its fields, in the order the report uses
     */
    private static Map<String, Object> describe(SheriffRule rule) {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put(CODE_FIELD, rule.code());
        described.put(DESCRIPTION_FIELD, rule.description());
        described.put(HOW_TO_SOLVE_FIELD, rule.howToSolve());
        described.put(LANGUAGE_FIELD, rule.language());
        described.put(CATEGORY_FIELD, rule.category());
        described.put(FIXER_FIELD, rule.fixer());
        described.put(PROFILES_FIELD, rule.profiles());
        return described;
    }

    /**
     * Writes the JSON report, when one was asked for.
     *
     * <p>Best effort: a report that cannot be written is worth saying out
     * loud, but it is not worth failing a run that otherwise went fine.
     *
     * @param report where it goes, {@code null} when none was asked for
     * @param body what to write
     */
    private void write(Path report, Map<String, Object> body) {
        if (report == null) {
            return;
        }
        try {
            Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(body));
        } catch (IOException exception) {
            System.out.printf("could not write the report to %s: %s%n", report, exception.getMessage());
        }
    }

    /**
     * The value of an option that takes one, such as {@code --rules javadoc}.
     *
     * @param arguments the command line
     * @param option the option to look for
     * @return what followed it, empty when nothing did or the next token is
     *     another option
     */
    static String valueAfter(List<String> arguments, String option) {
        int index = arguments.indexOf(option);
        if (index == NOT_PRESENT || index + 1 >= arguments.size()) {
            return NOTHING;
        }
        String next = arguments.get(index + 1);
        return next.startsWith(OPTION_PREFIX) ? NOTHING : next;
    }

    /**
     * The value of an option that takes one, as a path.
     *
     * @param arguments the command line
     * @param option the option to look for
     * @return that path, or {@code null} when the option carried no value
     */
    private static Path pathAfter(List<String> arguments, String option) {
        String value = valueAfter(arguments, option);
        return value.isEmpty() ? null : Path.of(value);
    }

    /**
     * The loop settings for this invocation: what was configured, with
     * whatever the command line overrode on top.
     *
     * <p>The iteration cap is built with {@code Integer.valueOf} rather than
     * {@code parseInt} on purpose: a ternary mixing {@code Integer} and
     * {@code int} unboxes, so the default case — no configured cap, which is
     * the normal one — would throw instead of meaning "size it automatically".
     *
     * @param arguments the command line
     * @return the settings to run with
     */
    LoopSettings settingsFor(List<String> arguments) {
        LoopSettings configured = configuration.loopSettings();
        String iterations = valueAfter(arguments, MAX_ITERATIONS);
        String files = valueAfter(arguments, MAX_FILES);
        return new LoopSettings(
                iterations.isEmpty() ? configured.maxIterations() : flagNumber(MAX_ITERATIONS, iterations),
                configured.useGitSafety() && !arguments.contains(NO_GIT_SAFETY),
                files.isEmpty() ? configured.maxFilesPerBatch() : flagNumber(MAX_FILES, files));
    }

    /**
     * A flag's value as a whole number.
     *
     * @param flag the flag, for the message
     * @param value what followed it
     * @return the number
     * @throws IllegalArgumentException naming the flag when it is not one
     */
    private static Integer flagNumber(String flag, String value) {
        try {
            return Integer.valueOf(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(String.format(FLAG_NOT_A_NUMBER, flag, value), exception);
        }
    }
}
