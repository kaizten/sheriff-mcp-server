package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The findings Sheriff is known to get wrong, which no caller should ever see.
 *
 * <p>{@code FilenamePascalCase} on {@code package-info.java} and
 * {@code module-info.java}: names the Java language itself requires, so no
 * repair exists and every PetClinic session ended on the same five, with the
 * Stop hook blocking until its cap. Only those two files, never the rule: it
 * is right about every other file.
 *
 * <p>Three rules of the image of 29 October ({@code sha256:81767ad1}), read
 * against the component's own source: {@code FourSpaceIndentation} on the
 * lines of a comment that start with {@code *}, its end included, and inside
 * a text block; {@code JavaVariableName} on a {@code static final} constant;
 * and {@code UnusedImport} on an import used as an annotation. Sheriff's
 * JavaDoc rules require the first, Java's naming convention the second, and
 * the compiler the third; on this repository's own code, which the image of
 * 28 October left with no error, the first two were 13,981 of its 14,879,
 * and the third was every {@code UnusedImport} of the Maven plugin. Only
 * those: a tab, a misplaced statement, a variable or an import nothing uses
 * still counts, and a source that cannot be read keeps its finding.
 *
 * <p>Applied once, in {@link SheriffDockerAnalyzer}, which every caller goes
 * through, so the MCP's tools, the hooks, the loop and the Maven plugin agree
 * on the count. No rule can be switched off by a project: an earlier version
 * let one exclude {@code SortedImport}, on the claim that PetClinic's
 * {@code spring-javaformat} rejects Sheriff's import order. Measured on
 * 1 October, it does not: with Sheriff's fixers applied, PetClinic's
 * {@code mvn test} passes, validation included.
 */
public final class KnownFalsePositives {

    private static final String FILE_NAME_RULE = "FilenamePascalCase";
    private static final String INDENTATION_RULE = "FourSpaceIndentation";
    private static final String VARIABLE_NAME_RULE = "JavaVariableName";
    private static final String UNUSED_IMPORT_RULE = "UnusedImport";
    private static final String PACKAGE_INFO = "package-info.java";
    private static final String MODULE_INFO = "module-info.java";
    private static final Set<String> NAMES_JAVA_REQUIRES = Set.of(PACKAGE_INFO, MODULE_INFO);
    private static final String PATH_SEPARATOR = "/";
    private static final String LINE_NUMBER_PATTERN = "at line (\\d+)";
    private static final String CONSTANT_PATTERN = "\\bstatic\\s+final\\b|\\bfinal\\s+static\\b";
    private static final String IMPORTED_PATTERN = "Unused import '(?:static )?([\\w.$]+)'";
    private static final String ANNOTATION_PATTERN = "@%s\\b";
    private static final Pattern LINE_NUMBER = Pattern.compile(LINE_NUMBER_PATTERN);
    private static final Pattern CONSTANT = Pattern.compile(CONSTANT_PATTERN);
    private static final Pattern IMPORTED = Pattern.compile(IMPORTED_PATTERN);
    private static final String NAME_SEPARATOR = ".";
    private static final String COMMENT_CONTINUATION = "*";
    private static final String TEXT_BLOCK_QUOTES = "\"\"\"";
    private static final int FIRST_LINE = 1;
    private static final int NOT_FOUND = -1;
    private static final int EVEN = 2;
    private static final int NONE = 0;
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private KnownFalsePositives() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The findings that count.
     *
     * @param findings what Sheriff reported
     * @param mount the directory the findings' paths are relative to
     * @return the same, without the known false positives
     */
    public static List<SheriffFinding> without(List<SheriffFinding> findings, Path mount) {
        Map<String, Optional<List<String>>> sources = new HashMap<>();
        List<SheriffFinding> kept = new ArrayList<>();
        for (SheriffFinding finding : findings) {
            if (!falsePositive(finding, mount, sources)) {
                kept.add(finding);
            }
        }
        return kept;
    }

