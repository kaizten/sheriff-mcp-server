package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Makes sure Sheriff's image is here before anything needs it, and says so
 * when it is not.
 *
 * <p>A missing image is pulled, on purpose and with its own time limit, rather
 * than left to the first {@code docker run} to pull silently inside an
 * analysis's limit. An image that is here but out of date is reported, not
 * replaced: a new image can bring new rules, and the rules a project is held
 * to should not change in the middle of someone's work without them deciding
 * it. {@code SHERIFF_PULL=always} makes it replace, {@code never} skips even
 * asking Docker Hub, for a machine with no network or a pinned setup.
 *
 * <p>The MCP server runs it in the background from startup and answers "being
 * downloaded" meanwhile; the command-line entry points run it in the
 * foreground; the hooks never run it, because a guardrail must not stall the
 * editor for a 4 GB download.
 */
public final class ImageProvisioning {

    private static final String POLICY_VARIABLE = "SHERIFF_PULL";
    private static final String ALWAYS = "always";
    private static final String NEVER = "never";
    private static final String DEFAULT_POLICY = "check";
    private static final String PULLING =
            "Sheriff's image (%s) is not on this machine yet, so it is being downloaded: about 4 GB, "
            + "started %d minute(s) ago. Try again in a few minutes; nothing else is needed.";
    private static final String PULL_FAILED =
            "Sheriff's image (%s) is not on this machine and could not be downloaded: %s. Check that "
            + "Docker is running and the network is up, then restart the session.";
    private static final String OUTDATED =
            "Note: a newer %s has been published than the one on this machine, and its rules may "
            + "differ. Update it with `docker pull %s`, or by running the install line again. To have "
            + "the server update it each time it starts, install with --pull-always: Codex passes a "
            + "server none of the shell's variables, so SHERIFF_PULL=always set there never reaches it.";
    private static final String PULLING_LOG = "Sheriff's image %s is not here; pulling it (about 4 GB)...%n";
    private static final String PULLED_LOG = "Pulled %s.%n";
    private static final String FAILED_LOG = "Could not pull %s: %s%n";
    private static final String UPDATING_LOG = "A newer %s is published; pulling it (SHERIFF_PULL=always)...%n";
    private static final String LINE_FORMAT = "%s%n";
    private static final String EMPTY = "";
    private static final String EXIT_CODE = "docker pull exited with %d";
    private static final String IMAGE_FIELD = "sheriff_image";
    private static final String DIGEST_FIELD = "sheriff_image_digest";

    private final SheriffImage image;
    private final String policy;
    private final PrintStream log;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile Instant pullStarted;
    private volatile String failure = EMPTY;
    private volatile String notice = EMPTY;

    /**
     * Wires it to the image and to a policy.
     *
     * @param image the image to make sure of
     * @param policy {@code always}, {@code never}, or anything else to report
     *     an outdated image without replacing it
     * @param log where progress is written, never the protocol stream
     */
    public ImageProvisioning(SheriffImage image, String policy, PrintStream log) {
        this.image = image;
        this.policy = policy;
        this.log = log;
    }

    /**
     * The policy an environment sets through {@code SHERIFF_PULL}.
     *
     * @param environment the environment to read
     * @return that policy, lower-cased, or the default that only reports
     */
    public static String policyIn(Map<String, String> environment) {
        String configured = environment.get(POLICY_VARIABLE);
        return configured == null || configured.isBlank() ? DEFAULT_POLICY : configured.strip().toLowerCase();
    }

    /**
     * One with nothing to do, for callers that do not manage the image.
     *
     * @return a provisioning that is ready and has nothing to report
     */
    public static ImageProvisioning alreadyReady() {
        ImageProvisioning done = new ImageProvisioning(null, DEFAULT_POLICY, System.err);
        done.ready.complete(null);
        return done;
    }

