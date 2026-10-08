package com.kaizten.sheriff.infrastructure.extractor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pulls out of each fixer script the function that builds the code Sheriff
 * writes.
 *
 * <p>This answers a question the catalog could not otherwise answer. A rule's
 * description says what is wrong and its howToSolve says it again in other
 * words; neither shows accepted code. A fixer's {@code method_source} does —
 * it is the exact text Sheriff writes to satisfy the rule — so it is kept
 * verbatim rather than executed.
 *
 * <p>Forty-two of the ninety-five scripts have one. The rest repair by
 * transforming what is already there, such as sorting imports or removing
 * blank lines, where the shape is not the point and the description suffices.
 */
public final class FixerExampleParser {

    private static final String MARKER = "@@FIXER ";
    private static final String SNIPPET_FUNCTION = "def method_source(";
    private static final String NEXT_TOP_LEVEL_EXPRESSION = "^(?:def |class |@|if __name__)";
    private static final Pattern NEXT_TOP_LEVEL = Pattern.compile(NEXT_TOP_LEVEL_EXPRESSION);
    private static final String NEWLINE = "\n";
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses instantiation: this is a holder of static methods.
     */
    private FixerExampleParser() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * Fixer script path to the function that builds its snippet.
     *
     * <p>A function's block ends at the next top-level construct rather than
     * at the next unindented line: these signatures span several lines and
     * close with {@code ) -> str:} in column zero, which is still part of
     * the function.
     *
     * @param dump every script, each preceded by an {@code @@FIXER} marker
     * @return the scripts that carry such a function, by path
     */
    public static Map<String, String> parse(String dump) {
        Map<String, String> examples = new LinkedHashMap<>();
        String path = "";
        List<String> lines = new ArrayList<>();
        boolean collecting = false;
        for (String line : dump.split(NEWLINE, -1)) {
            if (line.startsWith(MARKER)) {
                store(examples, path, lines);
                path = line.substring(MARKER.length()).strip();
                lines = new ArrayList<>();
                collecting = false;
                continue;
            }
            if (line.startsWith(SNIPPET_FUNCTION)) {
                collecting = true;
                lines = new ArrayList<>();
                lines.add(line);
                continue;
            }
            if (collecting) {
                if (NEXT_TOP_LEVEL.matcher(line).find()) {
                    collecting = false;
                    continue;
                }
                lines.add(line);
            }
        }
        store(examples, path, lines);
        return examples;
    }

    /**
     * Records one script's function, when it had one.
     *
     * @param examples where to record it
     * @param path the script's path
     * @param lines the collected lines
     */
    private static void store(Map<String, String> examples, String path, List<String> lines) {
        if (path.isEmpty() || lines.isEmpty()) {
            return;
        }
        examples.put(path, String.join(NEWLINE, lines).stripTrailing());
    }
}
