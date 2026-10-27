package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.DeterministicPolicy;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
 * repaired again, until a round repairs nothing ({@code SHERIFF_REPAIR_ROUNDS}
 * sets a limit). It always ends: a round runs only when the count before it
 * dropped, and a count cannot drop for ever. Each round's analysis measures the one
 * before, so the last analysis, the one that repaired nothing, is the state
 * the repair left; a fixer that exits 0 having done nothing is caught there.
 * With rules named, only their fixers run, once.
 *
 * <p>Findings it is told to withhold are taken out of the state before every
 * {@code fix}, so no fixer makes their repair again: one whose repair was
 * undone, as it broke the build, was made again by every call.
 */
public final class SheriffRepair {
    /**
     * No limit on the rounds: they run until one repairs nothing, which they
     * reach, as a round runs only when the count before it dropped.
     */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    private static final String FIX_COMMAND = "fix";
    private static final String APPLY_FLAG = "-f";
    private static final String FIXERS_FLAG = "--fixers";
    private static final String TYPE_FLAG = "--type";
    private static final String DETERMINISTIC_TYPE = "deterministic";
    private static final String USAGE_BANNER = "Usage: Sheriff";
    private static final String REFUSED_ARGUMENTS = "Sheriff refused the arguments of fix and gave no reason";
    private static final String URI_FLAG = "--uri";
    private static final String COMPONENT_FLAG = "--component";
    private static final String URI_ARGUMENT = "file:/data";
    private static final String CODE_SEPARATOR = ",";
    private static final int NO_ERRORS = 0;
    private static final int FIRST_ROUND = 1;
    private static final int NO_ROUNDS = 0;
    private static final int ONE_ROUND = 1;
    private static final int NAMED_FIXER_ROUNDS = 1;

    private static final int FIRST_PROFILE = 0;

    private final SheriffContainer container;
    private final SheriffDockerAnalyzer analyzer;
    private final String component;
    private int roundLimit = NO_LIMIT;
    private Predicate<SheriffFinding> withheld = finding -> false;

    /**
     * Wires the repair to one component.
     *
     * @param container what runs Sheriff's {@code fix}
     * @param analyzer an analyzer of the component, which may export what
     *     it reads; it is made to keep Sheriff's state, which {@code fix}
     *     needs, and the state is removed here
     * @param component the folder to repair
     */
    public SheriffRepair(SheriffContainer container, SheriffDockerAnalyzer analyzer, String component) {
        this.container = container;
        this.analyzer = analyzer;
        this.component = component;
    }

    /**
     * The same repair, with a limit on its rounds.
     *
     * @param limit the most rounds of fixers it runs, one or more;
     *     {@link #NO_LIMIT} for none
     * @return this repair
     */
    public SheriffRepair withRounds(int limit) {
        this.roundLimit = limit;
        return this;
    }

    /**
     * The same repair, leaving some findings to no fixer.
     *
     * @param findings which findings no fixer may repair
     * @return this repair
     */
    public SheriffRepair withholding(Predicate<SheriffFinding> findings) {
        this.withheld = findings;
        return this;
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
        return MountLock.holding(analyzer.lockKey(), () -> repair(rules, Optional.empty(), roundLimit));
    }

    /**
     * The fixers of the rules named, and no other, once.
     *
     * @param codes the rules, separated by commas
     * @return what it did, measured
     */
    public RepairOutcome onlyRules(String codes) {
        return MountLock.holding(analyzer.lockKey(), () -> repair(List.of(), Optional.of(codes), NAMED_FIXER_ROUNDS));
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
        int repairRounds = NO_ROUNDS;
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
                    List<SheriffFinding> repairable = analysis.errors().stream().filter(withheld.negate()).toList();
                    if (round <= rounds && helping && repairable(repairable, rules)) {
                        single.withholdFromState(withheld);
                        codes.addAll(DeterministicPolicy.fixableCodes(repairable, rules));
                        failures.addAll(named.isPresent() ? runFix(List.of(FIXERS_FLAG, named.get()))
                                : everyFixerFor(repairable));
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
            repairRounds += repaired ? ONE_ROUND : NO_ROUNDS;
            round++;
        } while (repaired);
        return new RepairOutcome(before, after, List.copyOf(codes), failures.stream().distinct().toList(),
                Optional.empty(), repairRounds);
    }

    /**
     * Sheriff's default set, then the fixer of every rule one analysis
     * found, while the state that analysis left is still there to read.
     *
     * @param errors the errors under one profile that a fixer may repair
     * @return the fixers that could not be applied
     */
    private List<String> everyFixerFor(List<SheriffFinding> errors) {
        List<String> failures = new ArrayList<>(runFix(List.of()));
        String found = errors.stream().map(SheriffFinding::referenceCode).filter(code -> !code.isEmpty())
                .distinct().collect(Collectors.joining(CODE_SEPARATOR));
        if (!found.isEmpty()) {
            failures.addAll(runFix(List.of(FIXERS_FLAG, found)));
        }
        return failures;
    }

    /**
     * Whether errors leave anything for Sheriff's fixers: some, and, when
     * there is a catalog, a rule among them it knows a fixer for.
     *
     * @param errors the errors under one profile that a fixer may repair
     * @param rules the catalog, empty when there is none
     * @return {@code true} when the fixers are worth running
     */
    private static boolean repairable(List<SheriffFinding> errors, List<SheriffRule> rules) {
        return errors.size() > NO_ERRORS
                && (rules.isEmpty() || !DeterministicPolicy.fixableCodes(errors, rules).isEmpty());
    }

    /**
     * One {@code fix} of Sheriff's own fixers, whose exit code says nothing.
     * What it says is on stdout, and a run that printed nothing there but a
     * reason on stderr, or only its usage banner, or never started, did not
     * repair.
     *
     * <p>The fixers are always the deterministic ones: with credentials
     * within reach {@code fix} would also run its AI fixer, and an argument
     * it refuses (the help lists {@code DETERMINISTIC}, which it does not
     * take) prints the banner and exits 0.
     *
     * @param extraArguments what goes after the standard arguments
     * @return the fixers Sheriff said it could not apply, and why
     * @throws FixNotRun when the {@code fix} did not run
     */
    private List<String> runFix(List<String> extraArguments) {
        List<String> arguments = new ArrayList<>(List.of(
                FIX_COMMAND, APPLY_FLAG,
                TYPE_FLAG, DETERMINISTIC_TYPE,
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
        if (outcome.standardOutput().stripLeading().startsWith(USAGE_BANNER)) {
            String reason = SheriffDockerAnalyzer.reasonOf(outcome.standardError());
            throw new FixNotRun(reason.isEmpty() ? REFUSED_ARGUMENTS : reason);
        }
        return FixerFailures.in(outcome.standardOutput());
    }
}
