package com.kaizten.sheriff.infrastructure.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Sheriff's image on this machine: whether it is here, which build it is, and
 * whether a newer one has been published.
 *
 * <p>Nothing else here pulls it. {@code docker run} does, silently, when the
 * image is missing, and that is 4 GB inside the first analysis's time limit,
 * with nothing said while it happens. A {@code latest} that is here is never
 * refreshed at all. This is what lets the entry points do both on purpose.
 *
 * <p>"Newer" is asked of Docker Hub, by comparing the digest Hub publishes for
 * the tag with the one the local image was pulled by. It needs no pull and no
 * extra tool, and an image from another registry, or no network, is simply
 * "unknown", never "outdated".
 */
public final class SheriffImage {

    private static final String DOCKER = "docker";
    private static final String IMAGE = "image";
    private static final String INSPECT = "inspect";
    private static final String FORMAT_FLAG = "--format";
    private static final String REPO_DIGESTS = "{{json .RepoDigests}}";
    private static final String PULL = "pull";
    private static final String DIGEST_SEPARATOR = "@";
    private static final String TAG_SEPARATOR = ":";
    private static final String PATH_SEPARATOR = "/";
    private static final String DEFAULT_TAG = "latest";
    private static final String OFFICIAL_NAMESPACE = "library/";
    private static final String DOT = ".";
    private static final String HUB_TAG_URL = "https://hub.docker.com/v2/repositories/%s/tags/%s";
    private static final String DIGEST_FIELD = "digest";
    private static final int HTTP_OK = 200;
    private static final int NOT_FOUND = -1;
    private static final int START = 0;
    private static final int AFTER = 1;
    private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration PULL_TIMEOUT = Duration.ofMinutes(60);
    private static final Duration HUB_TIMEOUT = Duration.ofSeconds(5);
    private static final String CURRENT_DIRECTORY = ".";
    private static final Path HERE = Path.of(CURRENT_DIRECTORY);

    private final ProcessRunner processes;
    private final String image;
    private final Function<String, Optional<String>> publishedDigests;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires it to Docker, and to Docker Hub for what has been published.
     *
     * @param processes how to run docker
     * @param image the image, as configured
     */
    public SheriffImage(ProcessRunner processes, String image) {
        this(processes, image, SheriffImage::askDockerHub);
    }

    /**
     * Wires it with a chosen way of reading what has been published, so the
     * comparison can be tested without a network.
     *
     * @param processes how to run docker
     * @param image the image, as configured
     * @param publishedDigests the digest published for an image, or empty
     *     when it cannot be known
     */
    public SheriffImage(ProcessRunner processes, String image, Function<String, Optional<String>> publishedDigests) {
        this.processes = processes;
        this.image = image;
        this.publishedDigests = publishedDigests;
    }

    /**
     * The image, as configured.
     *
     * @return its name and tag
     */
    public String image() {
        return image;
    }

    /**
     * Whether the image is on this machine.
     *
     * @return {@code true} when Docker has it locally
     */
    public boolean present() {
        return inspect().succeeded();
    }

    /**
     * The digest the local image was pulled by.
     *
     * @return it, or empty when the image is missing or was built here
     */
    public Optional<String> localDigest() {
        ProcessOutcome outcome = inspect();
        if (!outcome.succeeded()) {
            return Optional.empty();
        }
        try {
            for (JsonNode entry : json.readTree(outcome.standardOutput().strip())) {
                String text = entry.asText();
                int at = text.indexOf(DIGEST_SEPARATOR);
                if (at != NOT_FOUND) {
                    return Optional.of(text.substring(at + AFTER));
                }
            }
        } catch (IOException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Whether a newer build of the tag has been published than the one here.
     *
     * @return {@code true} only when both digests are known and differ
     */
    public boolean outdated() {
        Optional<String> local = localDigest();
        Optional<String> published = publishedDigests.apply(image);
        return local.isPresent() && published.isPresent() && !local.get().equals(published.get());
    }

    /**
     * Pulls the image, with a time limit of its own: minutes, where an
     * analysis gets seconds.
     *
     * @return what {@code docker pull} did
     */
    public ProcessOutcome pull() {
        return processes.run(List.of(DOCKER, PULL, image), HERE, Map.of(), PULL_TIMEOUT);
    }

    /**
     * Asks Docker about the image.
     *
     * @return what {@code docker image inspect} answered
     */
    private ProcessOutcome inspect() {
        return processes.run(List.of(DOCKER, IMAGE, INSPECT, FORMAT_FLAG, REPO_DIGESTS, image), HERE, Map.of(),
                INSPECT_TIMEOUT);
    }

    /**
     * The digest Docker Hub publishes for an image's tag.
     *
     * @param image the image, as configured
     * @return the digest, or empty for another registry, a pinned digest, no
     *     network, or any answer that is not the expected one
     */
    static Optional<String> askDockerHub(String image) {
        Optional<String> url = hubUrl(image);
        if (url.isEmpty()) {
            return Optional.empty();
        }
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(HUB_TIMEOUT).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.get())).timeout(HUB_TIMEOUT).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HTTP_OK) {
                return Optional.empty();
            }
            String digest = new ObjectMapper().readTree(response.body()).path(DIGEST_FIELD).asText();
            return digest.isEmpty() ? Optional.empty() : Optional.of(digest);
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Where Docker Hub answers about an image's tag.
     *
     * @param image the image, as configured
     * @return the URL, or empty when the image does not live on Docker Hub or
     *     is pinned to a digest, which cannot go out of date
     */
    static Optional<String> hubUrl(String image) {
        if (image.contains(DIGEST_SEPARATOR)) {
            return Optional.empty();
        }
        int slash = image.indexOf(PATH_SEPARATOR);
        String first = slash == NOT_FOUND ? image : image.substring(START, slash);
        if (slash != NOT_FOUND && (first.contains(DOT) || first.contains(TAG_SEPARATOR))) {
            return Optional.empty();
        }
        int colon = image.lastIndexOf(TAG_SEPARATOR);
        boolean tagged = colon > slash;
        String repository = tagged ? image.substring(START, colon) : image;
        String tag = tagged ? image.substring(colon + AFTER) : DEFAULT_TAG;
        String qualified = repository.contains(PATH_SEPARATOR) ? repository : OFFICIAL_NAMESPACE + repository;
        return Optional.of(String.format(HUB_TAG_URL, qualified, tag));
    }
}
