package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import java.util.List;

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
 */
public record FixRun(AnalysisResult before, AnalysisResult after, List<String> fixerFailures) {

    /**
     * Makes the failure list unmodifiable.
     */
    public FixRun {
        fixerFailures = fixerFailures == null ? List.of() : List.copyOf(fixerFailures);
    }

    /**
     * A run with no fixer Sheriff failed to apply.
     *
     * @param before what was wrong going in
     * @param after what is still wrong coming out
     */
    public FixRun(AnalysisResult before, AnalysisResult after) {
        this(before, after, List.of());
    }
}
