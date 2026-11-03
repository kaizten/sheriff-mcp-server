package com.kaizten.sheriff.infrastructure.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The tests Sheriff has run on each component of a repository, as Sheriff
 * records them in {@code sheriff_history.json} at the root of what it
 * mounts.
 *
 * <p>Run by hand on a repository, Sheriff appends every {@code test} to that
 * file, under the repository's URI, as an entry with its components and its
 * test; {@code test --history} prints the same as a table. Measured on the
 * image of 30 October ({@code sha256:46f6caf2}): {@code JAVA} and
 * {@code JAVA_HEXAGONAL} run on {@code back-end} left two entries, one per
 * test, each naming {@code back-end}. The file is read rather than that
 * table, as it needs no container and the hooks read it on every edit.
 *
 * <p>These tools never write it into a project: their runs mount a state
 * folder of their own, so what it holds is what someone ran on the project.
 */
public final class SheriffHistory {

    private static final String FILE_NAME = "sheriff_history.json";
    private static final String COMPONENTS_FIELD = "components";
    private static final String TEST_FIELD = "test";
    private final Path file;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * The history of the repository mounted at a folder.
     *
     * @param mount the folder Sheriff mounts, which holds the file
     */
    public SheriffHistory(Path mount) {
        this.file = mount.resolve(FILE_NAME);
    }

    /**
     * The tests Sheriff has run on a component, in the order the history
     * first lists them.
     *
     * <p>Under any URI: the file is at the root of what was mounted, so every
     * URI in it is this folder, wherever it was mounted in the container.
     *
     * @param component the component, relative to the mount
     * @return those tests, empty when there is no history, it cannot be
     *     read, or it lists none on the component
     */
    public List<String> testsOn(String component) {
        List<String> tests = new ArrayList<>();
        for (JsonNode entries : read()) {
            for (JsonNode entry : entries) {
                String test = entry.path(TEST_FIELD).asText();
                if (!test.isEmpty() && !tests.contains(test) && names(entry, component)) {
                    tests.add(test);
                }
            }
        }
        return tests;
    }

    /**
     * Whether an entry of the history was a run on a component.
     *
     * @param entry the entry
     * @param component the component
     * @return {@code true} when its components include it
     */
    private static boolean names(JsonNode entry, String component) {
        for (JsonNode named : entry.path(COMPONENTS_FIELD)) {
            if (named.asText().equals(component)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The history as Sheriff wrote it: an object of URIs, each with a list
     * of entries.
     *
     * @return that object, or an empty one when there is no file or it
     *     cannot be read
     */
    private JsonNode read() {
        if (!Files.isRegularFile(file)) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(file.toFile());
        } catch (IOException exception) {
            return json.createObjectNode();
        }
    }
}
