package com.kaizten.sheriff.infrastructure.extractor;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.List;

/**
 * One rule as the extractor knows it, which is more than the domain needs.
 *
 * <p>Two extra fields exist only while the catalog is being built. {@code
 * owner} is the class that carries the rule, and it is what makes profile
 * attribution possible: a profile runs a rule when its root checker can reach
 * that class. {@code source} records whether Sheriff names the rule with a
 * reference code of its own or whether this project had to name it after the
 * checker that owns it — a distinction worth keeping, because one of those
 * names is Sheriff's and the other is ours.
 *
 * @param rule the rule itself, as the rest of the agent sees it
 * @param referenceCode Sheriff's own code, empty for checker-owned rules
 * @param source where the text came from
 * @param owner the class carrying it, in slash form
 */
public record CatalogEntry(SheriffRule rule, String referenceCode, String source, String owner) {

    /**
     * Rules Sheriff reports with a reference code of its own, whose text comes
     * out of an error-message class.
     */
    public static final String MESSAGE_CLASS_SOURCE = "message-class";

    /**
     * Rules Sheriff reports with an empty reference code, named here after the
     * checker class that builds the message.
     */
    public static final String CHECKER_SOURCE = "checker";

    /**
     * The same entry with its profiles filled in.
     *
     * @param profiles the profiles that run this rule
     * @return a new entry carrying them
     */
    public CatalogEntry withProfiles(List<String> profiles) {
        return with(rule.fixer(), profiles, rule.example());
    }

    /**
     * The same entry with its fixer filled in.
     *
     * @param fixer the path of Sheriff's own fixer, empty when it has none
     * @return a new entry carrying it
     */
    public CatalogEntry withFixer(String fixer) {
        return with(fixer, rule.profiles(), rule.example());
    }

    /**
     * The same entry carrying the code Sheriff writes to satisfy the rule.
     *
     * @param example that function, verbatim from the fixer's own script
     * @return a new entry carrying it
     */
    public CatalogEntry withExample(String example) {
        return with(rule.fixer(), rule.profiles(), example);
    }

    /**
     * Rebuilds the entry, changing only the three fields that get filled in
     * after the rule is first read.
     *
     * <p>One place on purpose. When each of the three did its own rebuild,
     * adding a component to the rule meant remembering to carry it in all of
     * them, and the one that forgot silently emptied the field for every
     * rule in the catalog.
     *
     * @param fixer the fixer path
     * @param profiles the profiles that run the rule
     * @param example the code Sheriff writes for it
     * @return the rebuilt entry
     */
    private CatalogEntry with(String fixer, List<String> profiles, String example) {
        return new CatalogEntry(
                new SheriffRule(rule.code(), rule.description(), rule.howToSolve(), rule.language(),
                        rule.category(), fixer, profiles, example),
                referenceCode, source, owner);
    }

    /**
     * This rule's code, which is what everything is keyed by.
     *
     * @return the code
     */
    public String code() {
        return rule.code();
    }
}
