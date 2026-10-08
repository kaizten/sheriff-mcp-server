package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.docker.FixerFailures;
import com.kaizten.sheriff.infrastructure.mcp.AcceptedCode;
import com.kaizten.sheriff.infrastructure.mcp.FindingsRenderer;
import com.kaizten.sheriff.infrastructure.mcp.FixRun;
import com.kaizten.sheriff.infrastructure.mcp.task.Task;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * {@code sheriff_fix}: applies the repairs Sheriff makes by itself, with no
 * model and no tokens, and reports the errors before and after.
 *
 * <p>A repair is measured, never taken from the list of fixers that ran: a
 * fixer can fail, or exit 0 having done nothing, and only analyzing again
 * shows which.
 */
final class SheriffFixTool {

    private static final String DESCRIPTION =
            "Apply every repair Sheriff has for a component, at no token cost, then list what is "
            + "left to fix by hand, with the code Sheriff accepts and the next step. Call it again "
            + "after editing: it checks and repairs what the edits broke.";
    private static final String TITLE = "Repair with Sheriff's own fixers";
    private static final ToolAnnotations ANNOTATIONS = ToolAnnotations.rewritingInPlace(TITLE);
    private static final String REFERENCE_CODE_HELP =
            "Leave it out. Rule ids, separated by commas, narrow the repair to those.";
    private static final String VERIFY_ARGUMENT = "verify";
    private static final String VERIFY_HELP =
            "'true' to also run the project's tests after the repair.";
    private static final String YES = "true";
    private static final String DEFAULT_FIXER_SCOPE = "every fixer Sheriff has for these errors";
    private static final String SPECIFIC_FIXER_SCOPE = "the `%s` fixer";
    private static final String FIX_SUMMARY =
            "Ran %s on %s: %d error(s) before, %d after (%d repaired).";
    private static final String NOT_APPLIED =
            "%nSheriff could not apply %d of its fixers, so the rules they cover were not repaired "
            + "and need editing by hand: %s";
    private static final String FAILURE_SEPARATOR = "; ";
    private static final String CHANGED_FILES = "%nFiles the fixers changed (%d): %s";
    private static final String FILE_SEPARATOR = ", ";
    private static final String UNDER = ", under %s";
    private static final String STILL_OUTSTANDING = "%nStill outstanding: %d error(s) in %d file(s). This answer "
            + "lists the %d of the first %d, and the work is those files now:";
    private static final String SAME_AS_LAST_TIME = "%nThese %d errors are exactly the ones the last answer of "
            + "this tool listed: the edits since then fixed none of them.";
    private static final String OTHER_FILES = "%n%nLeft in the other files, for the calls after this one (%d): %s";
    private static final String COUNT = "%s %d";
    private static final String NO_FILE = "";
    private static final String TESTS_PASS_AFTER = "%nThe project's own tests still pass after the repair (%s).";
    private static final String TESTS_FAIL_AFTER =
            "%nThe project's own tests FAIL after the repair (%s). The repair is left in place for you "
            + "to review: fix what broke, or revert it.%n%s";
    private static final String TESTS_FAIL_UNCHANGED =
            "%nThe project's own tests FAIL (%s), but this repair changed no file, so they were already "
            + "failing before it: not something the repair broke.%n%s";
    private static final int TEST_OUTPUT_LIMIT = 2000;
    private static final String NO_TESTS =
            "%nNo build tool with tests was found in %s (Maven, Gradle or an npm test script), so no tests "
            + "were run: only Sheriff checked this repair.";
    private static final int NO_ERRORS = 0;
    private static final int ERRORS_PER_ANSWER = 20;
    private static final String CODE_SEPARATOR = ",";

    private final ToolContext context;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context what runs Sheriff, the catalog, the tests and the tasks
     */
    SheriffFixTool(ToolContext context) {
        this.context = context;
    }

