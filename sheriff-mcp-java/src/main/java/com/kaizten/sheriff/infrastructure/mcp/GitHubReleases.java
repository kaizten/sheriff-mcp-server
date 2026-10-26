package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * The releases GitHub publishes for this repository, read through the same
 * public addresses the install line downloads from: no API, so no rate limit
 * and no token. The newest release is where {@code releases/latest} points,
 * which never names a prerelease.
 */
public final class GitHubReleases implements ReleaseSource {

    private static final String REPOSITORY_ADDRESS = "https://github.com/kaizten/sheriff-mcp-server";

    /**
     * This repository on GitHub.
     */
    public static final URI REPOSITORY = URI.create(REPOSITORY_ADDRESS);

    private static final String LATEST = "/releases/latest";
    private static final String DOWNLOAD = "/releases/download/v%s/%s";
    private static final String LOCATION = "location";
    private static final String TAG_PATH = "/releases/tag/v";
    private static final Duration TIME_LIMIT = Duration.ofSeconds(20);
    private static final int REDIRECTS_FROM = 300;
    private static final int REDIRECTS_TO = 400;
    private static final int OK = 200;
    private static final String NOT_DOWNLOADED = "%s answered %d.";

    private final URI repository;
    private final HttpClient asking;
    private final HttpClient downloading;

    /**
     * The releases of a repository on GitHub, or of a server that answers the
     * same way, as a test's does.
     *
     * @param repository the repository's address, as {@link #REPOSITORY}
     */
    public GitHubReleases(URI repository) {
        this.repository = repository;
        this.asking = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIME_LIMIT).build();
        this.downloading = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(TIME_LIMIT).build();
    }

    /**
     * The newest release, from where {@code releases/latest} redirects to:
     * {@code .../releases/tag/v0.1.8}.
     *
     * @return its version, or empty when there is no redirect to a tag
     * @throws IOException when GitHub cannot be reached
     */
    @Override
    public Optional<String> latest() throws IOException {
        HttpResponse<Void> answer = send(asking, URI.create(repository + LATEST),
                HttpResponse.BodyHandlers.discarding());
        if (answer.statusCode() < REDIRECTS_FROM || answer.statusCode() >= REDIRECTS_TO) {
            return Optional.empty();
        }
        return answer.headers().firstValue(LOCATION).filter(location -> location.contains(TAG_PATH))
                .map(location -> location.substring(location.lastIndexOf(TAG_PATH) + TAG_PATH.length()));
    }

    /**
     * One file of a release, from {@code releases/download/v<version>/<file>},
     * following GitHub's redirect to where it keeps it.
     *
     * @param version the release
     * @param file the file's name
     * @return its bytes
     * @throws IOException when it cannot be downloaded
     */
    @Override
    public byte[] download(String version, String file) throws IOException {
        URI address = URI.create(repository + String.format(DOWNLOAD, version, file));
        HttpResponse<byte[]> answer = send(downloading, address, HttpResponse.BodyHandlers.ofByteArray());
        if (answer.statusCode() != OK) {
            throw new IOException(String.format(NOT_DOWNLOADED, address, answer.statusCode()));
        }
        return answer.body();
    }

    /**
     * Sends one request and waits for its answer, within the time limit.
     *
     * @param client the client to send it with
     * @param address where to
     * @param body how to read the answer's body
     * @param <T> what the body is read as
     * @return the answer
     * @throws IOException when it cannot be sent or the wait is interrupted
     */
    private static <T> HttpResponse<T> send(HttpClient client, URI address, HttpResponse.BodyHandler<T> body)
            throws IOException {
        try {
            return client.send(HttpRequest.newBuilder(address).timeout(TIME_LIMIT).GET().build(), body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }
}
