package com.kaizten.sheriff.infrastructure.extractor;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Pulls Sheriff's whole rule catalog out of its image and writes it down.
 *
 * <p>This is what makes the agent able to say what Sheriff expects
 * <em>before</em> a rule is broken, rather than only reacting to findings. It
 * is not part of an analysis: the catalog describes an image rather than any
 * project, so it is extracted once per image, by {@code CatalogProvisioning}
 * when a tool finds none that matches the image installed, or by hand with
 * {@code --extract-rules}. It is never committed, since what it reads is
 * Kaizten's.
 */
public final class RuleCatalogExtractor {

    private static final String READING = "Reading rules out of %s...%n";
    private static final String NO_TREE_OPTION =
            "  (this image has no 'tree' option -- falling back to reading the bytecode)%n".formatted();
    private static final String PROFILE_SOURCE_LINE = "  profiles from: %s%n";
    private static final String NO_FIXER_SCRIPTS =
            "  (could not read the fixer scripts -- the catalog will carry no examples)%n".formatted();
    private static final String WITH_EXAMPLES =
            "  %d rule(s) carry the exact code Sheriff writes for them%n";
    private static final String UNMATCHED_HEADING = "  %d check(s) with no matching rule code, e.g.:%n";
    private static final String UNMATCHED_LINE = "    %s%n";
    private static final int UNMATCHED_SHOWN = 5;
    private static final String PROFILE_LINE = "  %s: %d rules%n";
    private static final String WROTE_CATALOG = "%d rules (%d with message text) -> %s%n";
    private static final String WROTE_MARKDOWN = "and the readable version -> %s%n";
    private static final String DOCKER_EXECUTABLE = "docker";
    private static final String IMAGE_COMMAND = "image";
    private static final String INSPECT_COMMAND = "inspect";
    private static final String FORMAT_FLAG = "--format";
    private static final String IMAGE_ID_FORMAT = "{{.Id}}";
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(60);

    private final ProcessRunner processes;
    private final CatalogWriter writer = new CatalogWriter();
    private final Path workingDirectory;

    /**
     * Wires the extractor.
     *
     * @param processes how to run Docker
     * @param workingDirectory where to run it from
     */
    public RuleCatalogExtractor(ProcessRunner processes, Path workingDirectory) {
        this.processes = processes;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Reads the image and writes both documents.
     *
     * @param image the Sheriff image to read
     * @param jsonPath where the catalog goes
     * @param markdownPath where the readable version goes, {@code null} to skip
     * @return the rules it extracted
     * @throws IOException when the documents cannot be written
     */
    public List<CatalogEntry> extract(String image, Path jsonPath, Path markdownPath) throws IOException {
        System.out.printf(READING, image);
        ImageReader reader = new ImageReader(processes, image, workingDirectory);
        String treeJson = "";
        List<String> profileNames = List.of();
        try {
            treeJson = reader.testTree();
            profileNames = TestTreeMapper.profileNames(reader.profileNames());
        } catch (IllegalStateException withoutTree) {
            System.out.print(NO_TREE_OPTION);
        }
        String fixerScripts;
        try {
            fixerScripts = reader.fixerScripts();
        } catch (IllegalStateException unreadable) {
            System.out.print(NO_FIXER_SCRIPTS);
            fixerScripts = "";
        }
        List<CatalogEntry> entries = CatalogBuilder.build(
                reader.constants(), reader.fixerList(), reader.factory(), reader.referenceGraph(),
                treeJson, profileNames, fixerScripts, reader.messageLinks());
        long withExamples = entries.stream().filter(entry -> entry.rule().hasExample()).count();
        System.out.printf(WITH_EXAMPLES, withExamples);
        System.out.printf(PROFILE_SOURCE_LINE,
                profileNames.isEmpty() ? CatalogWriter.BYTECODE_SOURCE : CatalogWriter.BOTH_SOURCES);
        for (Map.Entry<String, Integer> profile : CatalogBuilder.profileCounts(entries).entrySet()) {
            System.out.printf(PROFILE_LINE, profile.getKey(), profile.getValue());
        }
        List<String> unmatched = profileNames.isEmpty()
                ? List.of()
                : TestTreeMapper.unmatchedChecks(entries, treeJson, profileNames);
        if (!unmatched.isEmpty()) {
            System.out.printf(UNMATCHED_HEADING, unmatched.size());
            unmatched.stream().limit(UNMATCHED_SHOWN).forEach(check -> System.out.printf(UNMATCHED_LINE, check));
        }
        writer.write(entries, image, imageId(image), jsonPath, markdownPath,
                profileNames.isEmpty() ? CatalogWriter.BYTECODE_SOURCE : CatalogWriter.BOTH_SOURCES,
                unmatched);
        long documented = entries.stream().filter(entry -> !entry.rule().description().isEmpty()).count();
        System.out.printf(WROTE_CATALOG, entries.size(), documented, jsonPath);
        if (markdownPath != null) {
            System.out.printf(WROTE_MARKDOWN, markdownPath);
        }
        return entries;
    }

    /**
     * The exact image the catalog came from.
     *
     * <p>Best effort: a catalog without it is still usable, only less
     * traceable, so a Docker that cannot answer is not a reason to fail.
     *
     * @param image the image name
     * @return its identifier, or the empty string
     */
    private String imageId(String image) {
        ProcessOutcome outcome = processes.run(
                List.of(DOCKER_EXECUTABLE, IMAGE_COMMAND, INSPECT_COMMAND, FORMAT_FLAG, IMAGE_ID_FORMAT, image),
                workingDirectory, Map.of(), INSPECT_TIMEOUT);
        return outcome.succeeded() ? outcome.standardOutput().strip() : "";
    }
}
