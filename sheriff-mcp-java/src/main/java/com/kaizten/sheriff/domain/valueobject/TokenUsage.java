package com.kaizten.sheriff.domain.valueobject;

import java.util.List;

/**
 * What one invocation of a model cost, as its backend reported it.
 *
 * <p>Input is the tokens read fresh, without those read from or written to
 * a prompt cache, which are counted apart: Claude reports them that way, and
 * Codex's input, which includes what it read from its cache, is split to
 * match, so the two can be compared.
 *
 * @param models the models that answered, as the backend names them; empty
 *     when it does not say
 * @param inputTokens the tokens read that no cache held
 * @param outputTokens the tokens written, reasoning included
 * @param cacheReadTokens the tokens read from a prompt cache
 * @param cacheWriteTokens the tokens written to a prompt cache
 * @param turns how many turns the invocation took
 * @param costUsd what it cost in dollars at list price, {@code null} when
 *     the backend does not say
 */
public record TokenUsage(
        List<String> models,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        int turns,
        Double costUsd) {

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * @throws IllegalArgumentException when the models are not defined or a
     *     count is negative
     */
    public TokenUsage {
        if (models == null) {
            throw new IllegalArgumentException(ERROR_MODELS_NOT_DEFINED);
        }
        if (inputTokens < NONE || outputTokens < NONE || cacheReadTokens < NONE || cacheWriteTokens < NONE
                || turns < NONE || costUsd != null && costUsd < NONE) {
            throw new IllegalArgumentException(ERROR_NEGATIVE_COUNT);
        }
        models = List.copyOf(models);
    }

    /**
     * Every token the invocation read or wrote, cached or not.
     *
     * @return the sum of the four counts
     */
    public long totalTokens() {
        return inputTokens + outputTokens + cacheReadTokens + cacheWriteTokens;
    }

    private static final String ERROR_MODELS_NOT_DEFINED = "Models are not defined";
    private static final String ERROR_NEGATIVE_COUNT = "A token count, a turn count or a cost is negative";
    private static final int NONE = 0;
}
