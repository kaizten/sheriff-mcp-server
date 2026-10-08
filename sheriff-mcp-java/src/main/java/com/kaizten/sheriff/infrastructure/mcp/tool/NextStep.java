package com.kaizten.sheriff.infrastructure.mcp.tool;

import java.util.List;
import java.util.Set;

/**
 * The one thing to do after an answer, which every answer ends with.
 *
 * <p>The answers were the same for the same code, call after call, and the
 * work still went differently with each model, because the answers left the
 * choice to it: "fix the ones above, or ask for a narrower component", "call
 * the fixer with one of these codes". Sonnet at low effort asked, stopped, or
 * skipped the repair; Opus did none of that. So the choice is made here, from
 * the state alone, and an answer names a single step and no alternative: the
 * same state always gets the same step, whichever model reads it.
 *
 * <p>A step names its call with the component, as {@link ToolCall} writes
 * it, so that it can be made as it stands, by tool or by command.
 *
 * <p>The work is one loop with one tool in it: {@code sheriff_fix}, the
 * errors it leaves by hand, {@code sheriff_fix} again. It analyzes before and
 * after, so it is also the check, and it repairs at once what an edit by hand
 * breaks, such as a blank line left in a method. The step after an analysis
 * does not depend on the rule catalog either: without one, nothing is known
 * to be repairable, and the step used to send a model to edit by hand what
 * Sheriff would have repaired for free.
 */
final class NextStep {

    /**
     * How every step starts, so that a model, and this class, can find it.
     */
    static final String MARK = "Next step:";

    private static final String REPAIR = "%n" + MARK + " %s. It applies every repair Sheriff has for these "
            + "errors and lists what it cannot repair.";
    private static final String THESE_FILES = "%n" + MARK + " fix by hand every error listed above, all of "
            + "them, in %s, each file from the last line up, as the line numbers are those of this analysis. "
            + "Leave the other files for now: the next answer lists them. Then %s again. These are the work, errors "
            + "that were already there included: do not ask the user whether to fix them, and do not stop "
            + "before a call reports 0 errors. Only a false positive of the checker may stay; name it and "
            + "say why.";
    private static final String TESTS = "%n" + MARK + " run the project's tests (%s). If they pass, the "
            + "component is done. If you change any file to make them pass, %s again.";
    private static final String NO_TESTS = "%n" + MARK + " none, the component is done: it meets the "
            + "standards and has no tests to run.";
    private static final String PASSING = "%n" + MARK + " none, the component is done: it meets the "
            + "standards and its tests pass.";
    private static final String FAILING = "%n" + MARK + " fix what makes the tests fail, then %s again.";
    private static final String UNAVAILABLE = "%n%n" + MARK + " tell the user the code standards check could "
            + "not run, and why, in their words above. Go on with the rest of the work, run the project's own "
            + "tests, and %s again before finishing.";
    private static final String UNTESTED = "%n%nThese methods are new and no test that changed calls them, so "
            + "nothing checks they work:%n%s%n" + MARK + " add a test under the component's src/test for each "
            + "one, that calls it and checks what it returns, edge cases included (nothing to return, a null "
            + "among the inputs). Then run %s, and %s again.";
    private static final String UNTESTED_LINE = "  - ";
    private static final String NEWLINE = "\n";
    private static final String FILE_SEPARATOR = ", ";
    private static final String STEP_START = NEWLINE + MARK;
    private static final int NOT_FOUND = -1;
    private static final String PROJECT_TESTS = "the project's tests";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private NextStep() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The step after an analysis that found errors: the repair, always.
     *
     * @param fixCall the repair's call, written to be made as it stands
     * @return the step, as the answer's last line
     */
    static String afterAnalysis(String fixCall) {
        return String.format(REPAIR, fixCall);
    }

    /**
     * The step after a repair that left errors: the files the answer lists,
     * and no other.
     *
     * <p>The whole list in every answer was 8 to 11 KB a call, and a Codex
     * session that fixed one file per call got it fifteen times. Told that
     * the rest were in an earlier answer, the session before had gone looking
     * for them in Sheriff's raw JSON. A few files are a step any model can
     * finish, and the same state names the same files, whoever asks.
     *
     * @param repairable the rules among them that Sheriff's fixers can still
     *     repair: none after a repair with no rule named, which ran all of
     *     them
     * @param files the files the answer lists, as it names them
     * @param fixCall the repair's call, written to be made as it stands
     * @return the step, as the answer's last line
     */
    static String afterRepair(Set<String> repairable, List<String> files, String fixCall) {
        if (repairable.isEmpty()) {
            return String.format(THESE_FILES, String.join(FILE_SEPARATOR, files), fixCall);
        }
        return String.format(REPAIR, fixCall);
    }

    /**
     * The step once a component has no errors.
     *
     * @param testCommand the command that runs the component's tests, or the
     *     empty string when it has none
     * @param testCall the analysis's call, written to be made as it stands
     * @return the step, as the answer's last line
     */
    static String afterClean(String testCommand, String testCall) {
        return testCommand.isEmpty() ? String.format(NO_TESTS) : String.format(TESTS, testCommand, testCall);
    }

    /**
     * The step once a component has no errors but methods the change added
     * have no test: before the tests, which pass whatever an untested method
     * does.
     *
     * @param untested each method, as {@code path: name}
     * @param testCommand the command that runs the component's tests
     * @param testCall the analysis's call, written to be made as it stands
     * @return the list and the step, the step last
     */
    static String afterUntested(List<String> untested, String testCommand, String testCall) {
        String list = UNTESTED_LINE + String.join(NEWLINE + UNTESTED_LINE, untested);
        return String.format(UNTESTED, list, testCommand.isEmpty() ? PROJECT_TESTS : testCommand, testCall);
    }

    /**
     * The step once a component has no errors and its tests pass.
     *
     * @return the step, as the answer's last line
     */
    static String afterPassingTests() {
        return String.format(PASSING);
    }

    /**
     * The step once a component has no errors but its tests fail.
     *
     * @param testCall the analysis's call, written to be made as it stands
     * @return the step, as the answer's last line
     */
    static String afterFailingTests(String testCall) {
        return String.format(FAILING, testCall);
    }

    /**
     * The step after an answer that could not check anything, unless the
     * answer already names one.
     *
     * @param answer the answer
     * @param testCall the analysis's call, written to be made as it stands
     * @return the answer, ending with a step
     */
    static String afterFailure(String answer, String testCall) {
        return answer.contains(MARK) ? answer : answer + String.format(UNAVAILABLE, testCall);
    }

    /**
     * An answer with its step moved to the end, after whatever was added
     * below it: the task's folder, a notice about the image.
     *
     * @param answer the answer
     * @return the same text, the step last
     */
    static String last(String answer) {
        int start = answer.lastIndexOf(STEP_START);
        if (start == NOT_FOUND) {
            return answer;
        }
        int end = answer.indexOf(NEWLINE, start + NEWLINE.length());
        if (end == NOT_FOUND) {
            return answer;
        }
        return answer.substring(0, start) + answer.substring(end) + answer.substring(start, end);
    }
}
