package com.kaizten.sheriff.domain.valueobject;

import java.util.List;

/**
 * The rules in force for a profile, and whether that answer is exact.
 *
 * <p>The flag is the point of this type. When the catalog does not record what
 * a profile runs, the fallback is every rule of that profile's language, which
 * is a superset — and presenting a superset as "the rules in force" is how a
 * fixer ends up restructuring code to satisfy a rule that was never running.
 *
 * @param rules the rules to apply
 * @param exact {@code true} when the catalog knew this profile specifically
 */
public record RuleSelection(List<SheriffRule> rules, boolean exact) {

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value, so an absent one is refused
     * where it appears rather than replaced by something harmless-looking.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public RuleSelection {
        if (rules == null) {
            throw new IllegalArgumentException(ERROR_RULES_NOT_DEFINED);
        }
        rules = List.copyOf(rules);
    }

    private static final String ERROR_RULES_NOT_DEFINED = "Rules are not defined";

    /**
     * Whether there is anything to tell the fixer about.
     *
     * @return {@code true} when no rule applies
     */
    public boolean isEmpty() {
        return rules.isEmpty();
    }
}
