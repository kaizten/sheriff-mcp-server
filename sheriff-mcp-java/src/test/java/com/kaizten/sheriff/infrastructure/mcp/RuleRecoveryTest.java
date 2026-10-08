package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Recovering a rule from a rendered message, the way a finding with no
 * {@code referenceCode} of its own is still named.
 */
final class RuleRecoveryTest {

    private static final String JAVADOC_CODE = "JavaJavaDocCommentInMethodMessage6";
    private static final String JAVADOC_TEMPLATE = "Method '%s' in file '%s' does not have JavaDoc comment";
    private static final String HARDCODED_CODE = "JavaHardcodedStringMessage1";
    private static final String HARDCODED_TEMPLATE = "Hardcoded value '%s' found at line %d";

    private static SheriffRule rule(String code, String description) {
        return new SheriffRule(code, description, "", "java", "", "", List.of("JAVA"));
    }

    @Test
    void recoversTheRuleWhoseTemplateProducedTheMessage() {
        RuleRecovery recovery = new RuleRecovery(List.of(
                rule(JAVADOC_CODE, JAVADOC_TEMPLATE),
                rule(HARDCODED_CODE, HARDCODED_TEMPLATE)));

        Optional<SheriffRule> found = recovery.recover(
                "Method 'fahrenheitToCelsius' in file 'Converter.java' does not have JavaDoc comment");

        assertTrue(found.isPresent());
        assertEquals(JAVADOC_CODE, found.get().code());
    }

    @Test
    void matchesTheIntegerPlaceholderAgainstADigitOnly() {
        RuleRecovery recovery = new RuleRecovery(List.of(rule(HARDCODED_CODE, HARDCODED_TEMPLATE)));

        Optional<SheriffRule> found = recovery.recover("Hardcoded value '42' found at line 17");

        assertTrue(found.isPresent());
        assertEquals(HARDCODED_CODE, found.get().code());
    }

    @Test
    void answersEmptyWhenNoTemplateMatches() {
        RuleRecovery recovery = new RuleRecovery(List.of(rule(JAVADOC_CODE, JAVADOC_TEMPLATE)));

        assertTrue(recovery.recover("Something entirely unrelated happened.").isEmpty());
    }

    @Test
    void answersEmptyForABlankDescription() {
        RuleRecovery recovery = new RuleRecovery(List.of(rule(JAVADOC_CODE, JAVADOC_TEMPLATE)));

        assertTrue(recovery.recover("   ").isEmpty());
    }

    @Test
    void aTemplateThatIsOnlyAPlaceholderNeverMatches() {
        RuleRecovery recovery = new RuleRecovery(List.of(rule(JAVADOC_CODE, "%s")));

        assertTrue(recovery.recover("Anything at all").isEmpty());
    }

    @Test
    void prefersTheLongestMatchingTemplateOnAnAmbiguousMessage() {
        RuleRecovery recovery = new RuleRecovery(List.of(
                rule("VAGUE", "%s is wrong"),
                rule("SPECIFIC", "Method '%s' is wrong")));

        Optional<SheriffRule> found = recovery.recover("Method 'compute' is wrong");

        assertEquals("SPECIFIC", found.orElseThrow().code());
    }
}
