package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.PrintStream;
import java.util.List;

/**
 * The same jar as a CI gate: {@code java -jar sheriff-mcp.jar --check}, run
 * in a project, analyzes every component it holds and exits non-zero unless
 * all of them pass.
 *
 * <p>It works the project out the way the server does, so a pipeline needs no
 * more configuration than a client: the directory it runs in is enough.
 * Three exit codes, because "the code has errors" and "nothing could be
 * analyzed" call for different people: 0 when every component passed, 1 when
 * any has errors, 2 when a run could not be trusted or there was nothing to
 * run on. Zero errors over a component with no sources of the profile's
 * language is the last of those, never a pass.
 */
public final class CheckCommand {

    public static final int PASSED = 0;
    public static final int ERRORS_FOUND = 1;
    public static final int NOT_ANALYZED = 2;

    private static final String COMPONENT_FLAG = "--component";
    private static final String PROFILE_FLAG = "--profile";

    /**
     * The options this command reads, each followed by its value.
     */
    static final List<String> OPTIONS = List.of(COMPONENT_FLAG, PROFILE_FLAG);
    private static final String NOT_GIVEN = "";
    private static final int NOT_FOUND = -1;
    private static final int NEXT = 1;
    private static final String NO_COMPONENTS =
            "Nothing to check: %s holds no folder with sources Sheriff analyzes. Run this in the "
            + "project, or pass --component.%n";
    private static final String CLEAN = "%s: 0 errors under %s.%n";
    private static final String FOUND = "%s: %d error(s) under %s:%n";
    private static final String FINDING = "  - %s%n";
    private static final String NOTHING_ANALYZED =
            "%s: nothing was analyzed -- it holds no sources for the %s profile, which Sheriff "
            + "reports as zero errors all the same.%n";
    private static final String UNAVAILABLE = "%s: Sheriff could not analyze it: %s%n";
    private static final String LINE_BREAK = "\\s*\\R\\s*";
    private static final String SPACE = " ";

    private final McpConfig config;
    private final SheriffRunner runner;
    private final PrintStream output;

    /**
     * Wires the check to a project and to Sheriff.
     *
     * @param config the project and how to reach Sheriff
     * @param runner what runs Sheriff
     * @param output where the report goes
     */
    public CheckCommand(McpConfig config, SheriffRunner runner, PrintStream output) {
        this.config = config;
        this.runner = runner;
        this.output = output;
    }

    /**
     * Checks the components the command line names, or every one there is.
     *
     * @param arguments the command line
     * @return the exit code
     */
    public int run(List<String> arguments) {
        String named = valueAfter(arguments, COMPONENT_FLAG);
        String profile = valueAfter(arguments, PROFILE_FLAG);
        List<String> components = named.isEmpty() ? config.layout().components() : List.of(named);
        if (components.isEmpty()) {
            output.printf(NO_COMPONENTS, config.repository());
            return NOT_ANALYZED;
        }
        int worst = PASSED;
        for (String component : components) {
            worst = Math.max(worst, check(component, profile.isEmpty() ? config.profileFor(component) : Rules.withBaseProfiles(profile)));
        }
        return worst;
    }

    /**
     * Checks one component and reports it.
     *
     * @param component the component
     * @param profile the profile to check it under
     * @return its exit code
     */
    private int check(String component, String profile) {
        AnalysisResult result;
        try {
            result = runner.test(component, profile);
        } catch (SheriffUnavailableException exception) {
            output.printf(UNAVAILABLE, component, exception.getMessage());
            return NOT_ANALYZED;
        }
        if (result.total() > PASSED) {
            output.printf(FOUND, component, result.total(), profile);
            for (SheriffFinding finding : result.errors()) {
                output.printf(FINDING, finding.describe().strip().replaceAll(LINE_BREAK, SPACE));
            }
            return ERRORS_FOUND;
        }
        if (!config.layout().holdsSourcesFor(component, profile)) {
            output.printf(NOTHING_ANALYZED, component, profile);
            return NOT_ANALYZED;
        }
        output.printf(CLEAN, component, profile);
        return PASSED;
    }

    /**
     * The value that follows a flag.
     *
     * @param arguments the command line
     * @param flag the flag
     * @return its value, or the empty string when it is absent or last
     */
    private static String valueAfter(List<String> arguments, String flag) {
        int at = arguments.indexOf(flag);
        return at == NOT_FOUND || at + NEXT >= arguments.size() ? NOT_GIVEN : arguments.get(at + NEXT);
    }
}
