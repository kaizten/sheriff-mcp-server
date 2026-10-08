package com.kaizten.sheriff.maven;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Fails the build when Sheriff reports errors on this module.
 *
 * <p>The same analysis the agent's {@code --check-only} runs, reached as a
 * Maven goal. Bound to {@code package} by default, so that
 * {@code mvn package} checks the standards too, as {@code verify} and
 * {@code install}, which include it, already did; it was {@code verify}, which
 * {@code mvn package} never reaches. Still after the tests, on purpose: a
 * module whose own suite is red has a worse problem than its documentation,
 * and the build should say so first.
 *
 * <p>Two failures, deliberately different. Errors in the code are a
 * {@link MojoFailureException} — the build is working and the code is not.
 * Sheriff not running at all is a {@link MojoExecutionException} — nothing was
 * measured, and reporting that as a clean module would be a lie.
 */
@Mojo(name = "check", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public class CheckMojo extends SheriffMojo {

    /**
     * The count that means the module passed.
     */
    private static final int NONE = 0;

    /**
     * Reported when Sheriff itself could not be run.
     */
    private static final String UNAVAILABLE = "Sheriff could not run: %s";

    /**
     * Reported when the module passed.
     */
    private static final String CLEAN = "Sheriff reports no errors under %s.";

    /**
     * Reported when the module did not pass.
     */
    private static final String SUMMARY = "Sheriff reports %d error(s) under %s in %s.";

    /**
     * Appended when the build is allowed to continue anyway.
     */
    private static final String TOLERATED = "%s Not failing the build (sheriff.failOnError=false).";

    /**
     * A line break inside a finding, with the indentation around it.
     */
    private static final String LINE_BREAK = "\\s*\\R\\s*";

    /**
     * What a line break inside a finding becomes.
     */
    private static final String SPACE = " ";

    /**
     * Report the findings without failing the build.
     */
    @Parameter(property = "sheriff.failOnError", defaultValue = "true")
    boolean failOnError = true;

    /**
     * Runs the analysis and decides whether the build continues.
     *
     * @throws MojoFailureException when Sheriff reports errors and failOnError is set
     * @throws MojoExecutionException when Sheriff could not be run at all
     */
    @Override
    public void execute() throws MojoFailureException, MojoExecutionException {
        if (skipped()) {
            return;
        }
        ensureImage();
        AnalysisResult result = analyze();
        if (result.error()) {
            throw new MojoExecutionException(String.format(UNAVAILABLE, result.message()));
        }
        if (result.total() == NONE) {
            getLog().info(String.format(CLEAN, profile()));
            return;
        }
        report(result);
    }

    /**
     * Lists the findings and then fails, or does not, as configured.
     *
     * @param result the analysis to report
     * @throws MojoFailureException when the build must not continue
     */
    private void report(AnalysisResult result) throws MojoFailureException {
        for (SheriffFinding finding : result.errors()) {
            getLog().error(oneLine(finding.describe()));
        }
        String summary = String.format(SUMMARY, result.total(), profile(), basedir.getName());
        if (failOnError) {
            throw new MojoFailureException(summary);
        }
        getLog().warn(String.format(TOLERATED, summary));
    }

    /**
     * A finding as the single line a log parser expects.
     *
     * <p>Sheriff quotes the offending code in its descriptions, line breaks
     * included, and every line after the first reached the log without its
     * {@code [ERROR]} prefix, where CI parsers and IDEs no longer tie it to
     * the finding it belongs to.
     *
     * @param text a finding as Sheriff describes it
     * @return the same text on one line
     */
    static String oneLine(String text) {
        return text.strip().replaceAll(LINE_BREAK, SPACE);
    }
}
