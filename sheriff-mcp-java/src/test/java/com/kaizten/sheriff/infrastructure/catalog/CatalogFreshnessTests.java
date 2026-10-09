package com.kaizten.sheriff.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether a committed catalog is warned about when it no longer matches the
 * configured image, without ever turning that into a failure.
 */
final class CatalogFreshnessTests {

    private static final String IMAGE = "kaizten/sheriff:latest";
    private static final String MATCHING_ID = "sha256:aaaa";
    private static final String DIFFERENT_ID = "sha256:bbbb";

    @TempDir
    Path directory;

    private Path catalogWith(String imageId) throws IOException {
        Path catalog = directory.resolve("rules_catalog.json");
        String body = imageId.isEmpty()
                ? "{\"rules\":[]}"
                : "{\"image_id\":\"%s\",\"generated_at\":\"2026-09-22T10:09:22Z\",\"rules\":[]}".formatted(imageId);
        Files.writeString(catalog, body);
        return catalog;
    }

    @Test
    void saysNothingWhenTheIdsMatch() throws IOException {
        Path catalog = catalogWith(MATCHING_ID);
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, MATCHING_ID, ""));

        Optional<String> warning = CatalogFreshness.check(processes, directory, catalog, IMAGE);

        assertEquals(Optional.empty(), warning);
    }

    @Test
    void warnsWhenTheImageMovedOn() throws IOException {
        Path catalog = catalogWith(MATCHING_ID);
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, DIFFERENT_ID, ""));

        Optional<String> warning = CatalogFreshness.check(processes, directory, catalog, IMAGE);

        assertTrue(warning.isPresent());
        assertTrue(warning.get().contains(IMAGE));
        assertTrue(warning.get().contains("--extract-rules"));
    }

    /**
     * Both sides of this comparison are normally the same tag, so a message
     * built out of the image name twice reads as "X is not X". It said
     * exactly that until a real run showed it; what has to appear is the two
     * identifiers the tag resolved to, then and now.
     */
    @Test
    void theWarningNamesBothIdentifiersRatherThanTheImageTwice() throws IOException {
        Path catalog = catalogWith(MATCHING_ID);
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, DIFFERENT_ID, ""));

        String warning = CatalogFreshness.check(processes, directory, catalog, IMAGE).orElseThrow();

        assertTrue(warning.contains(MATCHING_ID), "the identifier the catalog recorded");
        assertTrue(warning.contains(DIFFERENT_ID), "the identifier the tag points at now");
    }

    @Test
    void saysNothingWhenTheCatalogCarriesNoImageId() throws IOException {
        Path catalog = catalogWith("");
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, DIFFERENT_ID, ""));

        Optional<String> warning = CatalogFreshness.check(processes, directory, catalog, IMAGE);

        assertEquals(Optional.empty(), warning);
        assertTrue(processes.commands().isEmpty());
    }

    @Test
    void saysNothingWhenTheCatalogFileIsMissing() {
        Path missing = directory.resolve("does-not-exist.json");
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, DIFFERENT_ID, ""));

        Optional<String> warning = CatalogFreshness.check(processes, directory, missing, IMAGE);

        assertEquals(Optional.empty(), warning);
    }

    @Test
    void saysNothingWhenDockerCannotAnswer() throws IOException {
        Path catalog = catalogWith(MATCHING_ID);
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.unavailable("docker not found"));

        Optional<String> warning = CatalogFreshness.check(processes, directory, catalog, IMAGE);

        assertEquals(Optional.empty(), warning);
    }
}
