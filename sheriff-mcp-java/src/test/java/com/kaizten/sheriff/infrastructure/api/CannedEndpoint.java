package com.kaizten.sheriff.infrastructure.api;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local HTTP endpoint that answers every POST with the next canned JSON
 * body, so an API fixer can be driven through whole conversations without a
 * network, a key or a token.
 */
final class CannedEndpoint implements AutoCloseable {

    /**
     * A reply starting with this is sent as a 400.
     */
    static final String FAILURE_PREFIX = "!400 ";

    /**
     * A reply starting with {@code !NNN } is sent with that status, and any
     * status but a success asks for an immediate retry, so a fixer that
     * retries does not make a test wait.
     */
    private static final Pattern STATUS_PREFIX = Pattern.compile("^!(\\d{3}) ");

    private final HttpServer server;
    private final Deque<String> replies;
    private final List<String> requests = new ArrayList<>();
    private final List<Map<String, List<String>>> headers = new ArrayList<>();

    /**
     * Starts serving on a free local port.
     *
     * @param replies the bodies to answer with, in order
     * @throws IOException when no port can be bound
     */
    CannedEndpoint(List<String> replies) throws IOException {
        this.replies = new ArrayDeque<>(replies);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            headers.add(Map.copyOf(exchange.getRequestHeaders()));
            String reply = this.replies.isEmpty() ? "{}" : this.replies.poll();
            Matcher prefix = STATUS_PREFIX.matcher(reply);
            int status = prefix.find() ? Integer.parseInt(prefix.group(1)) : 200;
            byte[] body = prefix.replaceFirst("").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (status != 200) {
                exchange.getResponseHeaders().add("retry-after-ms", "0");
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /**
     * The headers of every request, in order, under the names the server
     * normalized them to (first letter capitalized).
     *
     * @return those headers
     */
    List<Map<String, List<String>>> headers() {
        return headers;
    }

    /**
     * Where the endpoint is listening.
     *
     * @return its base URL
     */
    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /**
     * What the fixer sent, one body per request.
     *
     * @return those bodies, in order
     */
    List<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
