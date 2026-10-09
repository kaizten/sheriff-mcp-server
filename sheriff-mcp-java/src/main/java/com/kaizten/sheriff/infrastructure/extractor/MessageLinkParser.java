package com.kaizten.sheriff.infrastructure.extractor;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pairs a message class with the text the checker hands it.
 *
 * <p>From the image published on 2026-09-16 a message class carries no text of
 * its own: it is an identity, and the checker passes the description and the
 * howToSolve into its {@code of(...)}. So the text and the code that names it
 * live in two different classes, and pairing them means reading the call
 * rather than the constants. Reading the constants alone leaves every one of
 * these rules with a code and no message, which on that image is 110 of them.
 *
 * <p>The order in the bytecode is what makes the pairing possible:
 *
 * <pre>
 *   112: ldc  // String Method '%s' in file '%s' does not have JavaDoc comment
 *   138: ldc  // String Add a JavaDoc comment to the method '%s' ...
 *   174: invokestatic JavaJavaDocCommentInMethodMessage6KaiztenSheriffErrorMessage.of
 * </pre>
 *
 * <p>The two strings immediately before the call are the description and the
 * howToSolve, in that order. Pairing by the number in {@code Message6} does
 * not work: that rule is the fifth constant its checker declares, not the
 * sixth.
 */
public final class MessageLinkParser {

    private static final String CLASS_LINE_PATTERN =
            "^(?:public |final |abstract )*(?:class|interface) (\\S+)";
    private static final String LDC_PATTERN = "\\bldc(?:_w)?\\s+#\\d+\\s+//\\s*String\\s(.*)$";
    private static final String MESSAGE_OF_PATTERN =
            "\\binvokestatic\\s+#\\d+\\s+//\\s*Method\\s+\\S*?/types/(\\w+)KaiztenSheriffErrorMessage\\.of:";
    private static final String CODE_START_PATTERN = "^\\s*Code:\\s*$";

    private static final Pattern CLASS_LINE = Pattern.compile(CLASS_LINE_PATTERN);
    private static final Pattern LDC_STRING = Pattern.compile(LDC_PATTERN);
    private static final Pattern MESSAGE_OF = Pattern.compile(MESSAGE_OF_PATTERN);
    private static final Pattern CODE_START = Pattern.compile(CODE_START_PATTERN);

    private static final String LINE_SEPARATOR_PATTERN = "\\R";
    private static final int NOT_FOUND = -1;
    private static final int STRINGS_PER_MESSAGE = 2;
    private static final int DESCRIPTION_OFFSET = 2;
    private static final int HOW_TO_SOLVE_OFFSET = 1;
    private static final String EMPTY_TEXT = "";
    private static final String DEFAULT_MARKER = ".";
    private static final String DOT = ".";
    private static final String SLASH = "/";
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";

    private static final String[][] JAVAP_ESCAPES = {
        {"\\n", "\n"},
        {"\\r", "\r"},
        {"\\t", "\t"},
        {"\\'", "'"},
        {"\\\"", "\""},
        {"\\\\", "\\"},
    };

    /**
     * Refuses instantiation: this is a holder of static methods.
     */
    private MessageLinkParser() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * Every message class the dump pairs with its text.
     *
     * @param dump the {@code javap -p -c} output for the checker classes
     * @return reference code to the rule it names, in the order first seen
     */
    public static Map<String, CatalogEntry> parse(String dump) {
        Map<String, CatalogEntry> links = new LinkedHashMap<>();
        List<String> recent = new ArrayList<>();
        String checker = EMPTY_TEXT;
        for (String line : dump.split(LINE_SEPARATOR_PATTERN)) {
            Matcher classLine = CLASS_LINE.matcher(line);
            if (classLine.find()) {
                checker = classLine.group(1);
                recent.clear();
                continue;
            }
            if (CODE_START.matcher(line).matches()) {
                recent.clear();
                continue;
            }
            if (checker.isEmpty()) {
                continue;
            }
            Matcher string = LDC_STRING.matcher(line);
            if (string.find()) {
                remember(recent, unescape(string.group(1).stripTrailing()));
                continue;
            }
            Matcher call = MESSAGE_OF.matcher(line);
            if (!call.find()) {
                continue;
            }
            record(links, recent, checker, call.group(1));
            recent.clear();
        }
        return links;
    }

    /**
     * Keeps one string, unless it repeats the one before it.
     *
     * <p>A constant can be loaded twice in a row, once per branch say, and
     * then "the last two strings" is (howToSolve, howToSolve) and the rule
     * ends up carrying its remedy as its description. Three rules came out
     * that way before this existed.
     *
     * @param recent the strings loaded so far in this method
     * @param loaded the string this instruction loads
     */
    private static void remember(List<String> recent, String loaded) {
        if (recent.isEmpty() || !recent.get(recent.size() - HOW_TO_SOLVE_OFFSET).equals(loaded)) {
            recent.add(loaded);
        }
    }

    /**
     * Adds one pairing, unless the code is already known or no string at all
     * precedes the call.
     *
     * <p>One string means the checker passed it as both the description and
     * the remedy, which a couple of rules really do: the whole method holds a
     * single constant and loads it twice.
     *
     * @param links where to add it
     * @param recent the strings loaded since the method started
     * @param checker the class that raises the message
     * @param code the reference code the message class names
     */
    private static void record(
            Map<String, CatalogEntry> links, List<String> recent, String checker, String code) {
        if (links.containsKey(code) || recent.isEmpty()) {
            return;
        }
        String marker = ConstantsParser.checkerMarkerIn(checker).orElse(DEFAULT_MARKER);
        String[] classified = ConstantsParser.classify(packageOf(checker), marker);
        String howToSolve = recent.get(recent.size() - HOW_TO_SOLVE_OFFSET);
        String description = recent.size() < STRINGS_PER_MESSAGE
                ? howToSolve
                : recent.get(recent.size() - DESCRIPTION_OFFSET);
        links.put(code, new CatalogEntry(
                new SheriffRule(code, description, howToSolve, classified[0], classified[1], EMPTY_TEXT, List.of()),
                code, CatalogEntry.MESSAGE_CLASS_SOURCE, slashed(checker)));
    }

    /**
     * Undoes the escaping javap applies to the strings it prints.
     *
     * @param text the string as javap printed it
     * @return the string as the class holds it
     */
    private static String unescape(String text) {
        String plain = text;
        for (String[] escape : JAVAP_ESCAPES) {
            plain = plain.replace(escape[0], escape[1]);
        }
        return plain;
    }

    /**
     * The package a class belongs to.
     *
     * @param fullyQualified the class name, in dotted form
     * @return its package, or the name itself when it has none
     */
    private static String packageOf(String fullyQualified) {
        int lastDot = fullyQualified.lastIndexOf(DOT);
        return lastDot == NOT_FOUND ? fullyQualified : fullyQualified.substring(0, lastDot);
    }

    /**
     * A class name in the slash form the profile mapping uses.
     *
     * @param fullyQualified the class name, in dotted form
     * @return the same name with slashes
     */
    private static String slashed(String fullyQualified) {
        return fullyQualified.replace(DOT, SLASH);
    }
}
