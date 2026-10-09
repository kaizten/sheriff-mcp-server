package com.kaizten.sheriff.domain;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which findings Sheriff can repair by itself, without asking a model.
 *
 * <p>Of the 282 rules Sheriff names with a reference code, 85 ship a fixer of
 * their own — a small jar or a Python script. Running those first is free in
 * every sense that matters: no tokens, no waiting, and no chance of a model
 * misreading the instruction. Only what survives that pass is worth paying an
 * AI to fix.
 *
 * <p>Two things bound what this can do, and both come from the findings
 * themselves rather than from a preference. A finding with no reference code
 * cannot be handed to {@code --fixers}, and a reference code with no fixer has
 * nothing to hand it to. On the fixtures in this repository that leaves very
 * little; on a codebase with unsorted imports or stray blank lines it is most
 * of the work.
 */
public final class DeterministicPolicy {

    private static final String UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Never called: this class is a namespace for two pure functions, not
     * something with instances.
     *
     * <p>Private and throwing, rather than merely private, so that reflection
     * cannot quietly produce one either.
     */
    private DeterministicPolicy() {
        throw new UnsupportedOperationException(UTILITY_CLASS);
    }

    /**
     * The reference codes worth handing to Sheriff's own fixer.
     *
     * @param findings what the analyzer reported
     * @param rules the catalog, which is what knows whether a rule has a fixer
     * @return those codes, without duplicates, in the order they were reported
     */
    public static List<String> fixableCodes(List<SheriffFinding> findings, List<SheriffRule> rules) {
        Map<String, SheriffRule> byCode = new java.util.LinkedHashMap<>();
        for (SheriffRule rule : rules) {
            byCode.put(rule.code(), rule);
        }
        Set<String> codes = new LinkedHashSet<>();
        for (SheriffFinding finding : findings) {
            String code = finding.referenceCode();
            SheriffRule rule = code.isEmpty() ? null : byCode.get(code);
            if (rule != null && rule.hasFixer()) {
                codes.add(code);
            }
        }
        return new ArrayList<>(codes);
    }

    /**
     * How many of a set of findings the deterministic pass could take on.
     *
     * <p>Worth reporting before the pass runs: it is the difference between
     * "this will save an AI call" and "this will cost a Docker run for
     * nothing".
     *
     * @param findings what the analyzer reported
     * @param rules the catalog
     * @return how many findings carry a code Sheriff can fix itself
     */
    public static long countFixable(List<SheriffFinding> findings, List<SheriffRule> rules) {
        List<String> codes = fixableCodes(findings, rules);
        return findings.stream().filter(finding -> codes.contains(finding.referenceCode())).count();
    }
}
