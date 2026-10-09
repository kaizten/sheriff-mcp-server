package com.kaizten.sheriff.domain.port;

/**
 * The text of the script Sheriff runs to repair a rule.
 *
 * <p>Worth a port of its own because it is the only place Sheriff's rules are
 * written down exactly. A rule's description says what is wrong and its
 * howToSolve says the same thing in other words; neither says what the
 * accepted code looks like. The fixer script does — the snippet it writes
 * <em>is</em> the accepted code — so this is the catalog's most precise
 * answer to "what does Sheriff expect?", and the image is not the only place
 * it could come from.
 */
@FunctionalInterface
public interface FixerScriptSource {

    /**
     * Reads one fixer script.
     *
     * @param scriptPath the script's path, relative to the fixers directory,
     *     as the catalog records it
     * @return the script's text
     * @throws IllegalArgumentException when the path is not one this will read
     * @throws IllegalStateException when the script cannot be read
     */
    String read(String scriptPath);
}
