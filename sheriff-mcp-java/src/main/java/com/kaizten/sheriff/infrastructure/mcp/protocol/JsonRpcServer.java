package com.kaizten.sheriff.infrastructure.mcp.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.infrastructure.mcp.ServerVersion;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * The Model Context Protocol, over stdio, by hand -- the same shape the
 * Python server this replaced spoke, so a client already talking to that
 * one needs nothing different to talk to this.
 *
 * <p>MCP is JSON-RPC 2.0 with a fixed handshake, and over stdio the framing
 * is one JSON object per line. Four methods matter: {@code initialize}
 * negotiates the protocol version and identity, {@code notifications/initialized}
 * is acknowledged by answering nothing at all (replying to a notification is
 * itself a protocol error), {@code tools/list} returns the tool definitions,
 * and {@code tools/call} runs one and answers with a content block. {@code
 * ping} is answered with an empty result, and anything else is
 * method-not-found rather than a reason to stop serving.
 *
 * <p>A {@code tools/call} runs on a thread of its own, one call at a time in
 * the order they arrived, and everything else is answered at once: a call can
 * take minutes (Sheriff, then the project's tests), and while it ran this
 * server read nothing more, so a client's {@code ping} went unanswered until
 * it finished. Responses are written whole, one per line, whichever thread
 * writes them; when the input ends, the calls already read are answered
 * before this returns.
 *
 * <p>A tool that fails answers as a normal result carrying {@code isError},
 * never as a JSON-RPC error: a JSON-RPC error means the call itself was
 * malformed, and a model can act on "that component does not exist" only
 * when it arrives as text it can read.
 */
public final class JsonRpcServer {

    private static final String FIRST_PROTOCOL_VERSION = "2024-11-05";
    private static final String STREAMABLE_PROTOCOL_VERSION = "2025-03-26";
    private static final String FALLBACK_PROTOCOL_VERSION = "2025-06-18";
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS =
            List.of(FIRST_PROTOCOL_VERSION, STREAMABLE_PROTOCOL_VERSION, FALLBACK_PROTOCOL_VERSION);
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INTERNAL_ERROR = -32603;
    private static final String JSONRPC_FIELD = "jsonrpc";
    private static final String JSONRPC_VERSION = "2.0";
    private static final String ID_FIELD = "id";
    private static final String METHOD_FIELD = "method";
    private static final String PARAMS_FIELD = "params";
    private static final String RESULT_FIELD = "result";
    private static final String ERROR_FIELD = "error";
    private static final String CODE_FIELD = "code";
    private static final String MESSAGE_FIELD = "message";
    private static final String EMPTY_METHOD = "";
    private static final String INITIALIZE_METHOD = "initialize";
    private static final String PING_METHOD = "ping";
    private static final String TOOLS_LIST_METHOD = "tools/list";
    private static final String TOOLS_CALL_METHOD = "tools/call";
    private static final String UNKNOWN_METHOD = "Unknown method: %s";
    private static final String PROTOCOL_VERSION_FIELD = "protocolVersion";
    private static final String CAPABILITIES_FIELD = "capabilities";
    private static final String TOOLS_FIELD = "tools";
    private static final String LIST_CHANGED_FIELD = "listChanged";
    private static final String SERVER_INFO_FIELD = "serverInfo";
    private static final String NAME_FIELD = "name";
    private static final String VERSION_FIELD = "version";
    private static final String CONTENT_FIELD = "content";
    private static final String TYPE_FIELD = "type";
    private static final String TEXT_TYPE = "text";
    private static final String TEXT_FIELD = "text";
    private static final String IS_ERROR_FIELD = "isError";
    private static final String NAME_ARGUMENT_FIELD = "name";
    private static final String ARGUMENTS_FIELD = "arguments";
    private static final String INVALID_JSON = "Invalid JSON: %s";
    private static final String NOT_AN_OBJECT = "A request must be a JSON object.";
    private static final String INSTRUCTIONS_FIELD = "instructions";
    private static final String NO_INSTRUCTIONS = "";
    private static final String CALL_THREAD = "sheriff-call";
    private static final long DRAIN_HOURS = 6;

    private final Supplier<List<Map<String, Object>>> listTools;
    private final BiFunction<String, Map<String, Object>, ToolOutcome> callTool;
    private final String serverName;
    private final String instructions;
    private final ObjectMapper json = new ObjectMapper();
    private final Object writing = new Object();

    /**
     * Wires the protocol layer to what actually answers the calls.
     *
     * @param listTools every tool's definition
     * @param callTool runs one tool and reports whether it failed
     * @param serverName this server's identity in the handshake
     */
    public JsonRpcServer(
            Supplier<List<Map<String, Object>>> listTools,
            BiFunction<String, Map<String, Object>, ToolOutcome> callTool,
            String serverName) {
        this(listTools, callTool, serverName, NO_INSTRUCTIONS);
    }

    /**
     * Wires the protocol layer, with instructions the client hands the model
     * when it connects.
     *
     * @param listTools every tool's definition
     * @param callTool runs one tool and reports whether it failed
     * @param serverName this server's identity in the handshake
     * @param instructions how to use the tools together, or empty for none
     */
    public JsonRpcServer(
            Supplier<List<Map<String, Object>>> listTools,
            BiFunction<String, Map<String, Object>, ToolOutcome> callTool,
            String serverName,
            String instructions) {
        this.listTools = listTools;
        this.callTool = callTool;
        this.serverName = serverName;
        this.instructions = instructions;
    }

    /**
     * The response to one already-parsed request.
     *
     * @param message the request, as a JSON object
     * @return the response, or {@code null} for a notification, which must
     *     not be answered at all
     */
    public ObjectNode handle(JsonNode message) {
        if (!message.has(ID_FIELD)) {
            return null;
        }
        JsonNode requestId = message.get(ID_FIELD);
        String method = message.path(METHOD_FIELD).asText(EMPTY_METHOD);
        JsonNode params = message.path(PARAMS_FIELD);
        if (INITIALIZE_METHOD.equals(method)) {
            return result(requestId, initializeResult(params));
        }
        if (PING_METHOD.equals(method)) {
            return result(requestId, json.createObjectNode());
        }
        if (TOOLS_LIST_METHOD.equals(method)) {
            ObjectNode body = json.createObjectNode();
            body.set(TOOLS_FIELD, json.valueToTree(listTools.get()));
            return result(requestId, body);
        }
        if (TOOLS_CALL_METHOD.equals(method)) {
            return result(requestId, callResult(params));
        }
        return error(requestId, METHOD_NOT_FOUND, String.format(UNKNOWN_METHOD, method));
    }

    /**
     * The {@code initialize} handshake's result.
     *
     * <p>The version is the client's only when this server knows it; any
     * other is answered with the one this server was built against, and the
     * client decides whether it can speak that. It used to echo whatever was
     * asked, {@code 1999-01-01} included.
     *
     * @param params what the client asked for
     * @return the protocol version, capabilities and identity to answer with
     */
    private ObjectNode initializeResult(JsonNode params) {
        JsonNode requested = params.path(PROTOCOL_VERSION_FIELD);
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested.asText())
                ? requested.asText()
                : FALLBACK_PROTOCOL_VERSION;
        ObjectNode body = json.createObjectNode();
        body.put(PROTOCOL_VERSION_FIELD, version);
        ObjectNode capabilities = json.createObjectNode();
        ObjectNode tools = json.createObjectNode();
        tools.put(LIST_CHANGED_FIELD, false);
        capabilities.set(TOOLS_FIELD, tools);
        body.set(CAPABILITIES_FIELD, capabilities);
        ObjectNode identity = json.createObjectNode();
        identity.put(NAME_FIELD, serverName);
        identity.put(VERSION_FIELD, ServerVersion.current());
        body.set(SERVER_INFO_FIELD, identity);
        if (!instructions.isBlank()) {
            body.put(INSTRUCTIONS_FIELD, instructions);
        }
        return body;
    }

    /**
     * Runs the tool a {@code tools/call} request names.
     *
     * @param params the request's parameters
     * @return the content block and error flag the client reads
     */
    private ObjectNode callResult(JsonNode params) {
        String name = params.path(NAME_ARGUMENT_FIELD).asText(EMPTY_METHOD);
        Map<String, Object> arguments = json.convertValue(
                params.path(ARGUMENTS_FIELD).isObject() ? params.path(ARGUMENTS_FIELD) : json.createObjectNode(),
                Map.class);
        ToolOutcome outcome = callTool.apply(name, arguments);
        ObjectNode block = json.createObjectNode();
        block.put(TYPE_FIELD, TEXT_TYPE);
        block.put(TEXT_FIELD, outcome.text());
        ObjectNode body = json.createObjectNode();
        body.set(CONTENT_FIELD, json.createArrayNode().add(block));
        body.put(IS_ERROR_FIELD, outcome.isError());
        return body;
    }

    /**
     * One successful response envelope.
     *
     * @param requestId the request this answers
     * @param resultBody the result to carry
     * @return the envelope
     */
    private ObjectNode result(JsonNode requestId, ObjectNode resultBody) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put(JSONRPC_FIELD, JSONRPC_VERSION);
        envelope.set(ID_FIELD, requestId);
        envelope.set(RESULT_FIELD, resultBody);
        return envelope;
    }

    /**
     * One failed response envelope.
     *
     * @param requestId the request this answers, {@code null} when it could
     *     not be read at all
     * @param code the JSON-RPC error code
     * @param message why the call failed
     * @return the envelope
     */
    private ObjectNode error(JsonNode requestId, int code, String message) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put(JSONRPC_FIELD, JSONRPC_VERSION);
        envelope.set(ID_FIELD, requestId);
        ObjectNode failure = json.createObjectNode();
        failure.put(CODE_FIELD, code);
        failure.put(MESSAGE_FIELD, message);
        envelope.set(ERROR_FIELD, failure);
        return envelope;
    }

    /**
     * Reads one request line at a time until the input ends, answering each
     * on the given stream.
     *
     * @param input where requests arrive, one JSON object per line
     * @param output where responses are written, one JSON object per line
     * @throws IOException when the input cannot be read
     */
    public void serve(BufferedReader input, PrintStream output) throws IOException {
        ExecutorService calls = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, CALL_THREAD);
            thread.setDaemon(true);
            return thread;
        });
        try {
            String line = input.readLine();
            while (line != null) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) {
                    dispatch(trimmed, output, calls);
                }
                line = input.readLine();
            }
        } finally {
            calls.shutdown();
            awaitCalls(calls);
        }
    }

    /**
     * Answers one raw line of input: a {@code tools/call} on the calls'
     * thread, anything else at once.
     *
     * @param line the request, not yet parsed
     * @param output where the response goes
     * @param calls the thread that runs the tools
     */
    private void dispatch(String line, PrintStream output, ExecutorService calls) {
        JsonNode message;
        try {
            message = json.readTree(line);
        } catch (JsonProcessingException exception) {
            write(output, error(null, PARSE_ERROR, String.format(INVALID_JSON, exception.getMessage())));
            return;
        }
        if (!message.isObject()) {
            write(output, error(null, INVALID_REQUEST, NOT_AN_OBJECT));
            return;
        }
        if (message.has(ID_FIELD) && TOOLS_CALL_METHOD.equals(message.path(METHOD_FIELD).asText(EMPTY_METHOD))) {
            calls.execute(() -> write(output, answer(message)));
        } else {
            write(output, answer(message));
        }
    }

    /**
     * The response to one parsed message, catching whatever it takes to keep
     * the session alive: one bad call must never end it.
     *
     * @param message the request, a JSON object
     * @return the response to write, or {@code null} for a notification
     */
    private ObjectNode answer(JsonNode message) {
        try {
            return handle(message);
        } catch (RuntimeException exception) {
            JsonNode requestId = message.has(ID_FIELD) ? message.get(ID_FIELD) : null;
            return error(requestId, INTERNAL_ERROR, exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    /**
     * Waits for the calls already read to be answered, for when the input
     * has ended: a client that closes its end may still be reading.
     *
     * @param calls the thread that runs the tools, already shut down
     */
    private static void awaitCalls(ExecutorService calls) {
        try {
            calls.awaitTermination(DRAIN_HOURS, TimeUnit.HOURS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Writes one response as a single line and flushes it -- a client
     * reading line by line never sees a partial message, nor two answers
     * written at once by two threads interleaved.
     *
     * @param output where to write
     * @param payload the response, {@code null} to write nothing
     */
    private void write(PrintStream output, ObjectNode payload) {
        if (payload == null) {
            return;
        }
        synchronized (writing) {
            output.println(payload.toString());
            output.flush();
        }
    }
}
