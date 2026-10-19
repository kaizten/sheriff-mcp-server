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
 * analysis's limit. An image that is here but out of date is replaced as well:
 * Kaizten publishes Sheriff only as {@code latest}, with no versions, so the
 * build to hold a project to is the newest one, and the only way to tell that
 * one from the one here is the digest Docker Hub publishes for the tag, never
 * the tag. {@code SHERIFF_PULL=check} only reports it, {@code never} skips even
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
    private static final String DEFAULT_POLICY = ALWAYS;
    private static final String PULLING =
            "Sheriff's image (%s) is not on this machine yet, so it is being downloaded: about 4 GB, "
            + "started %d minute(s) ago. Try again in a few minutes; nothing else is needed.";
    private static final String PULL_FAILED =
            "Sheriff's image (%s) is not on this machine and could not be downloaded: %s. Check that "
            + "Docker is running and the network is up, then restart the session.";
    private static final String OUTDATED =
            "Note: a newer %s has been published than the one on this machine, and its rules may "
            + "differ. SHERIFF_PULL=check keeps the one here: update it with `docker pull %s`.";
    private static final String NOT_UPDATED =
            "Note: a newer %s has been published than the one on this machine, and its rules may "
            + "differ, but it could not be downloaded (%s), so the one here is used. Update it with "
            + "`docker pull %s`.";
    private static final String UPDATING =
            "Note: a newer %s has been published, and it is being downloaded. Until it is here, Sheriff "
            + "runs the one on this machine, whose rules may differ.";
    private static final String PULLING_LOG = "Sheriff's image %s is not here; pulling it (about 4 GB)...%n";
    private static final String PULLED_LOG = "Pulled %s.%n";
    private static final String FAILED_LOG = "Could not pull %s: %s%n";
    private static final String UPDATING_LOG =
            "A newer %s is published (its digest differs from the one here); pulling it...%n";
    private static final String LINE_FORMAT = "%s%n";
    private static final String EMPTY = "";
    private static final String EXIT_CODE = "docker pull exited with %d";
    private static final String FULL_STOP = ".";
    private static final int START = 0;
    private static final String IMAGE_FIELD = "sheriff_image";
    private static final String DIGEST_FIELD = "sheriff_image_digest";
    private static final String CREATED_FIELD = "sheriff_image_created";
    private static final String BUILT_ON = ", built on %s";
    private static final String PULLED_BY = " (%s)";
    private static final String NOT_HERE = ", which Docker does not show on this machine";
    private static final String NEWER_PUBLISHED = "; a newer build of it has been published";
    private static final String NEWER_ARRIVING = "; a newer build of it is being downloaded";
    private static final int SHORT_DIGEST_LENGTH = 19;

    private final SheriffImage image;
    private final String policy;
    private final PrintStream log;
    private final CompletableFuture<Void> ready = new CompletableFuture<>();
    private volatile Instant pullStarted;
    private volatile String failure = EMPTY;
    private volatile String notice = EMPTY;
    private volatile boolean updating;

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
     * @return that policy, lower-cased, or the default that replaces an
     *     outdated image
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
     * @return the note that a newer image is published, or is being
     *     downloaded, or empty
     */
    public Optional<String> notice() {
        if (updating) {
            return Optional.of(String.format(UPDATING, image.image()));
        }
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
        image.localBuild().ifPresent(build -> {
            putKnown(identity, DIGEST_FIELD, build.digest());
            putKnown(identity, CREATED_FIELD, build.created());
        });
        return identity;
    }

    /**
     * The image as a person reads it: its name, and which build of it is on
     * this machine, the day it was built and the start of its digest, since
     * {@code latest} alone does not say which build was pulled. It also says
     * when a newer build is known to be published, or is being downloaded.
     *
     * @return that description, or empty when there is no image to ask about
     */
    public Optional<String> description() {
        if (image == null) {
            return Optional.empty();
        }
        Optional<LocalBuild> build = image.localBuild();
        StringBuilder text = new StringBuilder(image.image());
        if (build.isEmpty()) {
            text.append(NOT_HERE);
        } else {
            appendKnown(text, BUILT_ON, build.get().created());
            appendKnown(text, PULLED_BY, shortened(build.get().digest()));
        }
        if (updating) {
            text.append(NEWER_ARRIVING);
        } else if (!notice.isEmpty()) {
            text.append(NEWER_PUBLISHED);
        }
        return Optional.of(text.toString());
    }

    /**
     * Records a field only when Docker said what it holds.
     *
     * @param identity where the fields go
     * @param field the field
     * @param value its value, empty when unknown
     */
    private static void putKnown(Map<String, String> identity, String field, String value) {
        if (!value.isEmpty()) {
            identity.put(field, value);
        }
    }

    /**
     * Adds a part of the description only when Docker said what it holds.
     *
     * @param text the description so far
     * @param format how the part reads
     * @param value its value, empty when unknown
     */
    private static void appendKnown(StringBuilder text, String format, String value) {
        if (!value.isEmpty()) {
            text.append(String.format(format, value));
        }
    }

    /**
     * The start of a digest, as Docker and Docker Hub show it.
     *
     * @param digest the whole digest
     * @return its algorithm and first twelve characters
     */
    private static String shortened(String digest) {
        return digest.length() <= SHORT_DIGEST_LENGTH ? digest : digest.substring(START, SHORT_DIGEST_LENGTH);
    }

    /**
     * Pulls a missing image, and replaces or reports an outdated one.
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
     * Replaces an outdated image, unless told only to report it.
     *
     * <p>The image here stays usable while the newer one downloads: a tool
     * call meanwhile runs it, and says a newer one is on its way. Docker
     * moves the tag only once the pull is whole.
     */
    private void refresh() {
        if (!ALWAYS.equals(policy)) {
            notice = String.format(OUTDATED, image.image(), image.image());
            log.printf(LINE_FORMAT, notice);
            return;
        }
        log.printf(UPDATING_LOG, image.image());
        updating = true;
        ProcessOutcome outcome = image.pull();
        if (outcome.succeeded()) {
            updating = false;
            log.printf(PULLED_LOG, image.image());
            return;
        }
        notice = String.format(NOT_UPDATED, image.image(), reasonOf(outcome), image.image());
        updating = false;
        log.printf(FAILED_LOG, image.image(), reasonOf(outcome));
    }

    /**
     * Why a pull failed, in Docker's own words when it gave any, without a
     * closing full stop, which the sentence it goes into has already: a
     * missing {@code docker} read "not on the PATH.. Check".
     *
     * @param outcome what the pull did
     * @return the reason
     */
    private static String reasonOf(ProcessOutcome outcome) {
        String said = outcome.ran() ? outcome.standardError().strip() : outcome.failure().strip();
        String reason = said.isEmpty() ? String.format(EXIT_CODE, outcome.exitCode()) : said;
        return reason.endsWith(FULL_STOP) ? reason.substring(START, reason.length() - FULL_STOP.length()) : reason;
    }
}
