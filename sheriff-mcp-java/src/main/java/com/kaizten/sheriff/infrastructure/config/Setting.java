package com.kaizten.sheriff.infrastructure.config;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One setting a user can change: its name, which is also the environment
 * variable that sets it, its default, what values it takes, and whether a
 * project may set it for everyone who opens it.
 *
 * @param name the setting, as an environment variable names it
 * @param defaultValue what it is when nothing sets it, as a person reads it;
 *     empty when the default is worked out
 * @param kind what values it takes
 * @param minimum the least number it takes, for a number
 * @param choices the values it takes, for a choice
 * @param project whether a project's {@code .sheriff.properties} may set it
 * @param help what it does, in a line
 */
public record Setting(String name, String defaultValue, SettingKind kind, int minimum, List<String> choices,
        boolean project, String help) {

    private static final int NONE = 0;
    private static final int ONE = 1;
    private static final int ALL = 100;
    private static final String OFF = "0";
    private static final String FIELD_SEPARATOR = "\t";
    private static final int FIELDS = 5;
    private static final int NAME_FIELD = 0;
    private static final int KIND_FIELD = 1;
    private static final int DEFAULT_FIELD = 2;
    private static final int SCOPE_FIELD = 3;
    private static final int HELP_FIELD = 4;
    private static final String KIND_SEPARATOR = ":";
    private static final int KIND_PARTS = 2;
    private static final int NAME_PART = 0;
    private static final int DETAIL_PART = 1;
    private static final String CHOICE_LIST = ",";
    private static final String PROJECT = "project";
    private static final String NOTHING = "";
    private static final String NOT_A_LINE = "Not a line of the settings catalog: '%s'.";
    private static final String ON = "1";
    private static final String NOT_A_NUMBER = "%s takes a whole number, not '%s'.";
    private static final String TOO_SMALL = "%s takes %d or more, not '%s'.";
    private static final String NOT_A_SHARE = "%s takes a share from 0 to 100, not '%s'.";
    private static final String NOT_A_CHOICE = "%s takes one of %s, not '%s'.";
    private static final String NOT_A_WORD = "%s takes a value with no spaces, not '%s'.";
    private static final String NOT_MUTATORS = "%s takes PIT mutators in capitals, separated by commas, not '%s'.";
    private static final String CHOICE_SEPARATOR = ", ";
    private static final String WHITESPACE = ".*\\s.*";
    private static final String MUTATOR_NAMES = "[A-Z0-9_]+(,[A-Z0-9_]+)*";
    private static final Pattern MUTATOR_LIST = Pattern.compile(MUTATOR_NAMES);

    /**
     * A setting as one line of the catalog writes it: its name, its kind
     * ({@code count:1} and {@code choice:a,b} carry their minimum and their
     * values), its default, who may set it, and what it does, separated by
     * tabs.
     *
     * @param line the line
     * @return that setting
     * @throws IllegalStateException when the line is not one
     */
    static Setting parse(String line) {
        String[] fields = line.split(FIELD_SEPARATOR, FIELDS);
        if (fields.length != FIELDS) {
            throw new IllegalStateException(String.format(NOT_A_LINE, line));
        }
        String[] kind = fields[KIND_FIELD].split(KIND_SEPARATOR, KIND_PARTS);
        String detail = kind.length == KIND_PARTS ? kind[DETAIL_PART] : NOTHING;
        SettingKind settingKind = SettingKind.valueOf(kind[NAME_PART].toUpperCase(Locale.ROOT));
        int least = settingKind == SettingKind.COUNT ? Integer.parseInt(detail) : NONE;
        List<String> choices = settingKind == SettingKind.CHOICE ? List.of(detail.split(CHOICE_LIST)) : List.of();
        return new Setting(fields[NAME_FIELD], fields[DEFAULT_FIELD], settingKind, least, choices,
                PROJECT.equals(fields[SCOPE_FIELD]), fields[HELP_FIELD]);
    }

    /**
     * Why a value is not one this setting takes. The empty value always is:
     * it puts the default back.
     *
     * @param value the value, trimmed
     * @return the reason, or empty when the value is fine
     */
    public Optional<String> problemWith(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return switch (kind) {
            case SECONDS, COUNT -> numberProblem(value, kind == SettingKind.SECONDS ? ONE : minimum);
            case SHARE -> shareProblem(value);
            case FLAG -> OFF.equals(value) || ON.equals(value) ? Optional.empty()
                    : Optional.of(String.format(NOT_A_CHOICE, name, OFF + CHOICE_SEPARATOR + ON, value));
            case CHOICE -> choices.contains(value) ? Optional.empty()
                    : Optional.of(String.format(NOT_A_CHOICE, name, String.join(CHOICE_SEPARATOR, choices), value));
            case WORD -> value.matches(WHITESPACE) ? Optional.of(String.format(NOT_A_WORD, name, value))
                    : Optional.empty();
            case MUTATORS -> MUTATOR_LIST.matcher(value).matches() ? Optional.empty()
                    : Optional.of(String.format(NOT_MUTATORS, name, value));
        };
    }

    /**
     * Why a value is not a whole number from a minimum.
     *
     * @param value the value
     * @param least the minimum
     * @return the reason, or empty
     */
    private Optional<String> numberProblem(String value, int least) {
        try {
            return Integer.parseInt(value) < least ? Optional.of(String.format(TOO_SMALL, name, least, value))
                    : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.of(String.format(NOT_A_NUMBER, name, value));
        }
    }

    /**
     * Why a value is not a share from 0 to 100.
     *
     * @param value the value
     * @return the reason, or empty
     */
    private Optional<String> shareProblem(String value) {
        try {
            int share = Integer.parseInt(value);
            return share < NONE || share > ALL ? Optional.of(String.format(NOT_A_SHARE, name, value))
                    : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.of(String.format(NOT_A_SHARE, name, value));
        }
    }
}
