package com.kaizten.sheriff.application.service;

import com.kaizten.sheriff.application.RunState;
import com.kaizten.sheriff.application.usecase.FixCodeUntilClean;
import com.kaizten.sheriff.domain.BatchPolicy;
import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.port.AutomaticRepair;
import com.kaizten.sheriff.domain.port.CodeAnalyzer;
import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.port.TestRunner;
import com.kaizten.sheriff.domain.port.VersionControl;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides when to stop, retry, repair, or give up.
 *
 * <p>Depends only on the ports, never on how they are implemented, and imports
 * nothing that reads configuration: everything it needs to know about the
 * environment arrives through its constructor.
 *
 * <p>Before the first iteration, and inside the run's own branch, the
 * analyzer's own fixers get their turn: what a script repairs for free never
 * reaches a model, and it lands in a commit of its own.
 *
 * <p>Each iteration: analyze, stop if done, otherwise build a prompt scoped to
 * a bounded batch of the flagged files and call the fixer, then ask version
 * control which files actually changed. An out-of-scope change cuts the run
 * without committing rather than guessing what to revert. Once Sheriff is
 * clean, the project's own tests are the last gate — if a fix satisfied Sheriff
 * but broke a caller outside its scope, one unrestricted repair pass gets a
 * shot at it before the run is called a failure.
 */
public final class FixLoop implements FixCodeUntilClean {

    /**
     * A prompt section, or nothing at all.
     *
     * <p>An empty section still costs a heading that promises something and
     * then delivers none, which is worse than silence.
     *
     * @param body what the section says, possibly empty
     * @return the section, or the empty string
     */
    private static String section(String body) {
        return body.isEmpty() ? "" : System.lineSeparator() + body + System.lineSeparator();
    }

    private static final String PROMPT_TEMPLATE = """
            Fix the following style/documentation errors that Sheriff reports in this repository.

            These are ALL the Sheriff rules currently in force here, not just the ones
            that caused the errors below. Respect all of them while fixing, so you
            don't fix one and break another by not knowing about it:
            ---
            %s
            ---

            Errors to fix in this pass (%d total):
            %s
            %s
            Instructions:
            - Fix exactly these errors, applying the full set of rules above.
            - Modify ONLY the files that appear in the error list. Don't touch any
              other file in the repository, even if you notice something else you'd
              like to fix -- that's a job for another pass.
            - No need to verify with Sheriff yourself when you're done -- the process
              that invoked you checks that, outside this conversation.
            """;

    private static final String REPAIR_PROMPT_TEMPLATE = """
            Sheriff is satisfied with this code (0 errors), but running the project's
            own test suite just failed:
            ---
            %s
            ---

            This usually means an earlier fix (for example, renaming a method) broke a
            caller in a file Sheriff never flagged as having an error, so it was
            outside the scope of that fix. This time, you may edit whatever file is
            actually necessary to fix this failure -- not just files Sheriff flagged.

            Fix the real problem so the tests pass again, without reintroducing any of
            the style/documentation/architecture issues Sheriff was checking for.
            """;

    private static final int OUTPUT_EXCERPT_LIMIT = 500;
    private static final String BRANCH_PREFIX = "sheriff-agent";
    private static final String ITERATION_LABEL = "iteration-%d";
    private static final String COMMIT_MESSAGE = "sheriff-agent: iteration %d (%d errors before this pass)";
    private static final String REPAIR_COMMIT_MESSAGE = "sheriff-agent: repair pass (fixing a verification failure)";
    private static final String REPAIR_LABEL = "repair";
    private static final String AUTOMATIC_REPAIR_COMMIT_MESSAGE =
            "sheriff-agent: Sheriff's own fixers (no model involved)";
    private static final String BULLET = "- ";
    private static final String NEWLINE = "\n";
    private static final String COMMA = ", ";
    private static final String EMPTY = "";
    private static final int NO_ERRORS = 0;
    private static final AutomaticRepair NO_AUTOMATIC_REPAIR = () -> EMPTY;

    private final CodeAnalyzer analyzer;
    private final CodeFixer fixer;
    private final VersionControl versionControl;
    private final TestRunner testRunner;
    private final AutomaticRepair automaticRepair;
    private final String rulesContext;
    private final List<SheriffRule> rules;
    private final LoopSettings settings;