    /**
     * Starts making sure of the image in the background.
     *
     * @return this, to ask about while it works
     */
    public ImageProvisioning inBackground() {
        CompletableFuture.runAsync(this::provision);
        return this;
    }

    /**
     * Makes sure of the image now, in the caller's thread.
     *
     * @return {@code true} when the image is here and can be used
     */
    public boolean now() {
        provision();
        return failure.isEmpty();
    }

    /**
     * Completes once the image has been dealt with, whatever the outcome.
     *
     * @return that signal
     */
    public CompletableFuture<Void> ready() {
        return ready;
    }

    /**
     * Why Sheriff cannot run right now, when it cannot.
     *
     * @return the reason, while the image is being pulled or after a pull
     *     failed, and empty otherwise
     */
    public Optional<String> blocker() {
        Instant started = pullStarted;
        if (started != null) {
            long minutes = Duration.between(started, Instant.now()).toMinutes();
            return Optional.of(String.format(PULLING, image.image(), minutes));
        }
        return failure.isEmpty() ? Optional.empty() : Optional.of(failure);
    }

    /**
     * Something worth telling whoever uses the tools, without stopping them.
     *
     * @return the note that a newer image is published, or empty
     */
    public Optional<String> notice() {
        return notice.isEmpty() ? Optional.empty() : Optional.of(notice);
    }

    /**
     * The image a run would use now, by name and by the digest it was pulled
     * by, so a result can be traced to the rules that produced it: Sheriff
     * only publishes {@code latest}, and the name alone says nothing.
     *
     * @return the name, and the digest when Docker knows one (an image built
     *     locally has none); empty when there is no image to ask about
     */
    public Map<String, String> identity() {
        if (image == null) {
            return Map.of();
        }
        Map<String, String> identity = new LinkedHashMap<>();
        identity.put(IMAGE_FIELD, image.image());
        image.localDigest().ifPresent(digest -> identity.put(DIGEST_FIELD, digest));
        return identity;
    }

    /**
     * Pulls a missing image, and reports or replaces an outdated one.
     */
    private void provision() {
        try {
            if (!image.present()) {
                pullMissing();
            } else if (!NEVER.equals(policy) && image.outdated()) {
                refresh();
            }
        } finally {
            ready.complete(null);
        }
    }

    /**
     * Pulls an image that is not here, and records how it went.
     *
     * <p>A failure is recorded before "being downloaded" is cleared, never
     * after: in between, a tool call would have found no reason to wait and
     * run Sheriff on an image that is not there.
     */
    private void pullMissing() {
        pullStarted = Instant.now();
        log.printf(PULLING_LOG, image.image());
        ProcessOutcome outcome = image.pull();
        if (outcome.succeeded()) {
            pullStarted = null;
            log.printf(PULLED_LOG, image.image());
            return;
        }
        failure = String.format(PULL_FAILED, image.image(), reasonOf(outcome));
        pullStarted = null;
        log.printf(FAILED_LOG, image.image(), reasonOf(outcome));
    }

    /**
     * Replaces an outdated image when told to, and reports it otherwise.
     */
    private void refresh() {
        if (ALWAYS.equals(policy)) {
            log.printf(UPDATING_LOG, image.image());
            ProcessOutcome outcome = image.pull();
            if (outcome.succeeded()) {
                log.printf(PULLED_LOG, image.image());
                return;
            }
            log.printf(FAILED_LOG, image.image(), reasonOf(outcome));
        }
        notice = String.format(OUTDATED, image.image(), image.image());
        log.printf(LINE_FORMAT, notice);
    }

    /**
     * Why a pull failed, in Docker's own words when it gave any.
     *
     * @param outcome what the pull did
     * @return the reason
     */
    private static String reasonOf(ProcessOutcome outcome) {
        if (!outcome.ran()) {
            return outcome.failure();
        }
        String said = outcome.standardError().strip();
        return said.isEmpty() ? String.format(EXIT_CODE, outcome.exitCode()) : said;
    }
}
