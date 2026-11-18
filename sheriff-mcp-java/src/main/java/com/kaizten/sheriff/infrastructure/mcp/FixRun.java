package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.List;
import java.util.Set;

/**
 * What one repair pass did: the findings before it ran and the findings
 * left over afterwards.
 *
 * <p>Its own file rather than a nested record, because Sheriff's hexagonal
 * profile rejects nested types, and the rule keeps being right: if a concept
 * is worth a type, it is worth a file.
 *
 * @param before what was wrong going in
 * @param after what is still wrong coming out
 * @param fixerFailures the fixers Sheriff said it could not apply, each as
 *     {@code CODE: reason}
 * @param rounds how many rounds of fixers ran
 * @param seen every error an analysis of the repair found, those its own
 *     rounds brought and repaired too: a fixer's repair of one of them can
 *     be undone as well
 * @param fixersRan the rules whose fixer Sheriff said it ran
 */
public record FixRun(AnalysisResult before, AnalysisResult after, List<String> fixerFailures, int rounds,
        List<SheriffFinding> seen, Set<String> fixersRan) {

    /**
     * Makes the collections unmodifiable.
     */
    public FixRun {
        fixerFailures = fixerFailures == null ? List.of() : List.copyOf(fixerFailures);
        seen = List.copyOf(seen);
        fixersRan = Set.copyOf(fixersRan);
    }
}
