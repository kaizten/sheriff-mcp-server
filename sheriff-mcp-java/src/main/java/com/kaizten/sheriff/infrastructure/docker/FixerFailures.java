package com.kaizten.sheriff.infrastructure.docker;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixers Sheriff could not apply, read from what {@code fix} prints.
 *
 * <p>{@code fix} exits 1 whatever happened, so its exit code says nothing,
 * and a fixer that cannot run looks from outside exactly like one that ran
 * and found nothing to change. Its output does say which: each fixer it could
 * not apply is a {@code Could not apply fixer for error 'CODE'} line followed
 * by {@code Error: <reason>}. Found in the image of 21 September 2026, where
 * the {@code BracesForStatements} fixer is registered as a jar that is not in
 * the image, and every run of it failed while this project reported the rule
 * as handed to a fixer.
 */
public final class FixerFailures {

    private static final String NOT_APPLIED_PATTERN = "Could not apply fixer for error '([^']*)'";
    private static final Pattern NOT_APPLIED = Pattern.compile(NOT_APPLIED_PATTERN);
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";
    private static final String ERROR_PREFIX = "Error: ";
    private static final String LINE_SEPARATOR_PATTERN = "\\R";
    private static final String FAILURE = "%s: %s";
    private static final int CODE_GROUP = 1;
    private static final int NEXT_LINE = 1;
    private static final int REASON_LIMIT = 200;
    private static final int REASON_START = 0;
    private static final String CODE_SEPARATOR = ": ";
    private static final int CODE_START = 0;
    private static final int NOT_FOUND = -1;

    /**
     * Not instantiable: a function over one run's output.
     */
    private FixerFailures() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * Each fixer Sheriff could not apply, once per rule and reason.
     *
     * @param standardOutput what {@code fix} printed
     * @return {@code CODE: reason} for each, in the order they appeared
     */
    public static List<String> in(String standardOutput) {
        String[] lines = standardOutput.split(LINE_SEPARATOR_PATTERN);
        Set<String> failures = new LinkedHashSet<>();
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = NOT_APPLIED.matcher(lines[index]);
            if (matcher.find()) {
                failures.add(String.format(FAILURE, matcher.group(CODE_GROUP), reasonAfter(lines, index)));
            }
        }
        return new ArrayList<>(failures);
    }

    /**
     * The rule codes of the fixers that failed, for whoever has to stop
     * offering them as repairable.
     *
     * @param failures what {@link #in(String)} returned
     * @return the codes, in the order they appeared
     */
    public static Set<String> codesOf(List<String> failures) {
        Set<String> codes = new LinkedHashSet<>();
        for (String failure : failures) {
            int end = failure.indexOf(CODE_SEPARATOR);
            codes.add(end == NOT_FOUND ? failure : failure.substring(CODE_START, end));
        }
        return codes;
    }

    /**
     * The reason Sheriff gives on the line after a fixer it could not apply.
     *
     * @param lines the output, line by line
     * @param index where the fixer was named
     * @return the reason, shortened, or empty when Sheriff gave none
     */
    private static String reasonAfter(String[] lines, int index) {
        int next = index + NEXT_LINE;
        if (next >= lines.length || !lines[next].startsWith(ERROR_PREFIX)) {
            return "";
        }
        String reason = lines[next].substring(ERROR_PREFIX.length()).strip();
        return reason.length() <= REASON_LIMIT ? reason : reason.substring(REASON_START, REASON_LIMIT);
    }
}
