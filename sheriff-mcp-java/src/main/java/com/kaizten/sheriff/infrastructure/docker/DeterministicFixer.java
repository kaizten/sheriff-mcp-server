package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.port.AutomaticRepair;
import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs Sheriff's own fixers before any model is asked for anything.
 *
 * <p>Eighty-five of Sheriff's rules ship a deterministic fixer — a small jar
 * or a Python script. Spending tokens on what a script repairs for free is
 * waste, so this pass goes first and only what survives it reaches the AI.
 *
 * <p>The repair itself is {@link SheriffRepair}, the same sequence
 * {@code sheriff_fix} runs with no rule named, so the MCP, the loop and the
 * Maven goal leave the same code behind. What this adds is the report the
 * loop and {@code sheriff:fix} print: which rules the catalog knows a fixer
 * for among those handed over, and what could not run.
 */
public final class DeterministicFixer implements AutomaticRepair {

    private static final int NO_ERRORS = 0;

    private final RuleCatalog catalog;
    private final SheriffRepair repair;

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
        this.catalog = catalog;
        this.repair = new SheriffRepair(new SheriffContainer(processes, image, repositoryRoot, timeout), analyzer,
                component, repositoryRoot);
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
        List<SheriffRule> rules = catalog.allRules();
        RepairOutcome outcome = repair.everyFixer(rules);
        if (outcome.failure().isPresent()) {
            return DeterministicFixReport.unavailable(outcome.failure().get());
        }
        int errorsBefore = outcome.before().total();
        if (rules.isEmpty() && errorsBefore > NO_ERRORS) {
            return DeterministicFixReport.defaultsOnly(errorsBefore);
        }
        if (outcome.codes().isEmpty()) {
            return DeterministicFixReport.nothingToDo(errorsBefore);
        }
        return DeterministicFixReport.applied(errorsBefore, outcome.codes(), outcome.fixerFailures());
    }
}