    /**
     * The tool's definition, bound to its handler.
     *
     * @return that tool
     */
    Tool definition() {
        Map<String, Object> schema = Schemas.objectSchema(Map.of(
                ToolContext.COMPONENT_ARGUMENT, Schemas.stringProperty(ToolContext.COMPONENT_HELP),
                ToolContext.PROFILE_ARGUMENT, Schemas.stringProperty(ToolContext.PROFILE_HELP),
                ToolContext.REFERENCE_CODE_ARGUMENT, Schemas.stringProperty(REFERENCE_CODE_HELP),
                VERIFY_ARGUMENT, Schemas.stringProperty(VERIFY_HELP)));
        return new Tool(context.fixTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Repairs what Sheriff can repair by itself, as a task.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        String component = context.componentArgument(arguments);
        String profile = context.profileArgument(arguments, component);
        String referenceCode = ToolContext.textArgument(arguments, ToolContext.REFERENCE_CODE_ARGUMENT);
        boolean verify = YES.equalsIgnoreCase(ToolContext.textArgument(arguments, VERIFY_ARGUMENT));
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put(ToolContext.COMPONENT_ARGUMENT, component);
        resolved.put(ToolContext.PROFILE_ARGUMENT, profile);
        resolved.put(ToolContext.REFERENCE_CODE_ARGUMENT, referenceCode);
        resolved.put(VERIFY_ARGUMENT, Boolean.toString(verify));
        return ToolContext.answered(context.tasks().run(ToolContext.commandLine(context.fixTool(), resolved),
                task -> fix(task, component, profile, referenceCode, verify,
                        ToolContext.textArgument(arguments, ToolContext.PROFILE_ARGUMENT))));
    }

    /**
     * Repairs what Sheriff can repair by itself.
     *
     * @param task the task this runs as, whose folder gets Sheriff's JSON
     * @param component the component to repair
     * @param profile the profile to repair it under
     * @param referenceCode the rules to repair, empty for the default set
     * @param verify whether to run the project's tests afterwards
     * @param asked the profile the caller asked for, empty when it was worked
     *     out, so that a step names it only then
     * @return the text to answer with
     */
    private String fix(Task task, String component, String profile, String referenceCode, boolean verify,
            String asked) {
        FixRun run = context.runner().exportingTo(task.directory()).fix(component, profile, referenceCode);
        if (run.before().total() == NO_ERRORS && !context.config().layout().holdsSourcesFor(component, profile)) {
            throw new IllegalArgumentException(ToolContext.nothingAnalyzed(component, profile));
        }
        int repaired = run.before().total() - run.after().total();
        String scope = referenceCode.isEmpty()
                ? DEFAULT_FIXER_SCOPE
                : String.format(SPECIFIC_FIXER_SCOPE, referenceCode);
        StringBuilder body = new StringBuilder(String.format(
                FIX_SUMMARY, scope, component, run.before().total(), run.after().total(), repaired));
        if (!run.fixerFailures().isEmpty()) {
            body.append(String.format(NOT_APPLIED, run.fixerFailures().size(),
                    String.join(FAILURE_SEPARATOR, run.fixerFailures())));
        }
        List<String> changed = run.after().changedSince(run.before()).stream().sorted().toList();
        if (!changed.isEmpty()) {
            String prefix = FindingsRenderer.commonFolder(changed);
            body.append(String.format(CHANGED_FILES, changed.size(), String.join(FILE_SEPARATOR,
                    changed.stream().map(file -> FindingsRenderer.relative(file, prefix)).toList())));
            if (!prefix.isEmpty()) {
                body.append(String.format(UNDER, prefix));
            }
        }
        boolean testsRun = verify && context.config().layout().hasTests(component);
        VerificationResult tests = testsRun ? context.tests().apply(component) : null;
        if (verify) {
            body.append(verification(component, tests, !run.before().trackedFiles().isEmpty() && changed.isEmpty()));
        }
        if (run.after().total() > NO_ERRORS) {
            FindingsRenderer renderer = new FindingsRenderer(context.catalog().allRules());
            Set<String> unrepaired = unrepaired(run, referenceCode);
            List<SheriffFinding> left = run.after().errors();
            Set<String> repairable = renderer.repairableRules(left, unrepaired);
            if (context.answers().sameAsLastTime(component, left)) {
                body.append(String.format(SAME_AS_LAST_TIME, left.size()));
            }
            List<String> files = firstFiles(left);
            List<SheriffFinding> shown = left.stream().filter(error -> files.contains(error.file())).toList();
            body.append(String.format(STILL_OUTSTANDING, left.size(), byFile(left).size(), shown.size(),
                    files.size()));
            body.append(renderer.render(shown, context.fixTool(), unrepaired));
            body.append(AcceptedCode.forFindings(shown));
            body.append(otherFiles(left, files));
            body.append(NextStep.afterRepair(repairable, files, context.fixCall(component, asked)));
        } else if (tests != null) {
            body.append(tests.ok() ? NextStep.afterPassingTests() : NextStep.afterFailingTests(context.testCall(component, asked)));
        } else {
            body.append(context.cleanStep(component, asked));
        }
        return body.toString();
    }

    /**
     * How many errors each file other than those listed holds, so that the
     * size of what is left is known without the errors themselves.
     *
     * @param left every error left
     * @param listed the files the answer lists
     * @return the counts, or nothing when no other file has errors
     */
    private static String otherFiles(List<SheriffFinding> left, List<String> listed) {
        Map<String, Long> others = byFile(left);
        others.keySet().removeAll(listed);
        if (others.isEmpty()) {
            return NO_FILE;
        }
        String prefix = FindingsRenderer.commonFolder(others.keySet());
        List<String> counts = others.entrySet().stream()
                .map(entry -> String.format(COUNT, FindingsRenderer.relative(entry.getKey(), prefix), entry.getValue()))
                .toList();
        String under = prefix.isEmpty() ? NO_FILE : String.format(UNDER, prefix);
        return String.format(OTHER_FILES, others.size(), String.join(FILE_SEPARATOR, counts)) + under;
    }

    /**
     * How many errors each file holds, in the order of their paths.
     *
     * @param errors the errors
     * @return the count per file
     */
    private static Map<String, Long> byFile(List<SheriffFinding> errors) {
        return errors.stream().collect(Collectors.groupingBy(SheriffFinding::file, TreeMap::new,
                Collectors.counting()));
    }

    /**
     * The files an answer lists: whole files, in the order of their paths,
     * until they hold {@link #ERRORS_PER_ANSWER} errors, and always one.
     *
     * <p>The whole list in every answer was 8 to 11 KB a call, and one file
     * at a time, when a file can hold one error, was a call for each.
     *
     * @param errors every error left
     * @return the files, in that order
     */
    private static List<String> firstFiles(List<SheriffFinding> errors) {
        List<String> files = new ArrayList<>();
        long listed = NO_ERRORS;
        for (Map.Entry<String, Long> file : byFile(errors).entrySet()) {
            if (listed >= ERRORS_PER_ANSWER) {
                break;
            }
            files.add(file.getKey());
            listed += file.getValue();
        }
        return files;
    }

    /**
     * The rules this repair cannot take any further: those whose fixer
     * Sheriff could not apply, and those it ran the fixer for that are still
     * there: every rule left, when none was named, since then every fixer
     * ran.
     *
     * <p>A fixer that runs and repairs nothing says nothing at all, so its
     * rule kept being offered as repairable, and a model called this tool
     * for it again and again, or gave up on it as a false positive, as a
     * PetClinic session did on 1 October.
     *
     * @param run the repair
     * @param referenceCode the codes asked for, comma-separated, or empty
     * @return those codes
     */
    private static Set<String> unrepaired(FixRun run, String referenceCode) {
        Set<String> codes = new LinkedHashSet<>(FixerFailures.codesOf(run.fixerFailures()));
        Set<String> left = new LinkedHashSet<>();
        run.after().errors().forEach(finding -> left.add(finding.referenceCode()));
        if (referenceCode.isEmpty()) {
            codes.addAll(left);
            return codes;
        }
        for (String code : referenceCode.split(CODE_SEPARATOR)) {
            if (left.contains(code.strip())) {
                codes.add(code.strip());
            }
        }
        return codes;
    }

    /**
     * Says how a component's own tests went after a repair.
     *
     * @param component the component that was repaired
     * @param result how its tests went, or {@code null} when it has none
     * @param unchanged whether the hashes Sheriff recorded before and after
     *     show no file changed, so a failure was there before the repair;
     *     {@code false} when there were no hashes to compare
     * @return the lines to append to the answer
     */
    private String verification(String component, VerificationResult result, boolean unchanged) {
        if (result == null) {
            return String.format(NO_TESTS, component);
        }
        String command = context.config().layout().verificationCommand(component);
        if (result.ok()) {
            return String.format(TESTS_PASS_AFTER, command);
        }
        String output = result.output().strip();
        String excerpt = output.length() <= TEST_OUTPUT_LIMIT
                ? output
                : output.substring(output.length() - TEST_OUTPUT_LIMIT);
        return String.format(unchanged ? TESTS_FAIL_UNCHANGED : TESTS_FAIL_AFTER, command, excerpt);
    }

}