    /**
     * Wires the loop to its collaborators, with no automatic repairs before
     * the first iteration.
     *
     * @param analyzer what reports the errors
     * @param fixer what fixes them
     * @param versionControl the branch, the commits, and the scope check
     * @param testRunner the target project's own tests
     * @param rulesContext what to tell the fixer about the rules in force
     * @param rules the catalog itself
     * @param settings the iteration cap, git safety and batch size
     */
    public FixLoop(
            CodeAnalyzer analyzer,
            CodeFixer fixer,
            VersionControl versionControl,
            TestRunner testRunner,
            String rulesContext,
            List<SheriffRule> rules,
            LoopSettings settings) {
        this(analyzer, fixer, versionControl, testRunner, NO_AUTOMATIC_REPAIR, rulesContext, rules, settings);
    }

    /**
     * Wires the loop to its collaborators.
     *
     * @param analyzer what reports the errors
     * @param fixer what fixes them
     * @param versionControl the branch, the commits, and the scope check
     * @param testRunner the target project's own tests
     * @param automaticRepair what repairs the analyzer can make by itself,
     *     run once inside the run's branch before the first iteration
     * @param rules the catalog itself, for the one thing the blurb cannot
     *     carry: what Sheriff would <em>write</em> for a rule. Domain data, so
     *     taking it here keeps this service as free of the outside world as it
     *     was
     * @param rulesContext what to tell the fixer about the rules in force,
     *     injected rather than read from configuration
     * @param settings the iteration cap, git safety and batch size
     */
    public FixLoop(
            CodeAnalyzer analyzer,
            CodeFixer fixer,
            VersionControl versionControl,
            TestRunner testRunner,
            AutomaticRepair automaticRepair,
            String rulesContext,
            List<SheriffRule> rules,
            LoopSettings settings) {
        this.analyzer = analyzer;
        this.fixer = fixer;
        this.versionControl = versionControl;
        this.testRunner = testRunner;
        this.automaticRepair = automaticRepair;
        this.rulesContext = rulesContext;
        this.rules = List.copyOf(rules);
        this.settings = settings;
    }

    /**
     * Runs the full loop.
     *
     * <p>A null iteration cap means "size it automatically": the real cap is
     * only knowable once the first analysis says how many files need fixing, so
     * it is computed inside the first iteration rather than before the loop.
     *
     * @return what happened, including why it stopped
     */
    @Override
    public RunSummary execute() {
        RunState state = new RunState(settings.maxIterations());
        if (settings.useGitSafety()) {
            String branch = versionControl.createWorkingBranch(BRANCH_PREFIX);
            state.workingBranch(branch);
            System.out.printf(
                    "Working on dedicated branch '%s' — every iteration with changes becomes its own commit.%n%n",
                    branch);
        }
        applyAutomaticRepair();
        while (state.maxIterations() == null || state.iteration() <= state.maxIterations()) {
            RunSummary outcome = runIteration(state);
            if (outcome != null) {
                return outcome;
            }
            state.nextIteration();
        }
        state.iterationsExhausted();
        RunSummary lastPassReachedZero = checkAfterLastPass(state);
        if (lastPassReachedZero != null) {
            return lastPassReachedZero;
        }
        System.out.printf("%nReached the max of %d iterations without getting down to 0 errors.%n", state.maxIterations());
        if (!state.nothingParked()) {
            System.out.printf("Files parked along the way (no pass improved them): %s%n",
                    String.join(COMMA, state.parkedFiles()));
        }
        return finish(state, false, StopReason.ITERATIONS_EXHAUSTED);
    }

    /**
     * Analyzes once more after the last pass the cap allows, because that
     * pass may be the one that reached 0 errors: without this the loop
     * reported the goal as not reached for code that was clean, and never
     * ran the tests or the repair pass a clean result is owed.
     *
     * @param state the run so far, with its iterations already at the cap
     * @return how the run ends when the last pass reached 0 errors, or
     *     {@code null} when it did not, or Sheriff could not say
     */
    private RunSummary checkAfterLastPass(RunState state) {
        AnalysisResult result = analyzer.analyze();
        if (result.error() || result.total() != NO_ERRORS) {
            return null;
        }
        System.out.printf("%nThe last pass the cap allowed brought it to 0 errors.%n");
        return verifyAndFinish(state);
    }

    /**
     * Lets the analyzer repair what it can by itself, and commits that on its
     * own so a reviewer can tell it from what a model wrote.
     */
    private void applyAutomaticRepair() {
        String outcome = automaticRepair.apply();
        if (outcome.isEmpty()) {
            return;
        }
        System.out.println(outcome);
        if (settings.useGitSafety()) {
            versionControl.commitIfChanges(AUTOMATIC_REPAIR_COMMIT_MESSAGE);
        }
    }

