package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.DeterministicPolicy;
import com.kaizten.sheriff.domain.port.AutomaticRepair;
import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs Sheriff's own fixers before any model is asked for anything.
 *
 * <p>Eighty-five of Sheriff's rules ship a deterministic fixer — a small jar
 * or a Python script. Spending tokens on what a script repairs for free is
 * waste, so this pass goes first and only what survives it reaches the AI.
 *
 * <p>The sequence is not obvious and was established by running it, because
 * none of it is documented:
 *
 * <ol>
 *   <li><b>Analyze, keeping Sheriff's state.</b> {@code fix} does not analyze
 *       anything itself: it reads the findings file an analysis leaves behind.
 *       Run it on a repository with no such file and it repairs nothing at
 *       all, silently.</li>
 *   <li><b>{@code fix -f}</b>, which applies some of the available fixers but
 *       not all of them.</li>
 *   <li><b>{@code fix -f --fixers A,B,C}</b>, once, with every rule the
 *       analysis found; a rule with no fixer in the list is ignored. Older
 *       images accepted a list and repaired nothing, so this used to run once
 *       per code; the image of 2026-09-21 repairs the whole list, and one
 *       container instead of one per code is seconds saved per run.</li>
 *   <li><b>Delete the state.</b> Left in place it makes the next analysis
 *       report the component clean when it is not — a stale verdict that looks
 *       exactly like success.</li>
 * </ol>
 *
 * <p>Then again, for each profile whose count the round before lowered, up
 * to three rounds: one fixer's change can be another's finding. It is the sequence
 * {@code sheriff_fix} runs with no rule named, so the MCP, the loop and the
 * Maven goal leave the same code behind.
 */
public final class DeterministicFixer implements AutomaticRepair {

    private static final String FIX_COMMAND = "fix";
    private static final String APPLY_FLAG = "-f";
    private static final String FIXERS_FLAG = "--fixers";
    private static final String URI_FLAG = "-u";
    private static final String COMPONENT_FLAG = "-c";
    private static final String URI_ARGUMENT = "file:/data";
    private static final int NO_ERRORS = 0;
    private static final String CODE_LIST_SEPARATOR = ",";
    private static final int NOT_YET = -1;
    private static final int FIRST_ROUND = 1;
    private static final int FIRST_PROFILE = 0;
    private static final int MAXIMUM_ROUNDS = 3;

    private final SheriffContainer container;
    private final Path repositoryRoot;
    private final RuleCatalog catalog;
    private final SheriffDockerAnalyzer analyzer;
    private final String component;

    /**
     * Wires the pass to one component.
     *
     * @param processes how to run Docker
     * @param catalog what knows which rules have a fixer
     * @param analyzer an analyzer that keeps Sheriff's state, because the fix
     *     subcommand needs it
     * @param image the Sheriff image
     * @param component the folder to repair
     * @param repositoryRoot the repository to mount
     * @param timeout how long each invocation is given
     */
    public DeterministicFixer(
            ProcessRunner processes,
            RuleCatalog catalog,
            SheriffDockerAnalyzer analyzer,
            String image,
            String component,
            Path repositoryRoot,
            Duration timeout) {
        this.container = new SheriffContainer(processes, image, repositoryRoot, timeout);
        this.repositoryRoot = repositoryRoot;
        this.catalog = catalog;
        this.analyzer = analyzer;
        this.component = component;
    }

    /**
     * Applies every fixer Sheriff has for what is currently wrong, as the fix
     * loop's first step.
     *
     * @return the report as one line for the run's log
     */
    @Override
    public String apply() {
        return run().describe();
    }

    /**
     * Applies every fixer Sheriff has for what is currently wrong.
     *
     * @return what it was able to take on, and what it left behind
     */
    public DeterministicFixReport run() {
        return MountLock.holding(repositoryRoot, this::repair);
    }

