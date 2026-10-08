package com.kaizten.sheriff.infrastructure.antigravity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The home of one Antigravity pass, made by {@link AntigravityHome#open}:
 * the variables {@code agy} runs with, and the temporary directory they point
 * at, deleted when the pass is closed.
 */
public final class AntigravityPass implements AutoCloseable {

    /**
     * The temporary home, empty when the pass runs in the user's.
     */
    private final Optional<Path> home;

    /**
     * The variables the pass runs with.
     */
    private final Map<String, String> environment;

    /**
     * Wires a pass.
     *
     * @param home the temporary home, or empty
     * @param environment the variables the pass runs with
     */
    AntigravityPass(Optional<Path> home, Map<String, String> environment) {
        this.home = home;
        this.environment = Map.copyOf(environment);
    }

    /**
     * The variables {@code agy} runs with.
     *
     * @return {@code HOME} and the build tools' homes, or nothing
     */
    public Map<String, String> environment() {
        return environment;
    }

    /**
     * Deletes the temporary home and everything {@code agy} wrote there. A
     * file that cannot be deleted stays, as a pass is not failed for it.
     */
    @Override
    public void close() {
        if (home.isEmpty()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(home.get())) {
            paths.sorted(Comparator.reverseOrder()).forEach(AntigravityPass::deleteQuietly);
        } catch (IOException ignored) {
            deleteQuietly(home.get());
        }
    }

    /**
     * Deletes one path, saying nothing when it cannot.
     *
     * @param path what to delete
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }
}
