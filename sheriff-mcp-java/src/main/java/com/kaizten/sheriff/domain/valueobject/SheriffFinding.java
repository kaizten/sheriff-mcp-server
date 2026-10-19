package com.kaizten.sheriff.domain.valueobject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One issue Sheriff reported: a rule that is already broken, in a file, now.
 *
 * <p>The typed fields cover what every {@code --test} profile returns, and
 * {@code raw} keeps whatever else came with it. Sheriff's output shape varies
 * a little between profiles, and silently dropping a field nobody wrote an
 * accessor for is how information gets lost between a tool and its user.
 *
 * @param file the repository-relative path of the offending file
 * @param description what Sheriff says is wrong
 * @param howToSolve what Sheriff says to do about it
 * @param referenceCode Sheriff's identifier for the rule, empty for the rules
 *     whose message is built inside a checker
 * @param type {@code ERROR} or {@code WARNING}
 * @param raw every field of the original entry, including the ones above
 */
public record SheriffFinding(
        String file,
        String description,
        String howToSolve,
        String referenceCode,
        String type,
        Map<String, Object> raw) {

    /**
     * Sheriff's value for an issue that has to be fixed, and the type assumed
     * when Sheriff sends none.
     */
    public static final String ERROR = "ERROR";

    /**
     * Sheriff's value for an issue that is only reported, and never counted
     * against the goal of a clean run.
     */
    public static final String WARNING = "WARNING";

    /**
     * The longest a text is kept by {@link #brief(String)}: twice Sheriff's
     * longest one-line description, so only an output pasted into one is
     * cut.
     */
    public static final int BRIEF_LENGTH = 300;

    private static final String DESCRIBE_FORMAT = "%s: %s%n  -> %s";
    private static final String LINE_BREAK = "\\s*\\R\\s*";
    private static final String SPACE = " ";
    private static final String CUT = "...";
    private static final int START = 0;
    private static final String ERROR_FILE_NOT_DEFINED = "File is not defined";
    private static final String ERROR_DESCRIPTION_NOT_DEFINED = "Description is not defined";
    private static final String ERROR_HOW_TO_SOLVE_NOT_DEFINED = "How to solve is not defined";
    private static final String ERROR_REFERENCE_CODE_NOT_DEFINED = "Reference code is not defined";
    private static final String ERROR_RAW_NOT_DEFINED = "Raw entry is not defined";

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value: turning an absent one into an
     * empty string makes "nothing was reported" and "we lost it on the way
     * here" look identical afterwards, which is how a parsing bug survives a
     * green test run.
     *
     * <p>An empty type is the one thing it does complete rather than refuse:
     * that is Sheriff's own shorthand for an error, which it omits on the
     * findings that have to be fixed.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public SheriffFinding {
        require(file != null, ERROR_FILE_NOT_DEFINED);
        require(description != null, ERROR_DESCRIPTION_NOT_DEFINED);
        require(howToSolve != null, ERROR_HOW_TO_SOLVE_NOT_DEFINED);
        require(referenceCode != null, ERROR_REFERENCE_CODE_NOT_DEFINED);
        require(raw != null, ERROR_RAW_NOT_DEFINED);
        type = type == null || type.isEmpty() ? ERROR : type;
        raw = Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /**
     * Refuses a value the domain does not accept.
     *
     * @param valid whether the value passed its check
     * @param message what to report when it did not
     * @throws IllegalArgumentException when {@code valid} is false
     */
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Whether this is a warning rather than an error.
     *
     * @return {@code true} when Sheriff typed it as a warning
     */
    public boolean isWarning() {
        return WARNING.equals(type);
    }

    /**
     * A text of Sheriff's on one line, and cut at {@link #BRIEF_LENGTH}, for
     * a list that gives one line to each finding. Sheriff quotes code in a
     * description, line breaks included, and the image of 16 October pastes
     * Maven's whole output, twenty-odd lines, into the description of a
     * {@code pom.xml} it cannot build: a hook's message of five findings grew
     * from 14 lines to 34.
     *
     * @param text a description or a how-to-solve
     * @return it, with every line break and the spaces around it one space,
     *     and cut with {@code ...} when it is longer than the limit
     */
    public static String brief(String text) {
        String single = text.strip().replaceAll(LINE_BREAK, SPACE);
        return single.length() <= BRIEF_LENGTH ? single : single.substring(START, BRIEF_LENGTH).stripTrailing() + CUT;
    }

    /**
     * One readable line for the console or for a fixer's prompt.
     *
     * @return the description and how to solve it, or the raw entry when
     *     Sheriff sent neither
     */
    public String describe() {
        if (description.isEmpty() || howToSolve.isEmpty()) {
            return raw.toString();
        }
        return String.format(DESCRIBE_FORMAT, file, description, howToSolve);
    }
}