    /**
     * The analysis, the repairs and the clean-up, with the mount's lock held
     * across them: {@code fix} reads the state the analysis left, and another
     * run in between would replace it.
     *
     * @return what the pass took on
     */
    private DeterministicFixReport repair() {
        List<SheriffRule> rules = catalog.allRules();
        Set<String> codes = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        List<SheriffDockerAnalyzer> profiles = analyzer.perProfile();
        List<Integer> previous = new ArrayList<>(Collections.nCopies(profiles.size(), Integer.MAX_VALUE));
        int errorsBefore = NOT_YET;
        boolean progress = true;
        for (int round = FIRST_ROUND; progress && round <= MAXIMUM_ROUNDS; round++) {
            progress = false;
            List<AnalysisResult> analyses = new ArrayList<>();
            for (int index = FIRST_PROFILE; index < profiles.size(); index++) {
                SheriffDockerAnalyzer single = profiles.get(index);
                AnalysisResult before = single.analyze();
                if (before.error()) {
                    single.removeStateFiles();
                    return DeterministicFixReport.unavailable(before.message());
                }
                analyses.add(before);
                if (before.total() < previous.get(index) && repairable(before, rules)) {
                    failures.addAll(repairFrom(before, rules, codes));
                    progress = true;
                }
                previous.set(index, before.total());
                single.removeStateFiles();
            }
            errorsBefore = errorsBefore == NOT_YET ? AnalysisResult.merged(analyses).total() : errorsBefore;
        }
        if (rules.isEmpty() && errorsBefore > NO_ERRORS) {
            return DeterministicFixReport.defaultsOnly(errorsBefore);
        }
        if (codes.isEmpty()) {
            return DeterministicFixReport.nothingToDo(errorsBefore);
        }
        return DeterministicFixReport.applied(errorsBefore, List.copyOf(codes), failures.stream().distinct().toList());
    }

    /**
     * Runs the fixers for what one profile's analysis found, while the state
     * that analysis left is still there for {@code fix} to read: Sheriff's
     * default set, then the fixer of every rule found.
     *
     * <p>The same sequence {@code sheriff_fix} runs, so the MCP, the loop and
     * the Maven goal leave the same code behind. Every rule found is named,
     * not only those the catalog says have a fixer: a rule with none in the
     * list is ignored, and the repair then needs no catalog at all.
     *
     * @param before the analysis under one profile
     * @param rules the catalog
     * @param codes where to add the codes the catalog knows a fixer for
     * @return the fixers that could not be applied
     */
    private List<String> repairFrom(AnalysisResult before, List<SheriffRule> rules, Set<String> codes) {
        codes.addAll(DeterministicPolicy.fixableCodes(before.errors(), rules));
        List<String> failures = new ArrayList<>(runFix(List.of()));
        String found = before.errors().stream().map(SheriffFinding::referenceCode).filter(code -> !code.isEmpty())
                .distinct().collect(Collectors.joining(CODE_LIST_SEPARATOR));
        if (!found.isEmpty()) {
            failures.addAll(runFix(List.of(FIXERS_FLAG, found)));
        }
        return failures;
    }

    /**
     * Whether an analysis leaves anything for Sheriff's fixers: errors, and,
     * when there is a catalog, a rule among them it knows a fixer for. With a
     * catalog that knows none, nothing runs: a container saved, as the
     * catalog is checked against the image installed.
     *
     * @param analysis the analysis under one profile
     * @param rules the catalog, empty when there is none
     * @return {@code true} when the fixers are worth running
     */
    private static boolean repairable(AnalysisResult analysis, List<SheriffRule> rules) {
        return analysis.total() > NO_ERRORS
                && (rules.isEmpty() || !DeterministicPolicy.fixableCodes(analysis.errors(), rules).isEmpty());
    }

    /**
     * One invocation of Sheriff's own fixer.
     *
     * <p>A failure does not stop the pass: whether anything was actually
     * repaired is the next analysis's answer, and a fixer that declines to act
     * is a normal outcome. A fixer Sheriff could not apply at all is different,
     * and is handed back so the report can say so.
     *
     * @param extraArguments what to add after the standard arguments, such as
     *     a single {@code --fixers} code
     * @return the fixers Sheriff could not apply, and why
     */
    private List<String> runFix(List<String> extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                FIX_COMMAND, APPLY_FLAG,
                URI_FLAG, URI_ARGUMENT,
                COMPONENT_FLAG, component));
        arguments.addAll(extraArguments);
        return FixerFailures.in(container.run(arguments).standardOutput());
    }
}
