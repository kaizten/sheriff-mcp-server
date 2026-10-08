package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import java.util.List;
import java.util.Optional;

/**
 * What one repair by Sheriff's own fixers did: the findings before it and
 * after it, measured, and what it could not do.
 *
 * <p>Its own file rather than a nested record, because Sheriff's hexagonal
 * profile rejects nested types.
 *
 * @param before what the first analysis found, merged across the profiles
 * @param after what the last analysis found, after the last repair
 * @param codes the rules the catalog knows a fixer for, among those handed
 *     to Sheriff's fixers, in the order first handed
 * @param fixerFailures the fixers Sheriff said it could not apply, each as
 *     {@code CODE: reason}
 * @param failure why the repair could not be carried out, when it could not:
 *     an analysis that could not be trusted, or a {@code fix} that did not run
 */
public record RepairOutcome(
        AnalysisResult before,
        AnalysisResult after,
        List<String> codes,
        List<String> fixerFailures,
        Optional<String> failure) {

    /**
     * Makes the lists unmodifiable.
     */
    public RepairOutcome {
        codes = codes == null ? List.of() : List.copyOf(codes);
        fixerFailures = fixerFailures == null ? List.of() : List.copyOf(fixerFailures);
        failure = failure == null ? Optional.empty() : failure;
    }

    /**
     * A repair that could not be carried out.
     *
     * @param reason why
     * @return that outcome, with no analysis to report
     */
    static RepairOutcome failed(String reason) {
        AnalysisResult none = AnalysisResult.failure(reason);
        return new RepairOutcome(none, none, List.of(), List.of(), Optional.of(reason));
    }
}
