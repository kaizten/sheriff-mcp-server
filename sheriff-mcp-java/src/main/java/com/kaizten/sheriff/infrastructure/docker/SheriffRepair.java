package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.DeterministicPolicy;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one way Sheriff's own fixers are run: by {@code sheriff_fix}, by the
 * loop before any model is asked, and by {@code sheriff:fix}.
 *
 * <p>It used to be written twice, in the MCP's runner and in the loop's
 * deterministic pass, and the two had already drifted the way the two
 * analyzers once did: different flags, rounds counted differently, and a
 * {@code fix} that could not run failed one and went unnoticed in the other.
 * So the same code could end in a different state depending on who asked,
 * which the tools promise it never does.
 *
 * <p>The sequence was established by running it, because none of it is
 * documented:
 *
 * <ol>
 *   <li><b>Analyze, keeping Sheriff's state.</b> {@code fix} does not analyze
 *       anything itself: it reads the findings file an analysis leaves
 *       behind, and with none it repairs nothing, silently.</li>
 *   <li><b>{@code fix -f}</b>, Sheriff's default set, which applies some
 *       fixers but not all of them.</li>
 *   <li><b>{@code fix -f --fixers A,B,C}</b>, once, with every rule the
 *       analysis found; a rule with no fixer in the list is ignored.</li>
 *   <li><b>Delete the state.</b> Left in place it makes the next analysis
 *       report the component clean when it is not.</li>
 * </ol>
 *
 * <p>Profile by profile, since {@code fix} reads the state of the
 * {@code test} just before it, and in rounds: one fixer's change can be
 * another's finding, so a profile whose count the round before lowered is
 * repaired again, up to three rounds. Each round's analysis measures the one
 * before, so the last analysis, the one that repaired nothing, is the state
 * the repair left; a fixer that exits 0 having done nothing is caught there.
 * With rules named, only their fixers run, once.
 */
public final class SheriffRepair {

    private static final String FIX_COMMAND = "fix";
    private static final String APPLY_FLAG = "-f";
    private static final String FIXERS_FLAG = "--fixers";
    private static final String URI_FLAG = "--uri";
    private static final String COMPONENT_FLAG = "--component";
    private static final String URI_ARGUMENT = "file:/data";
    private static final String CODE_SEPARATOR = ",";
    private static final int NO_ERRORS = 0;
    private static final int FIRST_ROUND = 1;
    private static final int EVERY_FIXER_ROUNDS = 3;
    private static final int NAMED_FIXER_ROUNDS = 1;
    private static final int FIRST_PROFILE = 0;

    private final SheriffContainer container;
    private final SheriffDockerAnalyzer analyzer;
    private final String component;
    private final Path repositoryRoot;

    /**
     * Wires the repair to one component.
     *
     * @param container what runs Sheriff's {@code fix}
     * @param analyzer an analyzer of the component, which may export what
     *     it reads; it is made to keep Sheriff's state, which {@code fix}
     *     needs, and the state is removed here
     * @param component the folder to repair
     * @param repositoryRoot the directory Sheriff mounts
     */
    public SheriffRepair(SheriffContainer container, SheriffDockerAnalyzer analyzer, String component,
            Path repositoryRoot) {
        this.container = container;
        this.analyzer = analyzer;
        this.component = component;
        this.repositoryRoot = repositoryRoot;
    }

    /**
     * Every repair Sheriff has: its default set and the fixer of every rule
     * found, in rounds while they help.
     *
     * @param rules the catalog, empty when there is none; with one, a
     *     profile whose findings it knows no fixer for is not given to the
     *     fixers, a container saved
     * @return what it did, measured
     */
    public RepairOutcome everyFixer(List<SheriffRule> rules) {
        return MountLock.holding(repositoryRoot, () -> repair(rules, Optional.empty(), EVERY_FIXER_ROUNDS));
    }

    /**
     * The fixers of the rules named, and no other, once.
     *
     * @param codes the rules, separated by commas
     * @return what it did, measured
     */
    public RepairOutcome onlyRules(String codes) {
        return MountLock.holding(repositoryRoot, () -> repair(List.of(), Optional.of(codes), NAMED_FIXER_ROUNDS));
    }