    /**
     * One pass of the loop: analyze, then stop, park, or hand a batch to the
     * fixer.
     *
     * @param state the run's mutable bookkeeping, updated in place
     * @return the summary to return when this iteration ends the run,
     *     {@code null} when the loop should carry on
     */
    private RunSummary runIteration(RunState state) {
        System.out.printf("--- Iteration %s ---%n", state.label());
        AnalysisResult result = analyzer.analyze();
        if (result.error()) {
            System.out.printf("Sheriff isn't responding: %s%n", result.message());
            return finish(state, false, StopReason.INFRA_ERROR);
        }
        int total = result.total();
        System.out.printf("Current errors: %d%n", total);
        if (total == NO_ERRORS) {
            return verifyAndFinish(state);
        }
        if (state.maxIterations() == null) {
            state.maxIterations(BatchPolicy.estimateMaxIterations(result.filesWithErrors().size(), settings.maxFilesPerBatch()));
            System.out.printf(
                    "  sizing the iteration cap to %d automatically, based on how many files have errors%n",
                    state.maxIterations());
        }
        if (state.hasHistory() && total >= state.lastTotal()) {
            RunSummary stalled = park(state, total);
            if (stalled != null) {
                return stalled;
            }
        } else if (state.hasHistory() && settings.maxIterations() == null) {
            extendCap(state, result.filesWithErrors().size());
        }
        state.recordTotal(total);
        List<SheriffFinding> batch = BatchPolicy.selectBatch(result.errors(), settings.maxFilesPerBatch(), state.parkedFiles());
        if (batch.isEmpty()) {
            System.out.printf(
                    "%nNothing left to try: every file that still has errors (%d of them) was already "
                            + "attempted without improvement. Cutting for manual review.%n", total);
            return finish(state, false, StopReason.STALLED);
        }
        return runFixPass(state, result, batch, total);
    }

    /**
     * Invokes the fixer on one batch, checks it stayed inside its scope, and
     * commits what it changed.
     *
     * @param state the run's bookkeeping, told which files this pass covered
     * @param result the analysis this batch came from
     * @param batch the findings the fixer is asked to fix
     * @param total how many errors there were before this pass
     * @return the summary to return when this pass ends the run, {@code null}
     *     when the loop should carry on
     */
    private RunSummary runFixPass(RunState state, AnalysisResult result, List<SheriffFinding> batch, int total) {
        Set<String> allowedFiles = filesOf(batch);
        state.lastBatchFiles(allowedFiles);
        if (batch.size() < result.errors().size()) {
            reportBatching(state, result, batch, total, allowedFiles);
        }
        FixRequest request = FixRequest.scoped(
                String.format(PROMPT_TEMPLATE, rulesContext, batch.size(), describeAll(batch),
                        section(Rules.acceptedCodeContext(batch, rules))),
                String.format(ITERATION_LABEL, state.iteration()),
                allowedFiles);
        System.out.println("  invoking the fixer...");
        FixResult fixResult = fixer.fix(request);
        if (!fixResult.ok()) {
            System.out.printf("  fixer reported failure: %s%n", excerpt(fixResult.output()));
            return finish(state, false, StopReason.FIXER_FAILED);
        }
        if (settings.useGitSafety()) {
            Map<String, String> renamedFrom = versionControl.renamedFrom();
            List<String> outOfScope = versionControl.modifiedFiles().stream()
                    .filter(file -> !allowedFiles.contains(file))
                    .filter(file -> !allowedFiles.contains(renamedFrom.getOrDefault(file, file)))
                    .toList();
            if (!outOfScope.isEmpty()) {
                System.out.printf(
                        "%nThe fixer modified files outside what Sheriff had flagged: %s. "
                                + "Cutting without committing — review manually before continuing.%n", outOfScope);
                return finish(state, false, StopReason.OUT_OF_SCOPE);
            }
            versionControl.commitIfChanges(String.format(COMMIT_MESSAGE, state.iteration(), total));
        }
        return null;
    }

