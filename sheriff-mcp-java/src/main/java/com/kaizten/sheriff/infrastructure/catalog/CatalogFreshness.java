package com.kaizten.sheriff.infrastructure.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Whether a catalog still matches the image it was extracted from.
 *
 * <p>A catalog is a file, and {@link JsonRuleCatalog} reads its rules without
 * ever looking at the {@code image_id} the extractor wrote alongside them. A
 * new Sheriff image makes it silently wrong: rules missing, renamed or
 * described as they used to be. This is the check that catches it, and
 * {@link CatalogProvisioning} puts every catalog to it before using one. It
 * is best effort: a Docker that cannot answer, or a catalog with no recorded
 * {@code image_id} (the Markdown format does not carry one), says nothing
 * rather than failing, since extracting again would fail the same way.
 */
public final class CatalogFreshness {

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String DOCKER = "docker";
    private static final String IMAGE = "image";
    private static final String INSPECT = "inspect";
    private static final String FORMAT_FLAG = "--format";
    private static final String IMAGE_ID_FORMAT = "{{.Id}}";
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(60);
    private static final String IMAGE_ID_FIELD = "image_id";
    private static final String GENERATED_AT_FIELD = "generated_at";
    private static final String EMPTY = "";
    private static final int SHORT_ID_LENGTH = 19;

    /**
     * Names the two identifiers rather than the image twice: both sides are
     * normally the same tag, so saying "%s is not %s" about
     * {@code kaizten/sheriff:latest} reads as a contradiction. What actually
     * differs is what the tag pointed at, then and now.
     */
    private static final String WARNING = """
            warning: the rule catalog at %s was extracted on %s from %s (%s), but that tag \
            points at %s now. Rules may be missing, renamed or wrongly described until it is \
            regenerated with --extract-rules.
            """;

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private CatalogFreshness() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Compares a catalog's recorded image against the image configured now.
     *
     * @param processes how to ask Docker what the configured image actually is
     * @param workingDirectory where to run that Docker check from
     * @param catalogPath the JSON catalog to check
     * @param image the image configured for this run
     * @return a warning to print, empty when they match or when either side
     *     could not be determined
     */
    public static Optional<String> check(
            ProcessRunner processes, Path workingDirectory, Path catalogPath, String image) {
        String recordedId = field(catalogPath, IMAGE_ID_FIELD);
        if (recordedId.isEmpty()) {
            return Optional.empty();
        }
        String liveId = liveImageId(processes, workingDirectory, image);
        if (liveId.isEmpty() || liveId.equals(recordedId)) {
            return Optional.empty();
        }
        String recordedAt = field(catalogPath, GENERATED_AT_FIELD);
        return Optional.of(String.format(
                WARNING, catalogPath, recordedAt, image, shortened(recordedId), shortened(liveId)));
    }

    /**
     * An image identifier cut to something a warning line can carry, since
     * the part that differs is at the front.
     *
     * @param imageId the full identifier
     * @return its first characters, or all of it when it is already short
     */
    private static String shortened(String imageId) {
        return imageId.length() <= SHORT_ID_LENGTH ? imageId : imageId.substring(0, SHORT_ID_LENGTH);
    }

    /**
     * One top-level string field of a JSON catalog file.
     *
     * @param catalogPath the file to read
     * @param name the field to read
     * @return that field's text, empty when the file or the field is absent
     */
    private static String field(Path catalogPath, String name) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(catalogPath));
            return root.path(name).asText(EMPTY);
        } catch (IOException exception) {
            return EMPTY;
        }
    }

    /**
     * The image identifier Docker reports right now.
     *
     * @param processes how to run Docker
     * @param workingDirectory where to run it from
     * @param image the image to inspect
     * @return that identifier, empty when Docker could not answer
     */
    private static String liveImageId(ProcessRunner processes, Path workingDirectory, String image) {
        ProcessOutcome outcome = processes.run(
                List.of(DOCKER, IMAGE, INSPECT, FORMAT_FLAG, IMAGE_ID_FORMAT, image),
                workingDirectory, Map.of(), INSPECT_TIMEOUT);
        return outcome.succeeded() ? outcome.standardOutput().strip() : EMPTY;
    }
}
