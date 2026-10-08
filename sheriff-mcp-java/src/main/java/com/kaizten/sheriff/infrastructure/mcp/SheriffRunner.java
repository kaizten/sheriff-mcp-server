package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.docker.FixerFailures;
import com.kaizten.sheriff.infrastructure.docker.MountLock;
import com.kaizten.sheriff.infrastructure.docker.SheriffContainer;
import com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Runs Sheriff for the two tools that need it, through the agent's own
 * analyzer and container launcher.
 *
 * <p>It used to be a second analyzer over the same process seam, because the
 * checks this server needed first (a run Sheriff did not record is not a
 * clean component; a rejected profile is reported in Sheriff's words) were
 * not in the agent's. Two analyzers drifted exactly as copies do: the CLI, the
 * hooks and the Maven plugin read a bad profile as "Sheriff isn't
 * responding", and could take an empty, unrecorded run for a pass. Those
 * checks now live in {@link SheriffDockerAnalyzer}, and this class only turns
 * an untrustworthy result into the exception the tools report.
 */
public final class SheriffRunner {

    private static final String FIX_SUBCOMMAND = "fix";
    private static final String APPLY_FLAG = "-f";
    private static final String URI_FLAG = "--uri";
    private static final String URI_ARGUMENT = "file:/data";
    private static final String COMPONENT_FLAG = "--component";
    private static final String FIXERS_FLAG = "--fixers";
    private static final int ONLY_ONE = 1;
    private static final int NO_ERRORS = 0;
    private static final int FIRST_ROUND = 1;
    private static final int MAXIMUM_ROUNDS = 3;
    private static final String CODE_SEPARATOR = ",";

    private final ProcessRunner processes;
    private final String image;
    private final Path repository;
    private final Duration timeout;
    private final SheriffContainer container;
    private final Optional<Path> exportDirectory;

    /**
     * Wires the runner to one Sheriff image and one repository.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to run
     * @param repository the repository to mount
     * @param timeout how long one invocation is given
     */
    public SheriffRunner(ProcessRunner processes, String image, Path repository, Duration timeout) {
        this(processes, image, repository, timeout, Optional.empty());
    }

    /**
     * Every field, for the constructor above and for {@link #exportingTo}.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to run
     * @param repository the repository to mount
     * @param timeout how long one invocation is given
     * @param exportDirectory where each analysis copies Sheriff's JSON, if
     *     anywhere
     */
    private SheriffRunner(
            ProcessRunner processes, String image, Path repository, Duration timeout, Optional<Path> exportDirectory) {
        this.processes = processes;
        this.image = image;
        this.repository = repository;
        this.timeout = timeout;
        this.container = new SheriffContainer(processes, image, repository, timeout);
        this.exportDirectory = exportDirectory;
    }

    /**
     * The same runner, copying Sheriff's findings and tracked files into a
     * directory after every analysis. A repair analyzes twice, so the copies
     * left are the ones after it.
     *
     * @param directory where to copy them
     * @return that runner
     */
    public SheriffRunner exportingTo(Path directory) {
        return new SheriffRunner(processes, image, repository, timeout, Optional.of(directory));
    }

    /**
     * One {@code sheriff test} run, cleaning up its state afterwards.
     *
     * @param component the folder to analyze
     * @param profile the {@code --test} profile
     * @return what it found
     * @throws SheriffUnavailableException when the run could not be trusted
     */
    public AnalysisResult test(String component, String profile) {
        return test(component, profile, false);
    }

    /**
     * One {@code sheriff test} run.
     *
     * @param component the folder to analyze
     * @param profile the {@code --test} profile
     * @param keepState whether to leave Sheriff's state files in place, which
     *     a following {@code fix} needs to have anything to repair
     * @return what it found
     * @throws SheriffUnavailableException when the run could not be trusted
     */
    public AnalysisResult test(String component, String profile, boolean keepState) {
        return test(component, profile, keepState, false);
    }

    /**
     * The same, exporting into a folder named after the profile when it is
     * one of several, so that none overwrites another's.
     *
     * @param component the folder to analyze
     * @param profile the {@code --test} profile
     * @param keepState whether to leave Sheriff's state for a {@code fix}
     * @param oneOfSeveral whether other profiles are exported beside it
     * @return what Sheriff found
     */
    private AnalysisResult test(String component, String profile, boolean keepState, boolean oneOfSeveral) {
        SheriffDockerAnalyzer analyzer =
                new SheriffDockerAnalyzer(processes, image, profile, component, repository, timeout, !keepState);
        AnalysisResult result = exportDirectory
                .map(directory -> analyzer.exportingTo(oneOfSeveral ? directory.resolve(profile) : directory))
                .orElse(analyzer).analyze();
        if (result.error()) {
            throw new SheriffUnavailableException(result.message());
        }
        return result;
    }

    /**
     * Sheriff repairing its own findings: {@code test} (keeping state) →
     * {@code fix -f}, optionally for some rules → {@code test} again.
     *
     * <p>With no rule named it is every repair Sheriff has: the default set
     * and the fixer of every rule found, again while a round lowers the count,
     * up to three rounds, as one fixer's change can be another's finding. The
     * same code always ends in the same state, whoever calls it.
     *
     * @param component the folder to repair
     * @param profile the {@code --test} profile
     * @param referenceCode the rules to repair, separated by commas, or empty
     *     for every one Sheriff can repair
     * @return the findings before and after
     * @throws SheriffUnavailableException when either run could not be trusted
     */
    public FixRun fix(String component, String profile, String referenceCode) {
        List<String> failures = new ArrayList<>();
        AnalysisResult before = analyzeAndRepair(component, profile, referenceCode, failures);
        AnalysisResult after = test(component, profile, false);
        AnalysisResult previous = before;
        for (int round = FIRST_ROUND; referenceCode.isEmpty() && round < MAXIMUM_ROUNDS
                && after.total() > NO_ERRORS && after.total() < previous.total(); round++) {
            previous = after;
            analyzeAndRepair(component, profile, referenceCode, failures);
            after = test(component, profile, false);
        }
        return new FixRun(before, after, failures.stream().distinct().toList());
    }

    /**
     * The analysis {@code fix} reads, then the repair, with Sheriff's state
     * removed afterwards whichever of the two failed.
     *
     * @param component the folder to repair
     * @param profile the {@code --test} profile
     * @param referenceCode the single rule to repair, empty for the default set
     * @param failures where to add the fixers Sheriff could not apply
     * @return what was wrong before the repair
     */
    private AnalysisResult analyzeAndRepair(
            String component, String profile, String referenceCode, List<String> failures) {
        return MountLock.holding(repository,
                () -> analyzeAndRepairLocked(component, profile, referenceCode, failures));
    }

    /**
     * The same, with the mount's lock held, so no other run replaces the
     * state {@code fix} reads. With several profiles, each is analyzed and
     * repaired in turn: {@code fix} reads the state of the {@code test} just
     * before it, so one repair after all of them would see only the last.
     *
     * @param component the folder to repair
     * @param profile the {@code --test} profile
     * @param referenceCode the single rule to repair, empty for the default set
     * @param failures where to add the fixers Sheriff could not apply
     * @return what was wrong before the repair
     */
    private AnalysisResult analyzeAndRepairLocked(
            String component, String profile, String referenceCode, List<String> failures) {
        try {
            List<AnalysisResult> before = new ArrayList<>();
            List<String> profiles = Rules.profilesIn(profile);
            for (String single : profiles) {
                AnalysisResult analysis = test(component, single, true, profiles.size() > ONLY_ONE);
                before.add(analysis);
                failures.addAll(runFix(component, referenceCode));
                String found = codesOf(analysis);
                if (referenceCode.isEmpty() && !found.isEmpty()) {
                    failures.addAll(runFix(component, found));
                }
            }
            return AnalysisResult.merged(before);
        } finally {
            SheriffDockerAnalyzer.removeStateFilesIn(repository);
        }
    }

    /**
     * Every rule an analysis found, as {@code --fixers} takes them.
     *
     * <p>Sheriff's default set leaves fixers out, and a repair asked for with
     * no rule used to stop there and say to call it again with the rules
     * still marked repairable: a step one model took and another did not.
     * Naming every rule found runs every fixer there is for them; a rule with
     * no fixer in the list is ignored, measured on 1 October.
     *
     * @param analysis the analysis
     * @return the rules, comma-separated, in the order first found
     */
    private static String codesOf(AnalysisResult analysis) {
        return analysis.errors().stream().map(SheriffFinding::referenceCode).filter(code -> !code.isEmpty())
                .distinct().collect(Collectors.joining(CODE_SEPARATOR));
    }

    /**
     * One {@code fix} invocation, tolerant of the exit code Sheriff always
     * returns for this subcommand regardless of what happened.
     *
     * @param component the folder to repair
     * @param referenceCode the single rule to repair, empty for the default set
     * @return the fixers Sheriff said it could not apply, and why
     */
    private List<String> runFix(String component, String referenceCode) {
        List<String> arguments = new ArrayList<>(List.of(
                FIX_SUBCOMMAND, APPLY_FLAG,
                URI_FLAG, URI_ARGUMENT,
                COMPONENT_FLAG, component));
        if (!referenceCode.isEmpty()) {
            arguments.add(FIXERS_FLAG);
            arguments.add(referenceCode);
        }
        ProcessOutcome outcome = container.run(arguments);
        if (!outcome.ran()) {
            throw new SheriffUnavailableException(outcome.failure());
        }
        if (outcome.standardOutput().strip().isEmpty() && !outcome.standardError().strip().isEmpty()) {
            throw new SheriffUnavailableException(outcome.standardError().strip());
        }
        return FixerFailures.in(outcome.standardOutput());
    }
}
