package com.kaizten.sheriff.domain.valueobject;

import java.util.List;


/**
 * Object Mother for TokenUsage.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class TokenUsageMother {

    private TokenUsageMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid TokenUsage
     */
    public static TokenUsage random() {
        return new TokenUsage(List.of("claude-sonnet-5-5"), 10, 20, 30, 40, 2, 0.25);
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a TokenUsage equal to {@code value}
     */
    public static TokenUsage equalTo(TokenUsage value) {
        return new TokenUsage(value.models(), value.inputTokens(), value.outputTokens(), value.cacheReadTokens(), value.cacheWriteTokens(), value.turns(), value.costUsd());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a TokenUsage not equal to {@code value}
     */
    public static TokenUsage differentTo(TokenUsage value) {
        return new TokenUsage(value.models(), value.inputTokens() + 1, value.outputTokens(), value.cacheReadTokens(), value.cacheWriteTokens(), value.turns(), value.costUsd());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static TokenUsage generateInvalidValue() {
        return new TokenUsage(null, 10, 20, 30, 40, 2, 0.25);
    }
}
