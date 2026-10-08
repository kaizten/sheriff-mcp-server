package com.kaizten.sheriff.infrastructure.catalog;

import com.kaizten.sheriff.infrastructure.extractor.RuleCatalogExtractor;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finds a rule catalog that matches Sheriff's image, or extracts one.
 *
 * <p>The catalog describes one image, and nothing in Sheriff says when the
 * image changes: a pull replaces what the tag points at and every catalog
 * extracted before goes quietly wrong. So no reader trusts a catalog because
 * it is there. Each one asks this class, which compares the image the
 * catalog was extracted from with the image installed now and extracts a new
 * one when they differ. The MCP server, the agent's CLI and the Maven plugin
 * all do, and they share one cache, so a new image is read once per machine
 * rather than once per tool.
 */
public final class CatalogProvisioning {

    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String USER_HOME = "user.home";
    private static final String DEFAULT_CACHE_DIRECTORY = ".cache";
    private static final String CACHE_SUBDIRECTORY = "sheriff-mcp";
    private static final String CATALOG_FILE_NAME = "rules_catalog.json";
    private static final String MARKDOWN_NAME = "SHERIFF_RULES.md";
    private static final String PARTIAL_SUFFIX = ".partial";
    private static final String EXTRACTING = "Extracting the rule catalog from %s into %s ...%n";
    private static final String EXTRACTION_FAILED =
            "Could not extract the rule catalog from %s (%s); rule lookups will say so.%n";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private CatalogProvisioning() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Where every tool here keeps the catalog it extracts.
     *
     * @param cacheHome {@code $XDG_CACHE_HOME}, or empty for {@code ~/.cache}
     * @return {@code <cache home>/sheriff-mcp/rules_catalog.json}
     */
    public static Path cache(String cacheHome) {
        return cacheIn(cacheHome(cacheHome));
    }

    /**
     * The cache directory itself, for handing on to a tool that is given
     * its environment rather than inheriting it.
     *
     * @param cacheHome {@code $XDG_CACHE_HOME}, or empty for {@code ~/.cache}
     * @return that directory
     */
    public static Path cacheHome(String cacheHome) {
        return cacheHome.isEmpty()
                ? Path.of(System.getProperty(USER_HOME), DEFAULT_CACHE_DIRECTORY)
                : Path.of(cacheHome);
    }

    /**
     * Where the catalog goes under a given cache directory.
     *
     * @param cacheHome the cache directory, such as {@code ~/.cache}
     * @return {@code <cache home>/sheriff-mcp/rules_catalog.json}
     */
    public static Path cacheIn(Path cacheHome) {
        return cacheHome.resolve(CACHE_SUBDIRECTORY).resolve(CATALOG_FILE_NAME);
    }

    /**
     * Whether a catalog was extracted from the image installed now.
     *
     * <p>A catalog that records no image, or a Docker that cannot answer,
     * counts as matching: neither is a reason to extract again, and the
     * extraction would fail with the same Docker anyway.
     *
     * @param processes how to ask Docker which image the tag points at
     * @param image the image in use
     * @return the test to put a catalog to
     */
    public static Predicate<Path> matching(ProcessRunner processes, String image) {
        return catalog -> CatalogFreshness.check(
                processes, catalog.toAbsolutePath().getParent(), catalog, image).isEmpty();
    }

    /**
     * How to extract the catalog from an image.
     *
     * @param processes how to run Docker
     * @param image the image to read
     * @return the extraction
     */
    public static CatalogExtraction extraction(ProcessRunner processes, String image) {
        return target -> extract(processes, image, target);
    }

    /**
     * Decides which catalog to use, extracting one when nothing usable is
     * there.
     *
     * @param configured the path set by hand, which wins outright, or
     *     {@code null}
     * @param candidates where one may already be, in order
     * @param cache where extracted catalogs are kept
     * @param fresh whether a catalog matches the image in use
     * @param extraction how to make one
     * @return the catalog to read
     */
    public static Path choose(
            Path configured, List<Path> candidates, Path cache, Predicate<Path> fresh, CatalogExtraction extraction) {
        if (configured != null) {
            return configured;
        }
        List<Path> known = new ArrayList<>(candidates);
        known.add(cache);
        for (Path candidate : known) {
            if (Files.isRegularFile(candidate) && fresh.test(candidate)) {
                return candidate;
            }
        }
        try {
            extraction.into(cache);
            return cache;
        } catch (IOException | RuntimeException exception) {
            System.err.printf(EXTRACTION_FAILED, cache, exception.getMessage());
            return known.stream().filter(Files::isRegularFile).findFirst().orElse(cache);
        }
    }

    /**
     * Extracts the catalog into a file, atomically: two tools starting at
     * once must not leave half of one catalog behind for a third to read.
     *
     * @param processes how to run Docker
     * @param image the image to read
     * @param target where the catalog goes
     * @throws IOException when it cannot be written
     */
    private static void extract(ProcessRunner processes, String image, Path target) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        System.err.printf(EXTRACTING, image, target);
        Path partial = directory.resolve(target.getFileName() + PARTIAL_SUFFIX);
        Path markdown = directory.resolve(MARKDOWN_NAME + PARTIAL_SUFFIX);
        new RuleCatalogExtractor(processes, directory).extract(image, partial, markdown);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.move(markdown, directory.resolve(MARKDOWN_NAME), StandardCopyOption.REPLACE_EXISTING);
    }
}
