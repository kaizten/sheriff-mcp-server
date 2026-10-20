package com.kaizten.sheriff.infrastructure.process;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What of a build's output an answer shows when the build failed: the lines
 * that say why, then its end.
 *
 * <p>Only the end used to be shown, which is where Maven says that it failed
 * but rarely why. On PetClinic with checkstyle 14.3.0 under Java 17 the
 * cause, {@code UnsupportedClassVersionError ... class file version 65.0},
 * was the second line of 150, and the answer showed sixty lines of the jars
 * on a plugin's class path; the model had to look for it.
 */
public final class BuildOutput {

    private static final String CAUSE_LINE = "^(Caused by: .*"
            + "|[\\w.$]+(Exception|Error)(: .*)?"
            + "|\\[ERROR\\] (Failed to execute goal|COMPILATION ERROR|Tests run:|Failures:|Errors:).*"
            + "|\\[ERROR\\] \\S+\\.(java|kt):\\[\\d.*"
            + "|\\[ERROR\\] {3}\\S.*"
            + "|\\* What went wrong:"
            + "|.* FAILED"
            + "|\\s*● .*"
            + "|FAIL .*)$";
    private static final Pattern CAUSE = Pattern.compile(CAUSE_LINE);
    private static final String GRADLE_REASON = "* What went wrong:";
    private static final String NEWLINE = "\n";
    private static final String SKIPPED = "[...]\n";
    private static final int LINE_LIMIT = 400;
    private static final int SHARE_FOR_CAUSES = 2;
    private static final int START = 0;
    private static final int NOT_FOUND = -1;
    private static final int NEXT = 1;
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private BuildOutput() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * A failed build's output, cut to a size: whole when it fits; else the
     * lines that say why it failed, in order and each once, in up to half
     * the size, then the end of it in the rest, from the start of a line;
     * with no such line, its end alone.
     *
     * @param output what the build printed
     * @param limit the most characters to show
     * @return what to show
     */
    public static String excerpt(String output, int limit) {
        String text = output.strip();
        if (text.length() <= limit) {
            return text;
        }
        StringBuilder causes = new StringBuilder();
        for (String line : causes(text)) {
            if (causes.length() + line.length() + NEWLINE.length() > limit / SHARE_FOR_CAUSES) {
                break;
            }
            causes.append(line).append(NEWLINE);
        }
        if (causes.isEmpty()) {
            return text.substring(text.length() - limit);
        }
        int from = text.length() - (limit - causes.length() - SKIPPED.length());
        int lineStart = text.indexOf(NEWLINE, from - NEXT);
        String end = lineStart == NOT_FOUND ? text.substring(from) : text.substring(lineStart + NEXT);
        return causes + SKIPPED + end;
    }

    /**
     * The lines of an output that say why a build failed: an exception and
     * what caused it, Maven's goal that failed, a compiler's error, a test
     * that failed, Gradle's reason and the line after it, Jest's failures.
     *
     * @param text the output
     * @return those lines, each once, cut to a length
     */
    private static Set<String> causes(String text) {
        Set<String> causes = new LinkedHashSet<>();
        String[] lines = text.split(NEWLINE);
        for (int index = START; index < lines.length; index++) {
            String line = lines[index].stripTrailing();
            if (CAUSE.matcher(line).matches()) {
                causes.add(cut(line));
            }
            if (line.equals(GRADLE_REASON) && index + NEXT < lines.length) {
                causes.add(cut(lines[index + NEXT].strip()));
            }
        }
        return causes;
    }

    /**
     * A line cut to {@link #LINE_LIMIT} characters.
     *
     * @param line the line
     * @return the line, or its start
     */
    private static String cut(String line) {
        return line.substring(START, Math.min(line.length(), LINE_LIMIT));
    }
}