    /**
     * The last gate once Sheriff is clean: the target project's own tests, with
     * one unrestricted repair pass if they fail.
     *
     * @param state the run's bookkeeping, told whether a repair pass was used
     * @return the summary that ends the run, successful or not
     */
    private RunSummary verifyAndFinish(RunState state) {
        System.out.printf("%nSheriff reports no errors — checking the project's own tests still pass...%n");
        VerificationResult verification = testRunner.run();
        if (!verification.ran()) {
            System.out.printf("There are no tests to run (%s), so only Sheriff checked this code — goal achieved.%n",
                    verification.output().strip());
            return finish(state, true, StopReason.SUCCESS, null);
        }
        if (verification.ok()) {
            System.out.println("Tests pass too — goal achieved.");
            return finish(state, true, StopReason.SUCCESS);
        }
        System.out.printf(
                "%nSheriff is happy, but the project's own tests failed -- the fix broke something "
                        + "Sheriff can't see.%n%s%n", verification.output());
        state.markRepairPassUsed();
        if (attemptRepair(verification.output())) {
            return finish(state, true, StopReason.SUCCESS_AFTER_REPAIR);
        }
        System.out.println("Repair pass didn't fix it either. Not reporting success.");
        return finish(state, false, StopReason.REPAIR_FAILED);
    }

    /**
     * Grows an automatic cap after a pass that lowered the errors, when the
     * files still with errors need more passes than are left.
     *
     * @param state the run so far
     * @param filesWithErrors how many files still have errors
     */
    private void extendCap(RunState state, int filesWithErrors) {
        int extended = BatchPolicy.extendedMaxIterations(state.maxIterations(), state.iteration() - 1,
                filesWithErrors, settings.maxFilesPerBatch(), state.firstEstimate());
        if (extended > state.maxIterations()) {
            System.out.printf("  extending the iteration cap to %d: the last pass lowered the errors, and %d file(s)"
                    + " still have them (at most %d, %d times the first estimate)%n", extended, filesWithErrors,
                    state.firstEstimate() * BatchPolicy.CEILING_FACTOR, BatchPolicy.CEILING_FACTOR);
            state.maxIterations(extended);
        }
    }

    /**
     * Sets the last pass's files aside after it failed to bring the total down,
     * so the next pass moves on to the files queued behind them.
     *
     * <p>A pass with no file of its own to park has nothing left to move on to,
     * which is the genuine stall the run is cut for.
     *
     * @param state the run's bookkeeping, whose parked list this grows
     * @param total how many errors the latest analysis found
     * @return the summary to return when the run is stalled, {@code null} when
     *     there are still files to try
     */
    private RunSummary park(RunState state, int total) {
        if (state.lastBatchFiles().isEmpty()) {
            System.out.printf(
                    "%nNo progress: %d -> %d errors, and the last pass had no file of its own to park. "
                            + "Cutting for manual review.%n", state.lastTotal(), total);
            return finish(state, false, StopReason.STALLED);
        }
        state.park(state.lastBatchFiles());
        System.out.printf(
                "  no progress (%d -> %d errors): parking %s and moving on to the other files%n",
                state.lastTotal(), total, String.join(COMMA, new TreeSet<>(state.lastBatchFiles())));
        return null;
    }

    /**
     * Prints what this pass covers and what it leaves for later, so a long run
     * reads as progress rather than as a stuck total.
     *
     * @param state the run's bookkeeping, asked which files are parked
     * @param result the analysis this batch came from
     * @param batch the findings this pass covers
     * @param total how many errors there are in all
     * @param allowedFiles the files this pass may touch
     */
    private void reportBatching(
            RunState state, AnalysisResult result, List<SheriffFinding> batch, int total, Set<String> allowedFiles) {
        long parkedErrors = result.errors().stream().filter(f -> state.isParked(f.file())).count();
        long queued = total - batch.size() - parkedErrors;
        String parkedNote = parkedErrors == NO_ERRORS ? EMPTY : String.format(", %d error(s) parked", parkedErrors);
        System.out.printf(
                "  batching: fixing %d file(s) this pass, %d error(s) left for later%s%n",
                allowedFiles.size(), queued, parkedNote);
    }

