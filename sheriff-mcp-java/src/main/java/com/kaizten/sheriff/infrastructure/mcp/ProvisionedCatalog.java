package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.catalog.CatalogExtraction;
import com.kaizten.sheriff.infrastructure.catalog.CatalogProvisioning;
import com.kaizten.sheriff.infrastructure.catalog.JsonRuleCatalog;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The rule catalog, found or made by this server itself, and kept in step
 * with Sheriff's image for as long as the server runs.
 *
 * <p>The catalog is Kaizten's content, read out of their image, so it ships
 * inside no jar and lives in no repository. A server that expected one to be
 * put beside it could only be installed by someone who knew to run the
 * extractor first, and went quiet about every rule otherwise. This one looks
 * for a catalog that matches the image it will run; when there is none, or
 * the one there came from an older image, it extracts its own into a cache
 * outside any project, in the background, starting the moment the server
 * does. That takes seconds, so it is usually done before the first call that
 * needs it, and a call that arrives earlier waits for it.
 *
 * <p>A session can outlive the image it started with: a {@code docker pull}
 * in another terminal replaces what the tag points at. So a lookup checks
 * again, at most once every {@link #RECHECK}, and extracts a new catalog
 * when the image has changed, answering from the old one meanwhile.
 */
public final class ProvisionedCatalog implements RuleCatalog {

    private static final Duration PATIENCE = Duration.ofSeconds(120);
    private static final Duration RECHECK = Duration.ofSeconds(60);
    private static final Duration ALWAYS = Duration.ZERO;
    private static final String REFRESHING =
            "The rule catalog is missing or was extracted from another image; extracting it again.%n";

    private final Supplier<CompletableFuture<Path>> provision;
    private final Predicate<Path> usable;
    private final Duration patience;
    private final Duration recheck;
    private volatile CompletableFuture<Path> location;
    private volatile Path interim;
    private Instant checked;

    /**
     * Wires the catalog to wherever it will turn out to be.
     *
     * @param location the catalog's path, once it is known
     * @param patience how long a call waits for it before answering without
     */
    public ProvisionedCatalog(CompletableFuture<Path> location, Duration patience) {
        this(location, patience, null);
    }

    /**
     * Wires the catalog with one to answer from while it is provisioned: a
     * catalog already on disk, beside the jar or in the cache, that has not
     * been checked against the image yet. While Sheriff's image is being
     * pulled, which can take minutes, the rules are still worth reading.
     *
     * @param location where the catalog turns out to be, once it is known
     * @param patience how long a lookup waits for that
     * @param interim a catalog to read meanwhile, or {@code null} for none
     */
    public ProvisionedCatalog(CompletableFuture<Path> location, Duration patience, Path interim) {
        this(() -> location, catalog -> true, patience, ALWAYS, interim);
    }

    /**
     * Wires the catalog to how it is provisioned, so that it can be
     * provisioned again when the one chosen stops matching the image.
     *
     * @param provision starts choosing or extracting a catalog; called once
     *     now and again whenever the chosen one is no longer usable
     * @param usable whether a chosen catalog still matches the image
     * @param patience how long a lookup waits for a catalog
     * @param recheck how long a check that found it usable holds
     * @param interim a catalog to read until the first one is known, or
     *     {@code null} for none
     */
    public ProvisionedCatalog(Supplier<CompletableFuture<Path>> provision, Predicate<Path> usable,
            Duration patience, Duration recheck, Path interim) {
        this.provision = provision;
        this.usable = usable;
        this.patience = patience;
        this.recheck = recheck;
        this.interim = interim;
        this.location = provision.get();
        this.checked = Instant.now();
    }

    /**
     * The catalog for a server whose image is already here.
     *
     * @param config where to look and which image it must match
     * @param processes how to run docker for the extraction
     * @return the catalog, being provisioned
     */
    public static ProvisionedCatalog forServer(McpConfig config, ProcessRunner processes) {
        return forServer(config, processes, CompletableFuture.completedFuture(null));
    }

    /**
     * The catalog for a server, chosen or extracted once Sheriff's image has
     * been dealt with: extracting reads the image, so on a machine that is
     * still pulling it, starting at once would only fail.
     *
     * <p>A path set by hand is used as it is, and never replaced.
     *
     * @param config where to look and which image it must match
     * @param processes how to run docker for the extraction
     * @param imageReady completes once the image has been pulled or found
     * @return the catalog, being provisioned
     */
    public static ProvisionedCatalog forServer(
            McpConfig config, ProcessRunner processes, CompletableFuture<Void> imageReady) {
        Path cache = config.cachedCatalog();
        String configured = config.configuredCatalog();
        Path byHand = configured.isEmpty() ? null : Path.of(configured);
        Predicate<Path> fresh = CatalogProvisioning.matching(processes, config.image());
        CatalogExtraction extraction = CatalogProvisioning.extraction(processes, config.image());
        Supplier<CompletableFuture<Path>> provision = () -> imageReady.thenApplyAsync(ignored ->
                CatalogProvisioning.choose(byHand, config.catalogCandidates(), cache, fresh, extraction));
        Predicate<Path> usable = byHand != null ? catalog -> true : catalog -> Files.isRegularFile(catalog) && fresh.test(catalog);
        Path interim = config.catalogCandidates().stream().filter(Files::isRegularFile).findFirst()
                .orElse(Files.isRegularFile(cache) ? cache : null);
        return new ProvisionedCatalog(provision, usable, PATIENCE, RECHECK, interim);
    }

    /**
     * Whether a lookup answers without waiting: the catalog is known, or one
     * is on disk to read meanwhile.
     *
     * @return {@code true} when the rules can be read now
     */
    @Override
    public boolean availableNow() {
        return location.isDone() || interim != null;
    }

    /**
     * Every rule in the catalog, once it is known, or in the one on disk
     * while it is still being provisioned.
     *
     * @return the rules, or an empty list when there is no catalog to read
     */
    @Override
    public List<SheriffRule> allRules() {
        refreshIfDue();
        Path meanwhile = interim;
        if (!location.isDone() && meanwhile != null) {
            return new JsonRuleCatalog(meanwhile).allRules();
        }
        Path catalog = awaited();
        return catalog == null ? List.of() : new JsonRuleCatalog(catalog).allRules();
    }

    /**
     * Where the catalog is, waiting for it to be found or made.
     *
     * @return its path, or {@code null} when it is still not known after
     *     waiting
     */
    public Path path() {
        refreshIfDue();
        return awaited();
    }

    /**
     * Provisions the catalog again when the one chosen no longer matches the
     * image, or never turned up. Checked at most once every
     * {@code recheck}, since each check asks Docker, and never while a
     * provisioning is still running. The catalog being replaced is read
     * until its replacement is ready.
     */
    private synchronized void refreshIfDue() {
        Instant now = Instant.now();
        if (!location.isDone() || now.isBefore(checked.plus(recheck))) {
            return;
        }
        checked = now;
        Path chosen = location.isCompletedExceptionally() ? null : location.join();
        if (chosen != null && usable.test(chosen)) {
            return;
        }
        System.err.printf(REFRESHING);
        if (chosen != null && Files.isRegularFile(chosen)) {
            interim = chosen;
        }
        location = provision.get();
    }

    /**
     * The chosen catalog, waiting for it up to {@code patience}.
     *
     * @return its path, or {@code null} when it is not known in time
     */
    private Path awaited() {
        try {
            return location.get(patience.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException | TimeoutException exception) {
            return null;
        }
    }
}
