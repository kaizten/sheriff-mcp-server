package com.kaizten.sheriff.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule the whole hexagonal layout exists to enforce: dependencies point
 * inwards. The domain knows nothing outside itself, and the application layer
 * knows the domain but never the infrastructure.
 *
 * <p>Checked structurally rather than trusted, because it is exactly the kind
 * of property that breaks by accident. It already did, twice, and both are
 * worth remembering: in the Python original a domain helper grew an import of
 * the configuration module, and in this port the check itself went stale —
 * phase 2 moved {@code FixLoop} out of {@code domain/} into
 * {@code application/}, and a test that only scanned {@code domain/} silently
 * stopped covering the one class most likely to reach for infrastructure.
 *
 * <p>Two design choices follow from that. It is an allowlist, not a denylist,
 * so an import nobody thought to forbid still fails. And it is driven by a
 * table of layers, so adding a layer means adding a row rather than
 * remembering to widen a path.
 */
class LayeringTests {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "com", "kaizten", "sheriff");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final List<String> PURE_JDK = List.of("java.util", "java.lang", "java.time", "java.math");
    private static final String DOMAIN_PACKAGE = "com.kaizten.sheriff.domain";
    private static final String APPLICATION_PACKAGE = "com.kaizten.sheriff.application";
    private static final String INFRASTRUCTURE_PACKAGE = "com.kaizten.sheriff.infrastructure";
    private static final List<String> INFRASTRUCTURE_TOOLING = List.of("java.io", "java.nio", "java.time",
            "java.util", "java.lang", "com.fasterxml.jackson", "com.anthropic", "java.security", "java.net",
            "javax.xml", "org.w3c.dom", "org.xml.sax");
    private static final int MINIMUM_EXPECTED_SOURCES = 10;

    /**
     * One layer and what it is allowed to depend on.
     *
     * @param directory the folder holding that layer, under the source root
     * @param allowedPrefixes every import prefix the layer may use
     */
    private record Layer(String directory, List<String> allowedPrefixes) {
    }

    private static final List<Layer> LAYERS = List.of(
            new Layer("domain", concat(PURE_JDK, List.of(DOMAIN_PACKAGE))),
            new Layer("application", concat(PURE_JDK, List.of(DOMAIN_PACKAGE, APPLICATION_PACKAGE))),
            // The outermost layer is where I/O and third-party libraries are
            // allowed to exist at all -- that is what makes it the outermost
            // layer. It may still only depend inwards, never sideways into
            // another adapter's business.
            new Layer("infrastructure", concat(INFRASTRUCTURE_TOOLING,
                    List.of(DOMAIN_PACKAGE, APPLICATION_PACKAGE, INFRASTRUCTURE_PACKAGE))));

    @Test
    @DisplayName("there are sources to check at all")
    void guardsAgainstAnEmptyScan() throws IOException {
        int scanned = 0;
        for (Layer layer : LAYERS) {
            scanned += sourcesOf(layer).size();
        }
        assertTrue(scanned >= MINIMUM_EXPECTED_SOURCES,
                "the layer scan found almost nothing, so a green result would mean nothing");
    }

    @Test
    void everyLayerDependsOnlyInwards() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Layer layer : LAYERS) {
            for (Path source : sourcesOf(layer)) {
                for (String imported : importsOf(Files.readString(source))) {
                    if (!isAllowed(imported, layer)) {
                        offenders.add(layer.directory() + "/" + source.getFileName() + " -> " + imported);
                    }
                }
            }
        }
        assertEquals(List.of(), offenders,
                "a layer may only depend on itself, on the layers inside it, and on pure JDK types; "
                        + "pass anything else in as a constructor argument, the way FixLoop takes its ports");
    }

    @Test
    @DisplayName("every layer in the table is covered, not just the innermost")
    void theScanIncludesEveryLayerInTheTable() throws IOException {
        for (Layer layer : LAYERS) {
            assertTrue(!sourcesOf(layer).isEmpty(), "no sources found for layer " + layer.directory());
        }
    }

    @Test
    @DisplayName("every source is in a layer: the MCP server is an adapter, not a layer of its own")
    void everySourceBelongsToALayer() throws IOException {
        List<Path> outside;
        try (Stream<Path> walk = Files.walk(SOURCE_ROOT)) {
            outside = walk.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> LAYERS.stream().noneMatch(layer -> path.startsWith(SOURCE_ROOT.resolve(layer.directory()))))
                    .toList();
        }
        assertEquals(List.of(), outside, "sources outside domain/, application/ and infrastructure/ escape this check");
    }

    @Test
    @DisplayName("a structural check that cannot fail is worse than no check")
    void theCheckWouldActuallyCatchAViolation() {
        String offending = """
                import java.io.File;
                import com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer;
                import com.kaizten.sheriff.domain.valueobject.FixResult;
                """;
        Layer domain = LAYERS.get(0);
        List<String> rejected = importsOf(offending).stream().filter(name -> !isAllowed(name, domain)).toList();
        assertEquals(
                List.of("java.io.File", "com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer"),
                rejected);
    }

    @Test
    @DisplayName("the domain may not import the application layer, only the other way round")
    void theAllowlistsAreNotSymmetric() {
        String applicationImport = "import com.kaizten.sheriff.application.usecase.FixCodeUntilClean;";
        assertTrue(isAllowed(importsOf(applicationImport).get(0), LAYERS.get(1)));
        assertTrue(!isAllowed(importsOf(applicationImport).get(0), LAYERS.get(0)));
    }

    private static List<Path> sourcesOf(Layer layer) throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCE_ROOT.resolve(layer.directory()))) {
            return walk.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static List<String> importsOf(String source) {
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(source);
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
    }

    private static boolean isAllowed(String imported, Layer layer) {
        return layer.allowedPrefixes().stream().anyMatch(prefix -> imported.startsWith(prefix + "."));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
