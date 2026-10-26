package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The releases, as GitHub's public addresses answer: {@code releases/latest}
 * redirects to the newest tag, and a release's file redirects to where it is
 * kept.
 */
class GitHubReleasesTest {

    private HttpServer server;
    private URI repository;
    private final AtomicReference<String> latestLocation = new AtomicReference<>();
    private final AtomicReference<Integer> latestStatus = new AtomicReference<>(302);

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/owner/repo/releases/latest", exchange -> {
            if (latestLocation.get() != null) {
                exchange.getResponseHeaders().set("Location", latestLocation.get());
            }
            answer(exchange, latestStatus.get(), new byte[0]);
        });
        server.createContext("/owner/repo/releases/download/v0.1.9/sheriff-mcp.jar", exchange -> {
            exchange.getResponseHeaders().set("Location", "/objects/jar");
            answer(exchange, 302, new byte[0]);
        });
        server.createContext("/objects/jar", exchange -> answer(exchange, 200,
                "jar bytes".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/", exchange -> answer(exchange, 404, "Not Found".getBytes(StandardCharsets.UTF_8)));
        server.start();
        repository = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/owner/repo");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("the newest release is the tag releases/latest redirects to")
    void theNewestIsWhereLatestPoints() throws IOException {
        latestLocation.set("https://github.com/owner/repo/releases/tag/v0.1.9");
        assertEquals(Optional.of("0.1.9"), new GitHubReleases(repository).latest());
    }

    @Test
    @DisplayName("no redirect, or one to no tag, is no newest release")
    void noRedirectIsNoRelease() throws IOException {
        latestStatus.set(200);
        latestLocation.set("https://github.com/owner/repo/releases/tag/v0.1.9");
        assertEquals(Optional.empty(), new GitHubReleases(repository).latest(), "an answer that is no redirect");
        latestStatus.set(404);
        assertEquals(Optional.empty(), new GitHubReleases(repository).latest(), "a repository with no release");
        latestStatus.set(302);
        latestLocation.set("https://github.com/owner/repo/releases");
        assertEquals(Optional.empty(), new GitHubReleases(repository).latest(), "a redirect to no tag");
        latestLocation.set(null);
        assertEquals(Optional.empty(), new GitHubReleases(repository).latest(), "a redirect that says nowhere");
    }

    @Test
    @DisplayName("every answer from 300 to 399 is a redirect, and 400 is none")
    void theRedirectsLimits() throws IOException {
        latestLocation.set("https://github.com/owner/repo/releases/tag/v0.2.0");
        latestStatus.set(300);
        assertEquals(Optional.of("0.2.0"), new GitHubReleases(repository).latest());
        latestStatus.set(399);
        assertEquals(Optional.of("0.2.0"), new GitHubReleases(repository).latest());
        latestStatus.set(400);
        assertEquals(Optional.empty(), new GitHubReleases(repository).latest());
        latestStatus.set(299);
        assertEquals(Optional.empty(), new GitHubReleases(repository).latest());
    }

    @Test
    @DisplayName("a release's file is downloaded through GitHub's redirect, and one it does not have is refused")
    void filesAreDownloaded() throws IOException {
        GitHubReleases releases = new GitHubReleases(repository);
        assertArrayEquals("jar bytes".getBytes(StandardCharsets.UTF_8), releases.download("0.1.9", "sheriff-mcp.jar"));
        IOException missing = assertThrows(IOException.class, () -> releases.download("0.1.9", "nothing.jar"));
        assertTrue(missing.getMessage().endsWith("/owner/repo/releases/download/v0.1.9/nothing.jar answered 404."),
                missing.getMessage());
    }

    @Test
    @DisplayName("a place that cannot be reached is an IOException, and an interrupted wait says so too")
    void unreachableIsAnError() throws IOException {
        server.stop(0);
        GitHubReleases releases = new GitHubReleases(repository);
        assertThrows(IOException.class, releases::latest);
        Thread.currentThread().interrupt();
        boolean stillInterrupted;
        try {
            assertThrows(IOException.class, () -> releases.download("0.1.9", "sheriff-mcp.jar"));
        } finally {
            stillInterrupted = Thread.interrupted();
        }
        assertTrue(stillInterrupted, "the interrupt is kept for whoever asked for it");
    }

    @Test
    @DisplayName("this repository is the one the install line downloads from")
    void theRepository() {
        assertEquals("https://github.com/kaizten/sheriff-mcp-server", GitHubReleases.REPOSITORY.toString());
    }
}
