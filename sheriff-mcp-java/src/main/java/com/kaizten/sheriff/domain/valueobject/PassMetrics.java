package com.kaizten.sheriff.domain.valueobject;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/**
 * What one pass of the fix loop did, measured: the errors in its scope rule
 * by rule before it and after it, how long it took, and what it cost.
 *
 * <p>A model's pass is measured on the files of its batch, as the next
 * analysis finds them, so what it did is told from what the passes after it
 * will do. Sheriff's own fixers are measured on the whole component.
 *
 * @param label the pass, as its prompt log names it
 * @param kind {@link #DETERMINISTIC} for Sheriff's own fixers,
 *     {@link #MODEL} for a pass of the model's
 * @param errorsBefore how many errors each rule had in the pass's scope
 *     before it
 * @param errorsAfter the same after it, {@code null} when no analysis
 *     followed the pass
 * @param elapsed how long the pass took, the analysis after it not included
 * @param usage what the pass cost, {@code null} when it used no model or its
 *     backend did not say
 */
public record PassMetrics(
        String label,
        String kind,
        Map<String, Integer> errorsBefore,
        Map<String, Integer> errorsAfter,
        Duration elapsed,
        TokenUsage usage) {

    /**
     * The kind of a pass of Sheriff's own fixers.
     */
    public static final String DETERMINISTIC = "deterministic";

    /**
     * The kind of a pass of the model's.
     */
    public static final String MODEL = "model";

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * @throws IllegalArgumentException when the label, the kind, the errors
     *     before or the time is not defined, or the kind is not one of the two
     */
    public PassMetrics {
        if (label == null || errorsBefore == null || elapsed == null) {
            throw new IllegalArgumentException(ERROR_NOT_DEFINED);
        }
        if (!DETERMINISTIC.equals(kind) && !MODEL.equals(kind)) {
            throw new IllegalArgumentException(ERROR_UNKNOWN_KIND + kind);
        }
        errorsBefore = Map.copyOf(errorsBefore);
        errorsAfter = errorsAfter == null ? null : Map.copyOf(errorsAfter);
    }

    /**
     * The rule by rule count of what the pass left fewer of, measured.
     *
     * @return each rule whose count dropped, with how much, in name order;
     *     empty when nothing was measured after the pass
     */
    public Map<String, Integer> repaired() {
        Map<String, Integer> repaired = new TreeMap<>();
        if (errorsAfter == null) {
            return repaired;
        }
        errorsBefore.forEach((rule, before) -> {
            int dropped = before - errorsAfter.getOrDefault(rule, NONE);
            if (dropped > NONE) {
                repaired.put(rule, dropped);
            }
        });
        return repaired;
    }

    private static final String ERROR_NOT_DEFINED = "A pass's label, kind, errors before or time is not defined";
    private static final String ERROR_UNKNOWN_KIND = "No such kind of pass: ";
    private static final int NONE = 0;
}
