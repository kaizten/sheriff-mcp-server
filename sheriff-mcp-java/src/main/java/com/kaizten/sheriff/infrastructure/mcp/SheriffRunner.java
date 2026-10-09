package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.infrastructure.docker.RepairOutcome;
import com.kaizten.sheriff.infrastructure.docker.SheriffContainer;
import com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer;
import com.kaizten.sheriff.infrastructure.docker.SheriffRepair;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
        SheriffDockerAnalyzer analyzer =
                new SheriffDockerAnalyzer(processes, image, profile, component, repository, timeout, !keepState);
        AnalysisResult result = exportDirectory.map(analyzer::exportingTo).orElse(analyzer).analyze();
        if (result.error()) {
            throw new SheriffUnavailableException(result.message());
        }
        return result;
    }

    /**
     * Sheriff repairing its own findings, as {@link SheriffRepair} does for
     * every caller: with no rule named, every repair Sheriff has, in rounds
     * while they help; with rules named, only their fixers, once. The counts
     * before and after are both analyses, never taken from the list of
     * fixers that ran.
     *
     * @param component the folder to repair
     * @param profile the {@code --test} profile
     * @param referenceCode the rules to repair, separated by commas, or empty
     *     for every one Sheriff can repair
     * @return the findings before and after
     * @throws SheriffUnavailableException when an analysis could not be
     *     trusted, or a repair did not run
     */
    public FixRun fix(String component, String profile, String referenceCode) {
        SheriffDockerAnalyzer stateKeeping =
                new SheriffDockerAnalyzer(processes, image, profile, component, repository, timeout, false);
        SheriffDockerAnalyzer analyzer = exportDirectory.map(stateKeeping::exportingTo).orElse(stateKeeping);
        SheriffRepair repair = new SheriffRepair(container, analyzer, component, repository);
        RepairOutcome outcome = referenceCode.isEmpty() ? repair.everyFixer(List.of()) : repair.onlyRules(referenceCode);
        if (outcome.failure().isPresent()) {
            throw new SheriffUnavailableException(outcome.failure().get());
        }
        return new FixRun(outcome.before(), outcome.after(), outcome.fixerFailures());
    }
}
