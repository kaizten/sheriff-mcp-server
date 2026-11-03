package com.kaizten.sheriff.infrastructure.docker;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The GitHub token Sheriff is given for the checks that need one: those of a
 * {@code pom.xml}'s dependencies on GitHub Packages, where Kaizten's own are.
 *
 * <p>Read from {@code GITHUB_TOKEN}, a variable alone, as every key is.
 * Measured on the image of 2 November ({@code sha256:1522c9a2}): {@code test}
 * and {@code fix} take it only as {@code --github-token}, not from the
 * environment, and refuse to run at all, with their usage banner and exit
 * code 0, when it does not have the form a GitHub token has. So one without
 * that form is not passed: the analysis runs without it, as with none.
 *
 * <p>The {@code sheriff.github.token} property wins over the variable, even
 * empty, so the tests, which set it empty, never pass the token of whoever
 * runs them.
 */
public final class GitHubToken {

    private static final String VARIABLE = "GITHUB_TOKEN";
    private static final String PROPERTY = "sheriff.github.token";
    private static final String ACCEPTED_REGEX = "^(?:ghp|github_pat|gho|ghu|ghs|ghr)_[A-Za-z0-9_-]+$";
    private static final Pattern ACCEPTED = Pattern.compile(ACCEPTED_REGEX);
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Not to be instantiated.
     */
    private GitHubToken() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The token this process was given.
     *
     * @return the property's value when it is set, otherwise the variable's,
     *     when it has the form Sheriff takes; empty otherwise
     */
    public static Optional<String> fromProcess() {
        String property = System.getProperty(PROPERTY);
        return accepted(property != null ? property : System.getenv(VARIABLE));
    }

    /**
     * A value, when it has the form Sheriff takes for a token.
     *
     * @param value the value, possibly {@code null}
     * @return the value without surrounding blanks, or empty when it has not
     */
    static Optional<String> accepted(String value) {
        return Optional.ofNullable(value).map(String::strip).filter(token -> ACCEPTED.matcher(token).matches());
    }
}
