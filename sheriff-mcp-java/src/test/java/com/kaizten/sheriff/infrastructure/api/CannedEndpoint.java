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

    private final HttpServer server;
    private final Deque<String> replies;
    private final List<String> requests = new ArrayList<>();

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
            String reply = this.replies.isEmpty() ? "{}" : this.replies.poll();
            int status = reply.startsWith(FAILURE_PREFIX) ? 400 : 200;
            byte[] body = reply.replace(FAILURE_PREFIX, "").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
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
