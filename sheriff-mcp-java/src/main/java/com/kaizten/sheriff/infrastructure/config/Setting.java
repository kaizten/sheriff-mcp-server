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
 * @param projectImage the one image repository a project may set it to, a
 *     tag or a digest of it; empty when a project may set any value
 * @param help what it does, in a line
 * @param section the part of the catalog it is listed under, empty for none
 */
public record Setting(String name, String defaultValue, SettingKind kind, int minimum, List<String> choices,
        boolean project, String projectImage, String help, String section) {

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
    private static final String SCOPE_SEPARATOR = ":";
    private static final int SCOPE_PARTS = 2;
    private static final String TAG_SEPARATOR = ":";
    private static final String DIGEST_SEPARATOR = "@";
    private static final String NOT_THAT_IMAGE = "%s takes only %s in a project's file, or a tag or a digest of it "
            + "(%s@sha256:...), not '%s': a project that is cloned must not choose which image runs on the machine "
            + "of whoever opens it. Set another image in your own file.";
    private static final String NOTHING = "";
    private static final String NOT_A_LINE = "Not a line of the settings catalog: '%s'.";
    private static final String ON = "1";
    private static final String NOT_A_NUMBER = "%s takes a whole number, not '%s'.";
    private static final String TOO_SMALL = "%s takes %d or more, not '%s'.";
    private static final String NOT_A_SHARE = "%s takes a share from 0 to 100, not '%s'.";
    private static final String NOT_A_CHOICE = "%s takes one of %s, not '%s'.";
    private static final String NOT_A_WORD = "%s takes a value with no spaces or backslashes, not '%s'.";
    private static final String NOT_MUTATORS = "%s takes PIT mutators in capitals, separated by commas, not '%s'.";
    private static final String CHOICE_SEPARATOR = ", ";
    private static final String TAKES_SECONDS = "Takes a whole number of seconds, 1 or more.";
    private static final String TAKES_COUNT = "Takes a whole number, %d or more.";
    private static final String TAKES_SHARE = "Takes a share from 0 to 100, with or without %.";
    private static final String PERCENT = "%";
    private static final String SPACE = " ";
    private static final String TAKES_FLAG = "Takes 0 or 1.";
    private static final String TAKES_CHOICE = "Takes one of %s.";
    private static final String TAKES_WORD = "Takes a value with no spaces or backslashes.";
    private static final String TAKES_MUTATORS = "Takes PIT mutators or groups in capitals, separated by commas.";
    private static final String TAKES_EMPTY = " Empty puts the default back.";
    private static final String WHITESPACE = "(?s).*[\\s\\\\].*";
    private static final String MUTATOR_NAMES = "[A-Z0-9_]+(,[A-Z0-9_]+)*";
    private static final Pattern MUTATOR_LIST = Pattern.compile(MUTATOR_NAMES);
    private static final String LINE_BREAK_OR_BACKSLASH = "(?s).*[\\r\\n\\\\].*";
    private static final String NOT_A_COMMAND = "%s takes a command on one line, with no backslashes (write a "
            + "path with /), not '%s'.";
    private static final String TAKES_COMMAND = "Takes a command on one line, its words separated by spaces, with no "
            + "backslashes.";
    private static final String WEB_ADDRESS = "https?://[^\\s\\\\]+";
    private static final String NOT_AN_ADDRESS = "%s takes an address that starts with http:// or https://, not '%s'.";
    private static final String TAKES_ADDRESS = "Takes an address that starts with http:// or https://.";

    /**
     * A setting as one line of the catalog writes it: its name, its kind
     * ({@code count:1} and {@code choice:a,b} carry their minimum and their
     * values), its default, who may set it ({@code project:<image>} lets a
     * project set it only to that image, a tag or a digest of it), and what
     * it does, separated by tabs.
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
        String[] scope = fields[SCOPE_FIELD].split(SCOPE_SEPARATOR, SCOPE_PARTS);
        String image = scope.length == SCOPE_PARTS ? scope[DETAIL_PART] : NOTHING;
        return new Setting(fields[NAME_FIELD], fields[DEFAULT_FIELD], settingKind, least, choices,
                PROJECT.equals(scope[NAME_PART]), image, fields[HELP_FIELD], NOTHING);
    }

    /**
     * The same setting, listed under a part of the catalog.
     *
     * @param title the part's title
     * @return that setting
     */
    Setting inSection(String title) {
        return new Setting(name, defaultValue, kind, minimum, choices, project, projectImage, help, title);
    }

    /**
     * The same setting under another name, as a project's own key for it
     * ({@code coverage} for {@code SHERIFF_COVERAGE}) is checked, so that
     * what is wrong is said in the name that was written.
     *
     * @param otherName the name
     * @return that setting
     */
    public Setting named(String otherName) {
        return new Setting(otherName, defaultValue, kind, minimum, choices, project, projectImage, help, section);
    }

    /**
     * Why a project's file may not give this setting a value it may
     * otherwise set: an image outside the one repository it allows.
     *
     * @param value the value, trimmed
     * @return the reason, or empty when a project may set it
     */
    public Optional<String> projectProblemWith(String value) {
        if (projectImage.isEmpty() || value.isEmpty() || value.equals(projectImage)
                || value.startsWith(projectImage + TAG_SEPARATOR) || value.startsWith(projectImage + DIGEST_SEPARATOR)) {
            return Optional.empty();
        }
        return Optional.of(String.format(NOT_THAT_IMAGE, name, projectImage, projectImage, value));
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
            case MUTATORS -> MUTATOR_LIST.matcher(value.replace(SPACE, NOTHING)).matches() ? Optional.empty()
                    : Optional.of(String.format(NOT_MUTATORS, name, value));
            case COMMAND -> value.matches(LINE_BREAK_OR_BACKSLASH)
                    ? Optional.of(String.format(NOT_A_COMMAND, name, value)) : Optional.empty();
            case ADDRESS -> value.matches(WEB_ADDRESS) ? Optional.empty()
                    : Optional.of(String.format(NOT_AN_ADDRESS, name, value));
        };
    }

    /**
     * The values this setting takes, as a person reads them: the same that
     * {@link #problemWith(String)} lets through.
     *
     * @return that sentence
     */
    public String accepted() {
        String values = switch (kind) {
            case SECONDS -> TAKES_SECONDS;
            case COUNT -> String.format(TAKES_COUNT, minimum);
            case SHARE -> TAKES_SHARE;
            case FLAG -> TAKES_FLAG;
            case CHOICE -> String.format(TAKES_CHOICE, String.join(CHOICE_SEPARATOR, choices));
            case WORD -> TAKES_WORD;
            case MUTATORS -> TAKES_MUTATORS;
            case COMMAND -> TAKES_COMMAND;
            case ADDRESS -> TAKES_ADDRESS;
        };
        return values + TAKES_EMPTY;
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
            int share = Integer.parseInt(value.replace(PERCENT, NOTHING));
            return share < NONE || share > ALL ? Optional.of(String.format(NOT_A_SHARE, name, value))
                    : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.of(String.format(NOT_A_SHARE, name, value));
        }
    }
}
