package com.kaizten.sheriff.infrastructure.docker;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixers a {@code fix} ran, read from what it prints.
 *
 * <p>Sheriff prints a {@code Selected error: CODE | hasScript=true} line for
 * each fixer it runs, and none for a rule it was given and did not reach. In
 * the image of 5 November 2026 ({@code sha256:23231466}) that matters: after
 * a file's first fixer, {@code fix} counts every finding of that file as
 * fixed and runs none of the other fixers it was given for it, even when the
 * first changed nothing ("Errors fixed: 3", one fixer run, the file as it
 * was). Only these lines tell which fixers were skipped.
 */
public final class FixersRun {

    private static final String SELECTED_PATTERN = "^\\s*Selected error: (\\S+)";
    private static final Pattern SELECTED = Pattern.compile(SELECTED_PATTERN, Pattern.MULTILINE);
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";
    private static final int CODE_GROUP = 1;

    /**
     * Not instantiable: a function over one run's output.
     */
    private FixersRun() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * The rules whose fixer a {@code fix} ran.
     *
     * @param standardOutput what {@code fix} printed
     * @return their codes, in the order they ran, each once
     */
    public static Set<String> in(String standardOutput) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = SELECTED.matcher(standardOutput);
        while (matcher.find()) {
            codes.add(matcher.group(CODE_GROUP));
        }
        return codes;
    }
}
