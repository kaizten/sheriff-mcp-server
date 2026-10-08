package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Sheriff accepts for the rules a model has to fix by hand, as Java it
 * can copy, from {@code accepted-code.md}.
 *
 * <p>A finding says what is wrong ("Method 'find' does not have JavaDoc
 * comment") and its {@code howToSolve} repeats it as an order; neither shows
 * the code that passes. The catalog's examples are the Python of Sheriff's
 * own fixers, which only exist for the rules a model never sees. So a model
 * guessed, and a guess that Sheriff still rejects is a round trip, or a
 * model that gives up. Every shape in that file was checked against the image
 * of 5 October: a class written with all of them draws 0 errors under
 * {@code JAVA}.
 */
public final class AcceptedCode {

    private static final String RESOURCE = "/accepted-code.md";
    private static final String HEADING = "## ";
    private static final String CODE_SEPARATOR = " ";
    private static final String NEWLINE = "\n";
    private static final String INTRODUCTION = "%n%nWhat Sheriff accepts, for the rules above:%n";
    private static final String NOTHING = "";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final Map<String, String> BY_CODE = load();

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private AcceptedCode() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The accepted shape of every rule among some findings, each once, in
     * the order the findings first name them.
     *
     * @param findings the findings left to fix by hand
     * @return the shapes under one heading, or nothing when none is known
     */
    public static String forFindings(List<SheriffFinding> findings) {
        Set<String> shapes = new LinkedHashSet<>();
        for (SheriffFinding finding : findings) {
            String shape = BY_CODE.get(finding.referenceCode());
            if (shape != null) {
                shapes.add(shape);
            }
        }
        if (shapes.isEmpty()) {
            return NOTHING;
        }
        return String.format(INTRODUCTION) + String.join(NEWLINE + NEWLINE, shapes);
    }

    /**
     * Every rule the file has a shape for.
     *
     * @return their codes
     */
    static Set<String> codes() {
        return BY_CODE.keySet();
    }

    /**
     * Reads the file: a heading of rule codes, then the shape they share.
     *
     * @return each code's shape, the same text for every code of a heading
     */
    private static Map<String, String> load() {
        Map<String, String> byCode = new LinkedHashMap<>();
        try (InputStream input = AcceptedCode.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return byCode;
            }
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            List<String> codes = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (String line : content.split(NEWLINE)) {
                if (line.startsWith(HEADING)) {
                    keep(byCode, codes, lines);
                    codes = List.of(line.substring(HEADING.length()).strip().split(CODE_SEPARATOR));
                    lines = new ArrayList<>();
                } else {
                    lines.add(line);
                }
            }
            keep(byCode, codes, lines);
        } catch (IOException exception) {
            return byCode;
        }
        return byCode;
    }

    /**
     * Records one heading's shape for each of its codes.
     *
     * @param byCode where the shapes go
     * @param codes the heading's codes
     * @param lines the shape's lines
     */
    private static void keep(Map<String, String> byCode, List<String> codes, List<String> lines) {
        String shape = String.join(NEWLINE, lines).strip();
        if (!shape.isEmpty()) {
            codes.forEach(code -> byCode.put(code, shape));
        }
    }
}
