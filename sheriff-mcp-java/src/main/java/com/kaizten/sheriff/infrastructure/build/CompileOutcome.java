package com.kaizten.sheriff.infrastructure.build;

import java.util.List;
import java.util.Optional;

/**
 * Whether a component compiles, and when it does not, where.
 *
 * @param compiles whether its main and test sources compiled
 * @param errors what the compiler said, empty when it compiled
 * @param command the command that compiled it
 * @param bypassed the goal of the build's that stopped it before compiling,
 *     which the second command left out, as {@code artifactId:goal}; empty
 *     when none did
 */
public record CompileOutcome(boolean compiles, List<CompileError> errors, String command,
        Optional<String> bypassed) {

    /**
     * Makes the list unmodifiable.
     */
    public CompileOutcome {
        errors = List.copyOf(errors);
    }
}
