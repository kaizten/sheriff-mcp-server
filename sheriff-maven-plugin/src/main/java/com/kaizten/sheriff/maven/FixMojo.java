package com.kaizten.sheriff.maven;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.docker.DeterministicFixReport;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Applies the repairs Sheriff can make by itself, and nothing else.
 *
 * <p>No model is involved and no tokens are spent: 89 of the catalog's rules
 * ship a deterministic fixer, and this runs those. What is left afterwards is
 * what actually needs a person or an assistant.
 *
 * <p><strong>It is not bound to a lifecycle phase</strong>, and that is the
 * point rather than an omission. This goal rewrites source files, and a build
 * that edits the code it is building turns a verification into a mutation:
 * the tree that passed is no longer the tree that was committed. Run it by
 * hand, look at the diff, commit it.
 *
 * <p>It fails when it could not repair everything: the module is analyzed
 * again after the repair, and errors left over fail the goal, listed, unless
 * {@code sheriff.failOnRemaining} is {@code false}. "Repaired what it could"
 * and "the module is clean" are different outcomes, and a build that runs
 * this wants to know which one it got.
 *
 * <p>The agent's full fix loop is deliberately not offered here at all. That
 * one calls a model, which would put credentials and non-determinism inside a
 * build; it stays a command line.
 */
@Mojo(name = "fix", threadSafe = true)
public class FixMojo extends SheriffMojo {

    /**
     * Reported when Sheriff itself could not be run.
     */
    private static final String UNAVAILABLE = "Sheriff's own fixers could not run: %s";

    /**
     * Reported when nothing among the findings has a fixer.
     */
    private static final String NOTHING = "Nothing Sheriff can repair by itself among %d error(s).";

    /**
     * Reported when fixers were run. What they actually repaired is the next
     * analysis's answer, reported by {@link #OUTCOME}.
     */
    private static final String APPLIED = "Sheriff's own fixers were run for %d rule(s), free of charge: %s";

    /**
     * Warned for each fixer Sheriff said it could not apply.
     */
    private static final String NOT_APPLIED = "Sheriff could not apply its fixer for %s";

    /**
     * What the repair achieved, measured by analyzing again.
     */
    private static final String OUTCOME = "Errors under %s: %d before the repair, %d after (%d repaired).";

    /**
     * Between the rule ids in that report.
     */
    private static final String SEPARATOR = ", ";

    /**
     * The count that means nothing is left.
     */
    private static final int NONE = 0;

    /**
     * Reported when the analysis after the repair could not run.
     */
    private static final String UNAVAILABLE_AFTER = "Sheriff could not analyze the module after the repair: %s";

    /**
     * Reported when the repair left the module clean.
     */
    private static final String CLEAN = "No errors left under %s.";

    /**
     * Reported when errors are left after the repair.
     */
    private static final String REMAINING =
            "%d error(s) under %s in %s that Sheriff could not repair by itself; they need editing.";

    /**
     * Appended when the build is allowed to continue anyway.
     */
    private static final String TOLERATED = "%s Not failing the build (sheriff.failOnRemaining=false).";

    /**
     * Report what is left without failing the goal.
     */
    @Parameter(property = "sheriff.failOnRemaining", defaultValue = "true")
    boolean failOnRemaining = true;

    /**
     * Serializes repairs across the modules of one build.
     *
     * <p>Sheriff's repair is two runs, not one: an analysis writes its
     * findings to a state file at the root of the mounted directory, and the
     * repair reads them back. Sibling modules of a reactor share that root,
     * so two repairing at once overwrite each other's state, and the failure
     * is silent because each still reports the repairs it was asked to make
     * while one of them has repaired nothing. Measured on a two-module
     * reactor under parallel build: both reported one rule repaired and one
     * module still had its error.
     *
     * <p>This makes the goal safe within a build, which is what a parallel
     * build is. It cannot help two separate Maven processes started against
     * sibling modules at the same time; nothing inside a plugin can.
     */
    private static final Object REPAIR_LOCK = new Object();

    /**
     * Runs the deterministic fixers over this module, then checks what they
     * left.
     *
     * @throws MojoExecutionException when Sheriff could not be run at all
     * @throws MojoFailureException when errors are left and failOnRemaining is set
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skipped()) {
            return;
        }
        ensureImage();
        DeterministicFixReport report;
        synchronized (REPAIR_LOCK) {
            report = repair();
        }
        if (!report.ran()) {
            throw new MojoExecutionException(String.format(UNAVAILABLE, report.failure()));
        }
        describe(report);
        AnalysisResult after = analyze();
        if (after.error()) {
            throw new MojoExecutionException(String.format(UNAVAILABLE_AFTER, after.message()));
        }
        getLog().info(String.format(OUTCOME, profile(), report.errorsBefore(), after.total(),
                Math.max(NONE, report.errorsBefore() - after.total())));
        if (after.total() == NONE) {
            getLog().info(String.format(CLEAN, profile()));
            return;
        }
        remaining(after);
    }

    /**
     * Says what the fixers did.
     *
     * @param report the repair pass
     */
    private void describe(DeterministicFixReport report) {
        if (report.withoutCatalog()) {
            getLog().warn(report.describe());
        } else if (!report.attemptedAnything()) {
            getLog().info(String.format(NOTHING, report.errorsBefore()));
        } else {
            getLog().info(String.format(APPLIED, report.codes().size(), String.join(SEPARATOR, report.codes())));
        }
        for (String failure : report.fixerFailures()) {
            getLog().warn(String.format(NOT_APPLIED, failure));
        }
    }

    /**
     * Lists what the repair left and fails, or does not, as configured.
     *
     * @param after the analysis after the repair
     * @throws MojoFailureException when the goal must fail
     */
    private void remaining(AnalysisResult after) throws MojoFailureException {
        for (SheriffFinding finding : after.errors()) {
            getLog().error(CheckMojo.oneLine(finding.describe()));
        }
        String summary = String.format(REMAINING, after.total(), profile(), basedir.getName());
        if (failOnRemaining) {
            throw new MojoFailureException(summary);
        }
        getLog().warn(String.format(TOLERATED, summary));
    }

    /**
     * One deterministic repair pass, with a catalog that matches the image
     * installed rather than the one this plugin was built with. Overridable so the decision above can be
     * tested without Docker.
     *
     * @return what the fixers did
     */
    protected DeterministicFixReport repair() {
        Composition composition = composition();
        composition.provisionCatalog().ifPresent(warning -> getLog().warn(warning.strip()));
        return composition.deterministicFixer().run();
    }
}
