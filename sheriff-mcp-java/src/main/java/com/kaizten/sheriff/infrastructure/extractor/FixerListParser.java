package com.kaizten.sheriff.infrastructure.extractor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code sheriff fix -l}: every reference code the image knows, and
 * whether Sheriff has a fixer of its own for it.
 *
 * <p>The table's columns are space-padded to absurd widths, so this keys off
 * the leading index number rather than column positions.
 */
public final class FixerListParser {

    private static final String ENTRY_REGEX = "^\\s*(\\d+)\\s+(\\S+)\\s+(.*?)\\s*$";
    private static final Pattern ENTRY = Pattern.compile(ENTRY_REGEX);
    private static final String NO_FIXER = "NO AVAILABLE";
    private static final String EMPTY = "";
    private static final String NEWLINE = "\n";
    private static final String WHITESPACE_REGEX = "\\s+";
    private static final String UTILITY_CLASS_MESSAGE = "This is a utility class and cannot be instantiated.";

    /**
     * Prevents instantiation of this utility class.
     */
    private FixerListParser() {
        throw new UnsupportedOperationException(UTILITY_CLASS_MESSAGE);
    }

    /**
     * Reference code to fixer path.
     *
     * <p>Whatever follows the fixer path on a row is that fixer's type
     * ({@code JAVA}, {@code PYTHON}, ...), which nothing here needs, so only
     * the first field of the remainder is kept.
     *
     * @param output what {@code fix -l} printed
     * @return the mapping, with an empty path where Sheriff has no fixer
     */
    public static Map<String, String> parse(String output) {
        Map<String, String> fixers = new LinkedHashMap<>();
        for (String line : output.split(NEWLINE)) {
            Matcher entry = ENTRY.matcher(line);
            if (!entry.find()) {
                continue;
            }
            String code = entry.group(2);
            if (!Character.isLetter(code.charAt(0))) {
                continue;
            }
            String rest = entry.group(3).replace(NO_FIXER, EMPTY).strip();
            fixers.put(code, rest.isEmpty() ? EMPTY : rest.split(WHITESPACE_REGEX)[0]);
        }
        return fixers;
    }
}