    /**
     * The rounds, with the mount's lock held across them: {@code fix} reads
     * the state the analysis before it left, and another run in between
     * would replace it.
     *
     * @param rules the catalog, empty when there is none
     * @param named the rules asked for, or empty for every fixer
     * @param rounds how many rounds may repair
     * @return what it did
     */
    private RepairOutcome repair(List<SheriffRule> rules, Optional<String> named, int rounds) {
        List<SheriffDockerAnalyzer> profiles = analyzer.perProfile();
        List<Integer> previous = new ArrayList<>(Collections.nCopies(profiles.size(), Integer.MAX_VALUE));
        Set<String> codes = new LinkedHashSet<>();
        List<String> failures = new ArrayList<>();
        AnalysisResult before = null;
        AnalysisResult after;
        int round = FIRST_ROUND;
        boolean repaired;
        do {
            repaired = false;
            List<AnalysisResult> analyses = new ArrayList<>();
            for (int index = FIRST_PROFILE; index < profiles.size(); index++) {
                SheriffDockerAnalyzer single = profiles.get(index);
                try {
                    AnalysisResult analysis = single.analyze();
                    if (analysis.error()) {
                        return RepairOutcome.failed(analysis.message());
                    }
                    analyses.add(analysis);
                    boolean helping = analysis.total() < previous.get(index);
                    previous.set(index, analysis.total());
                    if (round <= rounds && helping && repairable(analysis, rules)) {
                        codes.addAll(DeterministicPolicy.fixableCodes(analysis.errors(), rules));
                        failures.addAll(named.isPresent() ? runFix(List.of(FIXERS_FLAG, named.get()))
                                : everyFixerFor(analysis));
                        repaired = true;
                    }
                } catch (FixNotRun exception) {
                    return RepairOutcome.failed(exception.getMessage());
                } finally {
                    single.removeStateFiles();
                }
            }
            after = AnalysisResult.merged(analyses);
            before = before == null ? after : before;
            round++;
        } while (repaired);
        return new RepairOutcome(before, after, List.copyOf(codes), failures.stream().distinct().toList(),
                Optional.empty());
    }

    /**
     * Sheriff's default set, then the fixer of every rule one analysis
     * found, while the state that analysis left is still there to read.
     *
     * @param analysis the analysis under one profile
     * @return the fixers that could not be applied
     */
    private List<String> everyFixerFor(AnalysisResult analysis) {
        List<String> failures = new ArrayList<>(runFix(List.of()));
        String found = analysis.errors().stream().map(SheriffFinding::referenceCode).filter(code -> !code.isEmpty())
                .distinct().collect(Collectors.joining(CODE_SEPARATOR));
        if (!found.isEmpty()) {
            failures.addAll(runFix(List.of(FIXERS_FLAG, found)));
        }
        return failures;
    }

    /**
     * Whether an analysis leaves anything for Sheriff's fixers: errors, and,
     * when there is a catalog, a rule among them it knows a fixer for.
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
     * One {@code fix}, whose exit code says nothing: it is 1 whatever
     * happened. What it says is on stdout, and a run that printed nothing
     * there but a reason on stderr, or never started, did not repair.
     *
     * @param extraArguments what goes after the standard arguments
     * @return the fixers Sheriff said it could not apply, and why
     * @throws FixNotRun when the {@code fix} did not run
     */
    private List<String> runFix(List<String> extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                FIX_COMMAND, APPLY_FLAG,
                URI_FLAG, URI_ARGUMENT,
                COMPONENT_FLAG, component));
        arguments.addAll(extraArguments);
        ProcessOutcome outcome = container.run(arguments);
        if (!outcome.ran()) {
            throw new FixNotRun(outcome.failure());
        }
        if (outcome.standardOutput().strip().isEmpty() && !outcome.standardError().strip().isEmpty()) {
            throw new FixNotRun(outcome.standardError().strip());
        }
        return FixerFailures.in(outcome.standardOutput());
    }
}
