package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;

/**
 * Applies a fix, given a prompt. The swappable strategy of this design: the
 * Claude Code CLI today, the Anthropic API tomorrow, a local model after that.
 */
@FunctionalInterface
public interface CodeFixer {

    /**
     * Asks the backend to carry out one fix.
     *
     * @param request the prompt, its log label, and the files it may touch
     * @return whether the invocation ran, and what it said
     */
    FixResult fix(FixRequest request);
}
