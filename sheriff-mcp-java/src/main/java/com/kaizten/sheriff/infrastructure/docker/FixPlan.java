package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Which {@code fix} runs repair one analysis, and what each is kept to.
 *
 * <p>Since the image of 11 November 2026 ({@code sha256:01cdf727}) a
 * {@code fix} is always recursive: after each fixer it analyzes again and
 * repairs what it finds, and the option that stopped it is gone. Measured on
 * that image:
 *
 * <ul>
 *   <li>a finding taken out of the record {@code fix} reads is repaired all
 *       the same when its rule's fixer runs, so nothing is withheld that
 *       way any more;</li>
 *   <li>{@code --fixers} keeps a run, its later analyses included, to the
 *       rules it names, and {@code --file} to the files it names, by their
 *       path in the container;</li>
 *   <li>a rule named to {@code --fixers} that has no fixer makes the whole
 *       run fail ("Invalid fixer reference code"), so only rules whose
 *       finding carries a {@code script} are named;</li>
 *   <li>with no {@code --fixers} it runs the fixer of every finding that has
 *       one, the ones its own repairs bring included.</li>
 * </ul>
 *
 * <p>So with nothing withheld one run of the default set is the plan. A
 * finding withheld keeps its rule's fixer out of its file: one run for the
 * files with nothing withheld, and one for the files with something
 * withheld, without the rules withheld in any of them.
 */
final class FixPlan {

    private static final String SCRIPT_FIELD = "script";
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";

    /**
     * Not instantiable.
     */
    private FixPlan() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * The runs that repair what one analysis recorded.
     *
     * @param recorded every finding in the record, false positives included
     * @param held which of them no fixer may repair
     * @param named the rules asked for, or empty for every fixer
     * @param attempts what this profile's fixers did in earlier rounds
     * @return the runs, in order; each after the first needs an analysis of
     *     its own before it, as {@code fix} refuses a record older than the
     *     files
     */
    static List<PlannedFix> of(List<SheriffFinding> recorded, Predicate<SheriffFinding> held,
            Optional<List<String>> named, FixerAttempts attempts) {
        List<SheriffFinding> withheld = recorded.stream().filter(held).toList();
        List<SheriffFinding> free = recorded.stream().filter(held.negate()).toList();
        List<String> codes = named.orElseGet(() -> attempts.worthGiving(fixable(free)));
        List<PlannedFix> runs = new ArrayList<>();
        if (withheld.isEmpty()) {
            if (named.isEmpty() && attempts.defaultSetRuns()) {
                runs.add(new PlannedFix(List.of(), List.of()));
            } else if (!codes.isEmpty()) {
                runs.add(new PlannedFix(codes, List.of()));
            }
            return runs;
        }
        Set<String> heldFiles = filesOf(withheld, finding -> true);
        Set<String> heldCodes = new LinkedHashSet<>(withheld.stream().map(SheriffFinding::referenceCode).toList());
        List<String> elsewhere = filesOf(free, finding -> codes.contains(finding.referenceCode())).stream()
                .filter(file -> !heldFiles.contains(file)).toList();
        if (!elsewhere.isEmpty()) {
            runs.add(new PlannedFix(codes, elsewhere));
        }
        List<String> rest = codes.stream().filter(code -> !heldCodes.contains(code)).toList();
        List<String> beside = filesOf(free, finding -> rest.contains(finding.referenceCode())).stream()
                .filter(heldFiles::contains).toList();
        if (!beside.isEmpty()) {
            runs.add(new PlannedFix(rest, beside));
        }
        return runs;
    }

    /**
     * The rules of some findings that have a fixer, each once, in order.
     *
     * @param findings the findings, as Sheriff recorded them
     * @return their rules with a fixer
     */
    private static List<String> fixable(List<SheriffFinding> findings) {
        return findings.stream().filter(finding -> finding.raw().containsKey(SCRIPT_FIELD))
                .map(SheriffFinding::referenceCode).filter(code -> !code.isEmpty()).distinct().toList();
    }

    /**
     * The files of the findings that pass a test, each once, in order.
     *
     * @param findings the findings
     * @param wanted which of them count
     * @return their files
     */
    private static Set<String> filesOf(List<SheriffFinding> findings, Predicate<SheriffFinding> wanted) {
        return new LinkedHashSet<>(findings.stream().filter(wanted).map(SheriffFinding::file).toList());
    }
}
