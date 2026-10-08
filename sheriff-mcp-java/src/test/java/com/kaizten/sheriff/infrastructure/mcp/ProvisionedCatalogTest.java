package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.catalog.CatalogExtraction;
import com.kaizten.sheriff.infrastructure.catalog.CatalogProvisioning;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * When the server uses a catalog it finds, and when it extracts its own.
 */
final class ProvisionedCatalogTest {

    @TempDir
    Path workspace;

    private final List<Path> extracted = new ArrayList<>();

    private CatalogExtraction recording() {
        return target -> {
            extracted.add(target);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "{\"rules\":[{\"code\":\"Extracted\",\"description\":\"d\"}]}");
        };
    }

    @Test
    void aConfiguredPathWinsWithoutLookingFurther() {
        Path configured = workspace.resolve("mine.json");
        Path chosen = CatalogProvisioning.choose(configured, List.of(), workspace.resolve("cache.json"),
                catalog -> true, recording());
        assertEquals(configured, chosen);
        assertEquals(List.of(), extracted);
    }

    @Test
    void aCatalogThatMatchesTheImageIsUsedAsItIs() throws IOException {
        Path beside = Files.writeString(workspace.resolve("rules_catalog.json"), "{}");
        Path chosen = CatalogProvisioning.choose(null, List.of(beside), workspace.resolve("cache/rules.json"),
                catalog -> true, recording());
        assertEquals(beside, chosen);
        assertEquals(List.of(), extracted);
    }

    @Test
    @DisplayName("with no catalog anywhere, the server extracts its own into the cache")
    void noCatalogMeansExtractingOne() {
        Path cache = workspace.resolve("cache/rules_catalog.json");
        Path chosen = CatalogProvisioning.choose(null, List.of(workspace.resolve("missing.json")), cache,
                catalog -> true, recording());
        assertEquals(cache, chosen);
        assertEquals(List.of(cache), extracted);
    }

    @Test
    @DisplayName("a catalog from another image is replaced, not trusted")
    void aStaleCatalogIsReextracted() throws IOException {
        Path stale = Files.writeString(workspace.resolve("rules_catalog.json"), "{}");
        Path cache = workspace.resolve("cache/rules_catalog.json");
        Path chosen = CatalogProvisioning.choose(null, List.of(stale), cache, catalog -> false, recording());
        assertEquals(cache, chosen);
    }

    @Test
    @DisplayName("a failed extraction falls back to whatever catalog there is, stale or not")
    void aFailedExtractionFallsBack() throws IOException {
        Path stale = Files.writeString(workspace.resolve("rules_catalog.json"), "{}");
        Path chosen = CatalogProvisioning.choose(null, List.of(stale), workspace.resolve("cache.json"),
                catalog -> false, target -> {
                    throw new IOException("docker is not running");
                });
        assertEquals(stale, chosen);
    }

    @Test
    void readsTheRulesOnceTheCatalogIsKnown() throws IOException {
        Path catalog = Files.writeString(workspace.resolve("rules_catalog.json"),
                "{\"rules\":[{\"code\":\"A\",\"description\":\"d\"}]}");
        ProvisionedCatalog provisioned = new ProvisionedCatalog(
                CompletableFuture.completedFuture(catalog), Duration.ofSeconds(1));
        assertEquals("A", provisioned.allRules().get(0).code());
    }

    @Test
    @DisplayName("a catalog that never turns up means no rules, not a call that hangs")
    void aCatalogThatNeverArrivesIsNoRules() {
        ProvisionedCatalog provisioned = new ProvisionedCatalog(new CompletableFuture<>(), Duration.ofSeconds(1));
        assertTrue(provisioned.allRules().isEmpty());
    }

    @Test
    @DisplayName("while the image is pulled, a catalog already on disk is read rather than waited for")
    void aCatalogOnDiskAnswersWhileProvisioning() throws IOException {
        Path onDisk = Files.writeString(workspace.resolve("rules_catalog.json"),
                "{\"rules\":[{\"code\":\"B\",\"description\":\"d\"}]}");
        ProvisionedCatalog provisioning = new ProvisionedCatalog(new CompletableFuture<>(), Duration.ofSeconds(60), onDisk);
        assertTrue(provisioning.availableNow());
        assertEquals("B", provisioning.allRules().get(0).code());
        assertFalse(new ProvisionedCatalog(new CompletableFuture<>(), Duration.ofSeconds(1)).availableNow());
    }

    @Test
    @DisplayName("an image pulled mid-session gets its own catalog, and the old one answers until it is ready")
    void aNewImageMidSessionIsFollowed() throws IOException {
        Path old = Files.writeString(workspace.resolve("old.json"), "{\"rules\":[{\"code\":\"OLD\",\"description\":\"d\"}]}");
        Path fresh = Files.writeString(workspace.resolve("new.json"), "{\"rules\":[{\"code\":\"NEW\",\"description\":\"d\"}]}");
        AtomicBoolean imageChanged = new AtomicBoolean(false);
        CompletableFuture<Path> extracting = new CompletableFuture<>();
        List<CompletableFuture<Path>> provisions = new ArrayList<>(List.of(CompletableFuture.completedFuture(old), extracting));
        Supplier<CompletableFuture<Path>> provision = () -> provisions.remove(0);
        ProvisionedCatalog catalog = new ProvisionedCatalog(provision,
                path -> !imageChanged.get() || path.equals(fresh), Duration.ofSeconds(1), Duration.ZERO, null);
        assertEquals("OLD", catalog.allRules().get(0).code());

        imageChanged.set(true);

        assertEquals("OLD", catalog.allRules().get(0).code());
        assertTrue(catalog.availableNow());
        extracting.complete(fresh);
        assertEquals("NEW", catalog.allRules().get(0).code());
        assertEquals("NEW", catalog.allRules().get(0).code());
        assertTrue(provisions.isEmpty());
    }

    @Test
    @DisplayName("the image is asked about at most once per interval, not on every lookup")
    void theCheckIsThrottled() throws IOException {
        Path old = Files.writeString(workspace.resolve("old.json"), "{\"rules\":[{\"code\":\"OLD\",\"description\":\"d\"}]}");
        List<Path> asked = new ArrayList<>();
        ProvisionedCatalog catalog = new ProvisionedCatalog(() -> CompletableFuture.completedFuture(old),
                path -> asked.add(path) && false, Duration.ofSeconds(1), Duration.ofHours(1), null);
        catalog.allRules();
        catalog.allRules();
        assertEquals(List.of(), asked);
    }

    @Test
    @DisplayName("a catalog that could not be extracted is tried again on a later lookup")
    void aMissingCatalogIsTriedAgain() throws IOException {
        Path missing = workspace.resolve("never-written.json");
        Path later = Files.writeString(workspace.resolve("later.json"), "{\"rules\":[{\"code\":\"LATER\",\"description\":\"d\"}]}");
        List<CompletableFuture<Path>> provisions = new ArrayList<>(List.of(
                CompletableFuture.completedFuture(missing), CompletableFuture.completedFuture(later)));
        ProvisionedCatalog catalog = new ProvisionedCatalog(() -> provisions.remove(0),
                Files::isRegularFile, Duration.ofSeconds(1), Duration.ZERO, null);
        assertEquals("LATER", catalog.allRules().get(0).code());
    }
}
