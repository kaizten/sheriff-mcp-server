package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The code Sheriff accepts, shown for the rules a model fixes by hand.
 */
class AcceptedCodeTest {

    private static SheriffFinding finding(String code) {
        return new SheriffFinding("A.java", "description", "how", code, SheriffFinding.ERROR, Map.of());
    }

    @Test
    @DisplayName("each shape once, however many of its rules the findings name")
    void showsEachShapeOnce() {
        String text = AcceptedCode.forFindings(List.of(finding("JavaJavaDocCommentInMethodMessage6"),
                finding("JavaJavaDocCommentInMethodMessage2"), finding("HardcodedStringInMethodCall")));

        assertTrue(text.contains("What Sheriff accepts"), text);
        assertEquals(1, text.split("@return the owner", -1).length - 1, text);
        assertTrue(text.contains("private static final String OWNER"), text);
    }

    @Test
    void saysNothingForRulesItHasNoShapeFor() {
        assertEquals("", AcceptedCode.forFindings(List.of(finding("SortedImport"))));
    }

    @Test
    @DisplayName("every code the file names is one Sheriff has: a typo would hide its shape for good")
    void namesOnlyRulesSheriffHas() throws IOException {
        Path catalog = Path.of("rules_catalog.json");
        Assumptions.assumeTrue(Files.exists(catalog), "the extracted catalog is not present");
        Set<String> known = new HashSet<>();
        for (JsonNode rule : new ObjectMapper().readTree(catalog.toFile()).path("rules")) {
            known.add(rule.path("code").asText());
        }

        Set<String> unknown = new HashSet<>(AcceptedCode.codes());
        unknown.removeAll(known);

        assertEquals(Set.of(), unknown);
    }
}
