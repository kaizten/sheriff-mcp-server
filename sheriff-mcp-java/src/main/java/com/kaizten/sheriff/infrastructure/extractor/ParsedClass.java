package com.kaizten.sheriff.infrastructure.extractor;

import java.util.ArrayList;
import java.util.List;

/**
 * A class from a javap dump, and the message constants it declares, in
 * declaration order.
 *
 * <p>Declaration order is not incidental: a checker pairs each error message
 * with the remedy that follows it, and no name-matching rule reproduces that
 * pairing.
 *
 * <p>Its own file rather than a nested class, for the same reason as every
 * other type in this port — Sheriff's hexagonal profile rejects nested types,
 * and the rule keeps being right.
 */
final class ParsedClass {

    private final String name;
    private final List<String[]> constants = new ArrayList<>();

    /**
     * Starts reading a class.
     *
     * @param name its fully qualified name
     */
    ParsedClass(String name) {
        this.name = name;
    }

    /**
     * Records one message constant.
     *
     * @param constantName the constant's name
     * @param value its text
     */
    void add(String constantName, String value) {
        constants.add(new String[] {constantName, value});
    }

    /**
     * The class's fully qualified name.
     *
     * @return that name
     */
    String name() {
        return name;
    }

    /**
     * Every message constant, in declaration order.
     *
     * @return name and value pairs
     */
    List<String[]> constants() {
        return constants;
    }

    /**
     * What this rule says when it fires.
     *
     * @return the distinct error messages
     */
    List<String> descriptions() {
        return ConstantsParser.uniqueValues(constants, false);
    }

    /**
     * What this rule says to do about it.
     *
     * @return the distinct remedies
     */
    List<String> howToSolve() {
        return ConstantsParser.uniqueValues(constants, true);
    }
}
