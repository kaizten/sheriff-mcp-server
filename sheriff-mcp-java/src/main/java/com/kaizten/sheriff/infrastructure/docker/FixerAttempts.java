package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.DeterministicPolicy;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What one profile's {@code fix} runs did, across a repair's rounds: the
 * rules they were given, the fixers Sheriff ran, and which of those changed
 * nothing.
 *
 * <p>A defence against the image of 5 November 2026 ({@code sha256:23231466}),
 * whose {@code fix} runs a file's first fixer and counts every finding of the
 * file as fixed, even when that fixer changed nothing, so it runs none of the
 * others, and a second {@code fix} on the same state finds no error left: a
 * file with an {@code if} on two lines lost every other repair to the braces
 * fixer, which does not handle that form, run first by the default set.
 *
 * <p>A fixer that ran and left its rule's count where it was is no longer
 * given for the rest of the repair, and from then on the default set, which
 * would run it again first, is not run either. A round is run again for the
 * fixers it kept from running, though the count did not drop; each such
 * round leaves one more fixer out, so they end. Until a fixer changes
 * nothing, nothing here changes a round.
 */
final class FixerAttempts {

    private static final long NONE = 0L;
    private final Set<String> idle = new HashSet<>();
    private Set<String> given = Set.of();
    private Set<String> ran = Set.of();
    private Map<String, Long> countsBefore = Map.of();

    /**
     * The rules worth naming to {@code --fixers}: all but those whose fixer
     * ran and changed nothing.
     *
     * @param codes the rules found
     * @return the same, in order, without those
     */
    List<String> worthGiving(Collection<String> codes) {
        return codes.stream().filter(code -> !idle.contains(code)).toList();
    }

    /**
     * Whether Sheriff's default set may run: only while no fixer has changed
     * nothing, as the default set cannot be told to leave one out.
     *
     * @return {@code true} until a fixer that ran changed nothing
     */
    boolean defaultSetRuns() {
        return idle.isEmpty();
    }

    /**
     * Whether a round has anything to run for some rules: the default set,
     * or a fixer that has not changed nothing.
     *
     * @param codes the rules found
     * @return {@code true} when a {@code fix} is worth running
     */
    boolean worthRunning(Collection<String> codes) {
        return defaultSetRuns() || !worthGiving(codes).isEmpty();
    }

    /**
     * Records one round's {@code fix} runs.
     *
     * @param codes the rules they were given
     * @param fixers the rules whose fixer Sheriff ran, in any of them
     * @param analysis the analysis they repaired
     */
    void ran(Collection<String> codes, Set<String> fixers, AnalysisResult analysis) {
        given = new LinkedHashSet<>(codes);
        ran = new LinkedHashSet<>(fixers);
        countsBefore = countsByRule(analysis);
    }

    /**
     * Measures the last run against the analysis after it: a fixer that ran
     * and left its rule's count where it was changed nothing.
     *
     * @param analysis the analysis after the last run
     * @param rules the catalog, empty when there is none; with one, only a
     *     skipped rule it knows a fixer for is worth a round, as a rule with
     *     none is never run
     * @return {@code true} when a fixer that changed nothing kept Sheriff
     *     from others it was given whose rules are still found, which
     *     another round can now run
     */
    boolean skippedWorthRetrying(AnalysisResult analysis, List<SheriffRule> rules) {
        Map<String, Long> counts = countsByRule(analysis);
        boolean learned = false;
        for (String code : ran) {
            if (counts.getOrDefault(code, NONE) >= countsBefore.getOrDefault(code, NONE)) {
                learned |= idle.add(code);
            }
        }
        Set<String> skipped = new LinkedHashSet<>(given);
        skipped.removeAll(ran);
        skipped.removeAll(idle);
        skipped.retainAll(rules.isEmpty() ? counts.keySet()
                : DeterministicPolicy.fixableCodes(analysis.errors(), rules));
        given = Set.of();
        ran = Set.of();
        return learned && !skipped.isEmpty();
    }

    /**
     * How many errors an analysis found per rule.
     *
     * @param analysis the analysis
     * @return the count of each rule found
     */
    private static Map<String, Long> countsByRule(AnalysisResult analysis) {
        return analysis.errors().stream().map(SheriffFinding::referenceCode)
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