    /**
     * The one repair pass a run gets, with no file-scope restriction, when
     * Sheriff is clean but the project's tests are not.
     *
     * <p>The scope has to be open: the caller that broke is by definition in a
     * file Sheriff never flagged, so the restriction that made the break is the
     * one thing that cannot also fix it. The result is only accepted when both
     * gates are green again.
     *
     * @param verificationOutput what the failing test run printed
     * @return whether the tests pass and the analyzer is still clean
     */
    private boolean attemptRepair(String verificationOutput) {
        System.out.println("  attempting one repair pass (unrestricted file scope, using the test failure)...");
        FixResult fixResult = fixer.fix(FixRequest.unrestricted(
                String.format(REPAIR_PROMPT_TEMPLATE, verificationOutput), REPAIR_LABEL));
        if (!fixResult.ok()) {
            System.out.printf("  repair pass failed to run: %s%n", excerpt(fixResult.output()));
            return false;
        }
        AnalysisResult result = analyzer.analyze();
        if (result.error()) {
            System.out.printf("  Sheriff isn't responding after the repair pass: %s%n", result.message());
            return false;
        }
        if (result.total() != NO_ERRORS) {
            System.out.println("  repair pass left the analyzer unhappy again — giving up.");
            return false;
        }
        VerificationResult verification = testRunner.run();
        if (!verification.ok()) {
            System.out.printf("  repair pass did not fix the tests either — giving up.%n%s%n", verification.output());
            return false;
        }
        if (settings.useGitSafety()) {
            versionControl.commitIfChanges(REPAIR_COMMIT_MESSAGE);
        }
        System.out.println("  repair pass fixed it — tests pass and the analyzer is still clean.");
        return true;
    }

    /**
     * Closes the run, turning the bookkeeping into the summary a caller reads.
     *
     * @param state the run's bookkeeping
     * @param ok whether the run reached its goal
     * @param reason why it stopped
     * @return that summary
     */
    private RunSummary finish(RunState state, boolean ok, StopReason reason) {
        return finish(state, ok, reason, testsAfter(reason));
    }

    /**
     * The summary that ends the run, with the verdict on the tests already
     * known.
     *
     * @param state the run's bookkeeping
     * @param ok whether the goal was reached
     * @param reason why the run stopped
     * @param testsPassed whether the project's own tests pass, {@code null}
     *     when there were none to run
     * @return that summary
     */
    private RunSummary finish(RunState state, boolean ok, StopReason reason, Boolean testsPassed) {
        return new RunSummary(ok, reason, state.iteration(), state.maxIterations(), state.repairPassUsed(),
                state.parkedFilesAsList(), state.workingBranch(), testsPassed);
    }

    /**
     * Whether the project's own tests pass on what the run left behind.
     *
     * <p>A run that reached zero errors has already run them, and one whose
     * repair pass failed already knows they fail. Every other way of stopping
     * used to say nothing about them, and a run cut short after Sheriff's own
     * fixers renamed something could leave a branch that no longer compiled
     * behind a summary that only said "stalled".
     *
     * @param reason why the run stopped
     * @return the verdict, {@code null} when there are no tests to run
     */
    private Boolean testsAfter(StopReason reason) {
        if (reason == StopReason.SUCCESS || reason == StopReason.SUCCESS_AFTER_REPAIR) {
            return true;
        }
        if (reason == StopReason.REPAIR_FAILED) {
            return false;
        }
        System.out.println("  checking the project's own tests on what this run leaves behind...");
        VerificationResult verification = testRunner.run();
        if (!verification.ran()) {
            System.out.println("  there are none to run.");
            return null;
        }
        if (verification.ok()) {
            System.out.println("  they pass.");
        } else {
            System.out.printf("  they FAIL on what this run leaves behind:%n%s%n", excerpt(verification.output()));
        }
        return verification.ok();
    }

    /**
     * The distinct files a batch touches, which is exactly the scope the fixer
     * is allowed and is checked against afterwards.
     *
     * @param batch the findings of one pass
     * @return their files, in the order they were first seen
     */
    private static Set<String> filesOf(List<SheriffFinding> batch) {
        Set<String> files = new LinkedHashSet<>();
        for (SheriffFinding finding : batch) {
            if (!finding.file().isEmpty()) {
                files.add(finding.file());
            }
        }
        return files;
    }

    /**
     * The batch as the bulleted list a fixer prompt carries.
     *
     * @param batch the findings of one pass
     * @return one bullet per finding, or an empty string for an empty batch
     */
    private static String describeAll(List<SheriffFinding> batch) {
        return batch.stream().map(f -> BULLET + f.describe()).reduce((a, b) -> a + NEWLINE + b).orElse(EMPTY);
    }

    /**
     * The head of a command's output, short enough for one console line or two.
     *
     * @param output what the command printed
     * @return that output, truncated when it is long
     */
    private static String excerpt(String output) {
        return output.length() <= OUTPUT_EXCERPT_LIMIT ? output : output.substring(0, OUTPUT_EXCERPT_LIMIT);
    }
}
