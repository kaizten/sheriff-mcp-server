package com.kaizten.sheriff.infrastructure.metrics;

/**
 * The figures a comparison gives each rule of a configuration, in the order
 * its table shows them.
 */
enum RuleColumn {

    /**
     * Found by the analysis the run started from.
     */
    DETECTED,

    /**
     * Repaired by Sheriff's own fixers.
     */
    SHERIFF_REPAIRED,

    /**
     * Repaired by the model's passes.
     */
    MODEL_REPAIRED,

    /**
     * Added by the model's passes.
     */
    INTRODUCED,

    /**
     * Left by the run.
     */
    LEFT
}
