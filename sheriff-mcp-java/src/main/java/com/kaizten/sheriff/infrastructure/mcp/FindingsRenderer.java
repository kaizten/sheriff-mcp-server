package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a bounded, per-file account of what Sheriff found, the same shape
 * the Python server's {@code _render_findings} answered with.
 *
 * <p>The answer is bounded and the total never is: an unmodified Spring
 * PetClinic reports 221 errors under {@code JAVA}, which spelled out in full
 * is tens of thousands of tokens of context for one call. Past {@link
 * #MAX_FINDINGS_RENDERED} the remaining files are named with their error
 * counts instead, so nothing is hidden, and the next answer lists them once
 * these are fixed. A file's own errors are never split across the boundary:
 * the unit a model fixes is a file.
 *
 * <p>Within a file, the errors that name a line come first, from the last
 * line up, and those that name only a method or a field after them. The line
 * numbers are those of the analysis, and an edit moves every line below it:
 * worked from the bottom up, each number still points at its error when the
 * model gets to it.
 */
public final class FindingsRenderer {

    /**
     * How many findings one call renders in full before the rest are only
     * named. The first file is always rendered whole, however many errors it
     * carries, so the answer is never all summary and no detail.
     */
    static final int MAX_FINDINGS_RENDERED = 60;

    private static final int NOTHING_SHOWN_YET = 0;
    private static final int FIRST = 1;
    private static final String NO_FILE = "(no file)";
    private static final String FILE_HEADING = "%n%s";
    private static final String FINDING_LINE = "  - %s  [%s%s%s]";
    private static final String HOW_LINE = "      how: %s";
    private static final String UNDER = "%nPaths below are under %s";
    private static final String COUNT_LINE = "  %s: %d";
    private static final String BY_RULE = "%nBy rule: %s";
    private static final String RULE_COUNT = "%s %d";
    private static final String LIST_SEPARATOR = ", ";
    private static final String PATH_SEPARATOR = "/";
    private static final String RECOVERED_SUFFIX = ", recovered from the message";
    private static final String FIXER_MARKER = ": %s can repair this one";
    private static final String FAILED_FIXER_MARKER = ": its fixer did not repair it, edit it by hand";
    private static final String HELD_BACK_HEADING =
            "%n%d more error(s) in %d file(s), listed by name only -- spelling them all out here would "
            + "cost more context than it is worth. The next analysis lists them once those above are fixed.";
    private static final String HELD_BACK_LINE = "  %s: %d error(s)";
    private static final String LINE_SEPARATOR = "\n";
    private static final String LINE_PATTERN = "\\blines? '?(\\d+)";
    private static final String EMPTY = "";
    private static final Pattern LINE = Pattern.compile(LINE_PATTERN);
    private static final int NO_LINE = -1;
    private static final int FIRST_GROUP = 1;

    private final Map<String, SheriffRule> byCode;
    private final RuleRecovery recovery;

    /**
     * Wires the renderer to the catalog it looks rules up in.
     *
     * @param catalog every rule Sheriff knows, empty when there is none
     */
    public FindingsRenderer(List<SheriffRule> catalog) {
        Map<String, SheriffRule> indexed = new LinkedHashMap<>();
        for (SheriffRule rule : catalog) {
            indexed.put(rule.code(), rule);
        }
        this.byCode = Map.copyOf(indexed);
        this.recovery = new RuleRecovery(catalog);
    }

    /**
     * Renders every error, grouped by file and bounded to {@link
     * #MAX_FINDINGS_RENDERED}.
     *
     * @param errors the findings to render
     * @param fixToolName the name of the tool that can repair some of them,
     *     for the mark on each one it can
     * @return the rendered text, empty when there is nothing to add to a
     *     header the caller already printed
     */
    public String render(List<SheriffFinding> errors, String fixToolName) {
        return render(errors, fixToolName, Set.of());
    }

    /**
     * Renders every error, as {@link #render(List, String)} does, without
     * offering the fixers that just failed as a way to repair anything: a
     * model told to call the fixer again would call it again, and it would
     * fail again.
     *
     * @param errors the findings to render
     * @param fixToolName the name of the tool that can repair some of them
     * @param failedFixers the rule codes whose fixer Sheriff could not apply, or ran
     *     without repairing them
     * @return the rendered text
     */
    public String render(List<SheriffFinding> errors, String fixToolName, Set<String> failedFixers) {
        Map<String, List<SheriffFinding>> byFile = byFile(errors);
        String prefix = commonFolder(byFile.keySet());
        List<Map.Entry<String, List<SheriffFinding>>> ordered = new ArrayList<>(byFile.entrySet());
        int budgetCutoff = withinBudget(ordered);
        List<String> lines = new ArrayList<>();
        if (!prefix.isEmpty()) {
            lines.add(String.format(UNDER, prefix));
        }
        Set<String> explained = new LinkedHashSet<>(AcceptedCode.codes());
        for (int index = 0; index < budgetCutoff; index++) {
            appendFile(lines, ordered.get(index), prefix, fixToolName, failedFixers, explained);
        }
        if (budgetCutoff < ordered.size()) {
            appendHeldBack(lines, ordered.subList(budgetCutoff, ordered.size()), prefix);
        }
        return String.join(LINE_SEPARATOR, lines);
    }

    /**
     * How many errors each file holds, and each rule, without the errors
     * themselves: what an analysis answers with.
     *
     * <p>The step after an analysis with errors is always the repair, whose
     * answer lists them in full. Listing them in the analysis too was 16 KB
     * that a model read and then read again; on PetClinic a session made four
     * analyses in a row.
     *
     * @param errors the findings
     * @return the counts, file by file, then rule by rule
     */
    public String summary(List<SheriffFinding> errors) {
        Map<String, List<SheriffFinding>> byFile = byFile(errors);
        String prefix = commonFolder(byFile.keySet());
        List<String> lines = new ArrayList<>();
        if (!prefix.isEmpty()) {
            lines.add(String.format(UNDER, prefix));
        }
        byFile.forEach((file, found) -> lines.add(String.format(COUNT_LINE, relative(file, prefix), found.size())));
        Map<String, Integer> byRule = new LinkedHashMap<>();
        errors.forEach(finding -> byRule.merge(identifierOf(finding), FIRST, Integer::sum));
        List<String> counts = new ArrayList<>();
        byRule.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> counts.add(String.format(RULE_COUNT, entry.getKey(), entry.getValue())));
        lines.add(String.format(BY_RULE, String.join(LIST_SEPARATOR, counts)));
        return String.join(LINE_SEPARATOR, lines);
    }

    /**
     * The folder every path shares, written once above them instead of on
     * each: on PetClinic, {@code spring-petclinic/src/main/java/org/
     * springframework/samples/petclinic/} was 15% of an answer.
     *
     * @param paths the paths
     * @return their common folder, ending in a slash, or empty when they
     *     share none
     */
    public static String commonFolder(Collection<String> paths) {
        String common = null;
        for (String path : paths) {
            String folder = path.substring(0, path.lastIndexOf(PATH_SEPARATOR) + FIRST);
            common = common == null ? folder : sharedStart(common, folder);
        }
        return common == null ? EMPTY : common;
    }

    /**
     * A path without a folder it starts with.
     *
     * @param path the path
     * @param prefix the folder, or empty
     * @return the rest of the path
     */
    public static String relative(String path, String prefix) {
        return !prefix.isEmpty() && path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    /**
     * The part two folders share, cut at a slash.
     *
     * @param first one folder, ending in a slash
     * @param second another
     * @return the folders both start with
     */
    private static String sharedStart(String first, String second) {
        int length = Math.min(first.length(), second.length());
        int end = NOTHING_SHOWN_YET;
        for (int index = NOTHING_SHOWN_YET; index < length && first.charAt(index) == second.charAt(index); index++) {
            if (first.charAt(index) == PATH_SEPARATOR.charAt(NOTHING_SHOWN_YET)) {
                end = index + FIRST;
            }
        }
        return first.substring(NOTHING_SHOWN_YET, end);
    }

    /**
     * The findings grouped by file, files in name order.
     *
     * @param errors the findings
     * @return each file's findings
     */
    private static Map<String, List<SheriffFinding>> byFile(List<SheriffFinding> errors) {
        Map<String, List<SheriffFinding>> byFile = new TreeMap<>();
        for (SheriffFinding finding : errors) {
            String file = finding.file().isEmpty() ? NO_FILE : finding.file();
            byFile.computeIfAbsent(file, key -> new ArrayList<>()).add(finding);
        }
        return byFile;
    }

    /**
     * The rule a finding names, or the one recovered from its message.
     *
     * @param finding the finding
     * @return the rule's code, empty when neither is known
     */
    private String identifierOf(SheriffFinding finding) {
        return !finding.referenceCode().isEmpty()
                ? finding.referenceCode()
                : ruleFor(finding).map(SheriffRule::code).orElse(EMPTY);
    }

    /**
     * How many of the ordered files fit inside the budget, always including
     * at least the first.
     *
     * @param ordered the files, findings-per-file, in the order they render
     * @return the index at which the held-back files begin
     */
    private static int withinBudget(List<Map.Entry<String, List<SheriffFinding>>> ordered) {
        int budget = MAX_FINDINGS_RENDERED;
        int shown = 0;
        for (Map.Entry<String, List<SheriffFinding>> entry : ordered) {
            int count = entry.getValue().size();
            if (shown > NOTHING_SHOWN_YET && count > budget) {
                break;
            }
            shown++;
            budget -= count;
        }
        return shown;
    }

    /**
     * Appends one file's findings in full, one line each, with the rule's
     * advice under the first finding of a rule no accepted shape explains.
     *
     * <p>The advice, Sheriff's {@code howToSolve}, used to follow every
     * finding: a third of an answer, mostly the description again in other
     * words.
     *
     * @param lines the lines built so far
     * @param entry the file and its findings
     * @param prefix the folder every file shares, left out of the heading
     * @param fixToolName the name of the tool that can repair a fixable rule
     * @param failedFixers the rule codes whose fixer just failed or repaired nothing
     * @param explained the rules already explained in this answer, added to
     */
    private void appendFile(List<String> lines, Map.Entry<String, List<SheriffFinding>> entry, String prefix,
            String fixToolName, Set<String> failedFixers, Set<String> explained) {
        lines.add(String.format(FILE_HEADING, relative(entry.getKey(), prefix)));
        for (SheriffFinding finding : bottomUp(entry.getValue())) {
            Optional<SheriffRule> rule = ruleFor(finding);
            String identifier = identifierOf(finding);
            String suffix = finding.referenceCode().isEmpty() ? RECOVERED_SUFFIX : EMPTY;
            lines.add(String.format(FINDING_LINE, finding.description(), identifier, suffix,
                    markerFor(rule, fixToolName, failedFixers)));
            if (!finding.howToSolve().isEmpty() && explained.add(identifier)) {
                lines.add(String.format(HOW_LINE, finding.howToSolve()));
            }
        }
    }

    /**
     * A file's findings in the order to fix them: those that name a line,
     * from the last line up, then those that name none, as Sheriff listed
     * them.
     *
     * @param findings one file's findings
     * @return the same findings, reordered
     */
    static List<SheriffFinding> bottomUp(List<SheriffFinding> findings) {
        List<SheriffFinding> ordered = new ArrayList<>(findings);
        ordered.sort(Comparator.comparingInt(FindingsRenderer::lineOf).reversed());
        return ordered;
    }

    /**
     * The line a finding names, the first one when it names a range.
     *
     * @param finding the finding
     * @return the line, or {@link #NO_LINE} when its message names none
     */
    private static int lineOf(SheriffFinding finding) {
        Matcher matcher = LINE.matcher(finding.description());
        return matcher.find() ? Integer.parseInt(matcher.group(FIRST_GROUP)) : NO_LINE;
    }

    /**
     * Appends the files that did not fit the budget, named with their error
     * counts rather than spelled out.
     *
     * @param lines the lines built so far
     * @param heldBack the files left over
     * @param prefix the folder every file shares, left out of each name
     */
    private static void appendHeldBack(
            List<String> lines, List<Map.Entry<String, List<SheriffFinding>>> heldBack, String prefix) {
        int remaining = heldBack.stream().mapToInt(entry -> entry.getValue().size()).sum();
        lines.add(String.format(HELD_BACK_HEADING, remaining, heldBack.size()));
        for (Map.Entry<String, List<SheriffFinding>> entry : heldBack) {
            lines.add(String.format(HELD_BACK_LINE, relative(entry.getKey(), prefix), entry.getValue().size()));
        }
    }

    /**
     * What a finding's rule line says about repairing it: that the fix tool
     * can, that its fixer just failed, or nothing when it has none.
     *
     * @param rule the finding's rule, when it is known
     * @param fixToolName the name of the tool that can repair a fixable rule
     * @param failedFixers the rule codes whose fixer just failed or repaired nothing
     * @return the marker, empty when there is nothing to say
     */
    private static String markerFor(Optional<SheriffRule> rule, String fixToolName, Set<String> failedFixers) {
        if (rule.filter(SheriffRule::hasFixer).isEmpty()) {
            return EMPTY;
        }
        if (failedFixers.contains(rule.get().code())) {
            return FAILED_FIXER_MARKER;
        }
        return String.format(FIXER_MARKER, fixToolName);
    }

    /**
     * Every rule among these findings that Sheriff can repair itself, taken
     * from all of them rather than only the rendered ones, so the offer of a
     * free repair does not disappear with the files held back.
     *
     * @param errors the findings to scan
     * @param failedFixers the rule codes whose fixer just failed or repaired nothing, left out
     * @return the codes of the repairable rules, in the order first seen
     */
    public Set<String> repairableRules(List<SheriffFinding> errors, Set<String> failedFixers) {
        Set<String> repairable = new LinkedHashSet<>();
        for (SheriffFinding finding : errors) {
            ruleFor(finding).filter(SheriffRule::hasFixer).filter(rule -> !failedFixers.contains(rule.code()))
                    .ifPresent(rule -> repairable.add(rule.code()));
        }
        return repairable;
    }

    /**
     * The rule a finding broke: by its own reference code when it has one,
     * otherwise recovered from the message it printed.
     *
     * @param finding the finding to identify
     * @return that rule, empty when neither lookup finds one
     */
    private Optional<SheriffRule> ruleFor(SheriffFinding finding) {
        if (!finding.referenceCode().isEmpty()) {
            SheriffRule direct = byCode.get(finding.referenceCode());
            if (direct != null) {
                return Optional.of(direct);
            }
        }
        return recovery.recover(finding.description());
    }
}
