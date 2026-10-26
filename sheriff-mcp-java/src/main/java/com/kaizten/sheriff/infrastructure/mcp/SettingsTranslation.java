package com.kaizten.sheriff.infrastructure.mcp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * The settings page's words for its sections and settings in one more
 * language than the catalog's: each section's title, and each setting's name
 * for people and what it does. The catalog stays the one source of the
 * settings themselves; this only says them in another language.
 *
 * @param language the language, as a browser names it ({@code es})
 * @param sections each section's title in it, by the catalog's title
 * @param labels each setting's name for people in it, by the setting
 * @param helps what each setting does, in it, by the setting
 */
public record SettingsTranslation(String language, Map<String, String> sections, Map<String, String> labels,
        Map<String, String> helps) {

    private static final String SECTION_START = "## ";
    private static final String COMMENT_START = "#";
    private static final String LINE_BREAK = "\\R";
    private static final String FIELD_SEPARATOR = "\t";
    private static final int SECTION_FIELDS = 2;
    private static final int SETTING_FIELDS = 3;
    private static final int FIRST = 0;
    private static final int SECOND = 1;
    private static final int THIRD = 2;
    private static final String NO_TRANSLATION = "The settings page's translation %s is not in this jar.";
    private static final String NOT_A_LINE = "Not a line of the settings page's translation: '%s'.";

    /**
     * Reads a translation this jar carries: a line {@code ## <title>\t<its
     * title>} per section, a line {@code <setting>\t<name>\t<what it does>}
     * per setting, and blank lines and comments, which start with {@code #},
     * between them.
     *
     * @param language the language it is in
     * @param resource the translation, as a resource of this jar
     * @return that translation
     * @throws IllegalStateException when it is missing or a line of it is
     *     neither a section nor a setting
     */
    static SettingsTranslation read(String language, String resource) {
        InputStream input = SettingsTranslation.class.getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalStateException(String.format(NO_TRANSLATION, resource));
        }
        Map<String, String> sections = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, String> helps = new LinkedHashMap<>();
        try (Scanner lines = new Scanner(input, StandardCharsets.UTF_8)) {
            for (String line : lines.useDelimiter(LINE_BREAK).tokens().toList()) {
                if (line.startsWith(SECTION_START)) {
                    String[] fields = fields(line.substring(SECTION_START.length()), SECTION_FIELDS, line);
                    sections.put(fields[FIRST].strip(), fields[SECOND].strip());
                } else if (!line.isBlank() && !line.startsWith(COMMENT_START)) {
                    String[] fields = fields(line, SETTING_FIELDS, line);
                    labels.put(fields[FIRST], fields[SECOND]);
                    helps.put(fields[FIRST], fields[THIRD]);
                }
            }
        }
        return new SettingsTranslation(language, Map.copyOf(sections), Map.copyOf(labels), Map.copyOf(helps));
    }

    /**
     * A line's fields, as many as it must have.
     *
     * @param text what to split
     * @param count how many fields it must have
     * @param line the whole line, to say which one is wrong
     * @return its fields
     * @throws IllegalStateException when it has another number of them
     */
    private static String[] fields(String text, int count, String line) {
        String[] fields = text.split(FIELD_SEPARATOR, count);
        if (fields.length != count) {
            throw new IllegalStateException(String.format(NOT_A_LINE, line));
        }
        return fields;
    }
}