    /**
     * Whether a finding is one Sheriff is known to get wrong.
     *
     * @param finding the finding
     * @param mount the directory its path is relative to
     * @param sources the files already read, by path
     * @return {@code true} for a file name the language requires, or a line
     *         or an import one of the three rules is wrong about
     */
    private static boolean falsePositive(SheriffFinding finding, Path mount,
            Map<String, Optional<List<String>>> sources) {
        String file = finding.file();
        String name = file.substring(file.lastIndexOf(PATH_SEPARATOR) + 1);
        String rule = finding.referenceCode();
        if (FILE_NAME_RULE.equals(rule)) {
            return NAMES_JAVA_REQUIRES.contains(name);
        }
        if (!INDENTATION_RULE.equals(rule) && !VARIABLE_NAME_RULE.equals(rule)
                && !UNUSED_IMPORT_RULE.equals(rule)) {
            return false;
        }
        Optional<List<String>> lines = sources.computeIfAbsent(file, path -> read(mount, path));
        if (UNUSED_IMPORT_RULE.equals(rule)) {
            return lines.isPresent() && usedAsAnnotation(finding.description(), lines.get());
        }
        int number = lineNumber(finding.description());
        if (number < FIRST_LINE || lines.isEmpty() || number > lines.get().size()) {
            return false;
        }
        String line = lines.get().get(number - 1);
        if (VARIABLE_NAME_RULE.equals(rule)) {
            return CONSTANT.matcher(line).find();
        }
        return line.strip().startsWith(COMMENT_CONTINUATION) || insideTextBlock(lines.get(), number);
    }

    /**
     * Whether the import a finding names is used as an annotation.
     *
     * @param description the finding's description, which names the import
     * @param lines the file's lines
     * @return {@code true} when its simple name follows an {@code @}
     */
    private static boolean usedAsAnnotation(String description, List<String> lines) {
        Matcher matcher = IMPORTED.matcher(description);
        if (!matcher.find()) {
            return false;
        }
        String imported = matcher.group(1);
        String name = imported.substring(imported.lastIndexOf(NAME_SEPARATOR) + 1);
        Pattern annotation = Pattern.compile(String.format(ANNOTATION_PATTERN, Pattern.quote(name)));
        return lines.stream().anyMatch(line -> annotation.matcher(line).find());
    }

    /**
     * The line a finding names in its description.
     *
     * @param description the finding's description
     * @return the line, or {@code -1} when it names none
     */
    private static int lineNumber(String description) {
        Matcher matcher = LINE_NUMBER.matcher(description);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : NOT_FOUND;
    }

    /**
     * Whether a line is the content of a text block: an odd number of
     * {@code """} before it.
     *
     * @param lines the file's lines
     * @param number the line, counted from 1
     * @return {@code true} inside a text block
     */
    private static boolean insideTextBlock(List<String> lines, int number) {
        int quotes = 0;
        for (String line : lines.subList(0, number - 1)) {
            quotes += occurrences(line);
        }
        return quotes % EVEN != NONE;
    }

    /**
     * How many {@code """} a line holds.
     *
     * @param line the line
     * @return the count
     */
    private static int occurrences(String line) {
        int count = 0;
        int from = line.indexOf(TEXT_BLOCK_QUOTES);
        while (from != NOT_FOUND) {
            count++;
            from = line.indexOf(TEXT_BLOCK_QUOTES, from + TEXT_BLOCK_QUOTES.length());
        }
        return count;
    }

    /**
     * A file's lines.
     *
     * @param mount the directory its path is relative to
     * @param file the file's path, as Sheriff gave it
     * @return its lines, or nothing when it cannot be read
     */
    private static Optional<List<String>> read(Path mount, String file) {
        try {
            return Optional.of(Files.readAllLines(mount.resolve(file), StandardCharsets.UTF_8));
        } catch (IOException | InvalidPathException exception) {
            return Optional.empty();
        }
    }
}
