package com.kaizten.sheriff.domain.valueobject;

/**
 * The three knobs of a fix-loop run, grouped so the loop's constructor stays
 * about its collaborators rather than about its configuration.
 *
 * @param maxIterations the cap on iterations, or {@code null} for "size it
 *     automatically once the first analysis says how many files have errors"
 * @param useGitSafety whether to work on a dedicated branch, commit each pass,
 *     and check afterwards which files were really touched
 * @param maxFilesPerBatch how many distinct files one fixer call may cover,
 *     {@code 0} for no limit
 */
public record LoopSettings(Integer maxIterations, boolean useGitSafety, int maxFilesPerBatch) {

    private static final int DEFAULT_MAX_FILES_PER_BATCH = 5;
    private static final String ERROR_BATCH_NEGATIVE = "Max files per batch cannot be negative";
    private static final String ERROR_NO_ITERATIONS =
            "Max iterations must be at least 1: a cap of 0 would run no pass and say nothing";

    /**
     * Checks the one value here that can be wrong rather than merely absent.
     *
     * <p>A null iteration cap means "size it from the first analysis", which
     * is a documented choice. A negative batch size is not a choice: zero
     * already means no limit, so anything below it is a mistake worth
     * refusing at the boundary.
     *
     * @throws IllegalArgumentException when the batch size is negative
     */
    public LoopSettings {
        if (maxFilesPerBatch < MINIMUM_FILES_PER_BATCH) {
            throw new IllegalArgumentException(ERROR_BATCH_NEGATIVE);
        }
        if (maxIterations != null && maxIterations < MINIMUM_ITERATIONS) {
            throw new IllegalArgumentException(ERROR_NO_ITERATIONS);
        }
    }
    private static final int NO_LIMIT = 0;
    private static final int MINIMUM_FILES_PER_BATCH = 0;
    private static final int MINIMUM_ITERATIONS = 1;

    /**
     * The defaults the CLI uses when nothing is configured: automatic
     * iteration cap, git safety on, five files per pass.
     *
     * @return those settings
     */
    public static LoopSettings defaults() {
        return new LoopSettings(null, true, DEFAULT_MAX_FILES_PER_BATCH);
    }

    /**
     * Whether a single pass is bounded to a subset of the flagged files.
     *
     * @return {@code true} when batching is in effect
     */
    public boolean batches() {
        return maxFilesPerBatch > NO_LIMIT;
    }
}
