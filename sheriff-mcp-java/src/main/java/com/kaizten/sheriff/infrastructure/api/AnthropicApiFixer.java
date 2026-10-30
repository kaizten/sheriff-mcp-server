package com.kaizten.sheriff.infrastructure.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The alternative fixer: the Anthropic Messages API directly, billed per
 * token, instead of the Claude Code subscription the CLI backend rides on.
 *
 * <p>The API has no built-in file tools, so this adapter brings its own —
 * {@code read_file} and {@code write_file} — and drives the tool-use loop
 * itself. That extra work buys the one thing the CLI backend cannot offer:
 * because the write tool belongs to this agent, a write outside the pass's
 * scope is refused before it happens (see {@link FileTools}), rather than
 * detected afterwards by asking git what changed.
 *
 * <p>The loop is bounded. A model that keeps calling tools without ever
 * finishing would otherwise spend money indefinitely, so there is a hard cap
 * on iterations and reaching it is reported as a failed fix rather than a
 * silent partial one.
 *
 * <p>A reply that stopped for any reason other than finishing is not acted
 * on. One cut off at {@code max_tokens} can end inside a {@code write_file}
 * call whose content never arrived, and a refusal carries nothing to apply;
 * both are reported as a failed pass instead.
 *
 * <p>It speaks HTTP through the JDK's own client, as {@link OpenAiApiFixer}
 * does, rather than through Anthropic's SDK: the SDK, with the Kotlin
 * runtime and the HTTP stacks it brings, was nine tenths of the jar and of
 * what the Maven plugin made every build download, for one request shape.
 * What the SDK did besides sending it is kept: a request that failed in a way
 * worth trying again (a timeout, a conflict, a rate limit, an overloaded or
 * failing server, a dropped connection) is retried twice, after the delay the
 * server asks for when it names one.
 */
public final class AnthropicApiFixer implements CodeFixer {

    private static final String READ_TOOL = "read_file";
    private static final String WRITE_TOOL = "write_file";
    private static final String PATH_ARGUMENT = "path";
    private static final String CONTENT_ARGUMENT = "content";
    private static final String STRING_TYPE = "string";
    private static final String OBJECT_TYPE = "object";
    private static final String READ_DESCRIPTION =
            "Read a file from the repository. The path is relative to the repository root.";
    private static final String WRITE_DESCRIPTION =
            "Write the full new contents of a file. The path is relative to the repository root, "
                    + "and only the files this pass is allowed to modify will be accepted.";
    private static final String MISSING_KEY =
            "No API key configured. Set ANTHROPIC_API_KEY, or use the claude_cli backend, which "
                    + "needs no key.";
    private static final String EXHAUSTED =
            "The model was still calling tools after %d turns; stopping rather than spending more.";
    private static final String UNKNOWN_TOOL = "Unknown tool '%s'.";
    private static final String TRANSCRIPT_SEPARATOR = "\n";
    private static final String OUTCOME_SEPARATOR = ": ";
    private static final String MISSING_ARGUMENT = "";
    private static final String NOTHING_WRONG = "";
    private static final String TRUNCATED =
            "The model's reply was cut off at the %d-token limit, so nothing in that reply was applied. "
                    + "Raise ANTHROPIC_MAX_TOKENS if this keeps happening.";
    private static final String REFUSED =
            "The model declined this request (stop reason 'refusal'), so nothing in that reply was applied.";
    private static final String BAD_STATUS = "%s answered %d: %s";
    private static final String UNREACHABLE = "Could not reach %s: %s";
    private static final String NOT_JSON = "%s answered with something that is not JSON: %s";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String TRAILING_SLASH = "/";
    private static final String CONTENT_TYPE = "content-type";
    private static final String JSON_TYPE = "application/json";
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String VERSION_HEADER = "anthropic-version";
    private static final String API_VERSION = "2023-06-01";
    private static final String RETRY_AFTER_HEADER = "retry-after";
    private static final String RETRY_AFTER_MS_HEADER = "retry-after-ms";
    private static final String SHOULD_RETRY_HEADER = "x-should-retry";
    private static final String TRUE = "true";
    private static final String FALSE = "false";
    private static final String MODEL_FIELD = "model";
    private static final String MAX_TOKENS_FIELD = "max_tokens";
    private static final String TOOLS_FIELD = "tools";
    private static final String MESSAGES_FIELD = "messages";
    private static final String ROLE_FIELD = "role";
    private static final String CONTENT_FIELD = CONTENT_ARGUMENT;
    private static final String TYPE_FIELD = "type";
    private static final String NAME_FIELD = "name";
    private static final String ID_FIELD = "id";
    private static final String INPUT_FIELD = "input";
    private static final String TEXT_FIELD = "text";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String INPUT_SCHEMA_FIELD = "input_schema";
    private static final String PROPERTIES_FIELD = "properties";
    private static final String REQUIRED_FIELD = "required";
    private static final String STOP_REASON_FIELD = "stop_reason";
    private static final String TOOL_USE_ID_FIELD = "tool_use_id";
    private static final String USER_ROLE = "user";
    private static final String ASSISTANT_ROLE = "assistant";
    private static final String TOOL_USE_TYPE = "tool_use";
    private static final String TOOL_RESULT_TYPE = "tool_result";
    private static final String TEXT_TYPE = TEXT_FIELD;
    private static final String MAX_TOKENS_STOP = MAX_TOKENS_FIELD;
    private static final String REFUSAL_STOP = "refusal";
    private static final int FIRST_TURN = 0;
    private static final int FIRST_ERROR_STATUS = 300;
    private static final int REPORTED_BODY_LIMIT = 400;
    private static final int RETRIES = 2;
    private static final int FIRST_SERVER_ERROR = 500;
    private static final Set<Integer> RETRIED_STATUSES = Set.of(408, 409, 429);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(500);
    private static final Duration LONGEST_BACKOFF = Duration.ofSeconds(8);
    private static final Duration LONGEST_ASKED_DELAY = Duration.ofSeconds(60);
    private static final double MILLISECONDS_PER_SECOND = 1000.0;
    private static final double ONE_PER_SECOND = 1.0;
    private static final int EQUAL = 0;
    private static final long ONE = 1L;
    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final long maxTokens;
    private final int maxToolIterations;
    private final Path repositoryRoot;
    private final Path workspace;
    private final PromptLog promptLog;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the fixer to an API key and a repository.
     *
     * @param apiKey the key to authenticate with, empty when none is set
     * @param baseUrl where the Messages API lives, empty for Anthropic's own
     *     endpoint; set for a gateway or proxy in front of it
     * @param model the model to ask
     * @param maxTokens the cap on one response
     * @param maxToolIterations how many tool-calling turns are allowed
     * @param repositoryRoot the repository the tools may work in
     * @param promptLog where to record what was asked
     */
    public AnthropicApiFixer(
            String apiKey,
            String baseUrl,
            String model,
            long maxTokens,
            int maxToolIterations,
            Path repositoryRoot,
            PromptLog promptLog) {
        this(apiKey, baseUrl, model, maxTokens, maxToolIterations, repositoryRoot, repositoryRoot, promptLog);
    }

    /**
     * Wires the fixer, with its file tools kept to one folder of the
     * repository, as {@link FileTools} explains.
     *
     * @param apiKey the key to authenticate with, empty when none is set
     * @param baseUrl where the Messages API lives, empty for Anthropic's own
     *     endpoint
     * @param model the model to ask
     * @param maxTokens the cap on one response
     * @param maxToolIterations how many tool-calling turns are allowed
     * @param repositoryRoot the directory the model's paths are relative to
     * @param workspace the only folder the tools may read or write in
     * @param promptLog where to record what was asked
     */
    public AnthropicApiFixer(
            String apiKey,
            String baseUrl,
            String model,
            long maxTokens,
            int maxToolIterations,
            Path repositoryRoot,
            Path workspace,
            PromptLog promptLog) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxToolIterations = maxToolIterations;
        this.repositoryRoot = repositoryRoot;
        this.workspace = workspace;
        this.promptLog = promptLog;
    }

    /**
     * Asks the model to carry out one fixing pass through its own file tools.
     *
     * @param request what to fix, and which files may be touched
     * @return the outcome, failed when no key is set or the loop ran out of
     *     turns
     */
    @Override
    public FixResult fix(FixRequest request) {
        if (apiKey.isBlank()) {
            return FixResult.failed(MISSING_KEY);
        }
        FileTools tools = new FileTools(repositoryRoot, workspace, request.allowedFiles());
        List<String> transcript = new ArrayList<>();
        try {
            FixResult result = converse(request, tools, transcript);
            promptLog.record(request.label(), request.prompt(), String.join(TRANSCRIPT_SEPARATOR, transcript));
            return result;
        } catch (RuntimeException exception) {
            promptLog.record(request.label(), request.prompt(), String.valueOf(exception.getMessage()));
            return FixResult.failed(String.valueOf(exception.getMessage()));
        }
    }

    /**
     * The bounded tool-use loop: ask, answer whatever tools were called, ask
     * again, and stop the moment the model replies without calling any.
     *
     * <p>The model's reply goes back into the conversation as it came, every
     * block of it, which is what the API expects of the next request.
     *
     * @param request what to fix
     * @param tools the file operations the model is allowed
     * @param transcript the running log of what happened
     * @return the outcome, failed when a reply is unusable or the turn cap is
     *     reached first
     */
    private FixResult converse(FixRequest request, FileTools tools, List<String> transcript) {
        HttpClient client = HttpClient.newHttpClient();
        ArrayNode conversation = json.createArrayNode();
        conversation.add(message(USER_ROLE).put(CONTENT_FIELD, request.prompt()));
        for (int turn = FIRST_TURN; turn < maxToolIterations; turn++) {
            JsonNode reply = ask(client, conversation);
            conversation.add(message(ASSISTANT_ROLE).set(CONTENT_FIELD, reply.path(CONTENT_FIELD)));
            recordText(reply, transcript);
            String unusable = unusableReply(reply);
            if (!unusable.isEmpty()) {
                transcript.add(unusable);
                return FixResult.failed(unusable);
            }
            List<JsonNode> calls = toolCalls(reply);
            if (calls.isEmpty()) {
                return FixResult.succeeded(String.join(TRANSCRIPT_SEPARATOR, transcript));
            }
            conversation.add(resultsFor(calls, tools, transcript));
        }
        return FixResult.failed(String.format(EXHAUSTED, maxToolIterations));
    }

    /**
     * Why a reply must not be acted on, if it must not.
     *
     * @param reply the reply that came back
     * @return the reason, or the empty string when the reply is usable
     */
    private String unusableReply(JsonNode reply) {
        String reason = reply.path(STOP_REASON_FIELD).asText(MISSING_ARGUMENT);
        if (MAX_TOKENS_STOP.equals(reason)) {
            return String.format(TRUNCATED, maxTokens);
        }
        if (REFUSAL_STOP.equals(reason)) {
            return REFUSED;
        }
        return NOTHING_WRONG;
    }

    /**
     * One request to the Messages API, retried while it fails in a way worth
     * trying again.
     *
     * @param client the client this pass sends through
     * @param conversation everything said so far
     * @return the reply, parsed
     * @throws IllegalStateException when the endpoint cannot be reached, keeps
     *     failing, or answers with something that is not JSON
     */
    private JsonNode ask(HttpClient client, ArrayNode conversation) {
        String endpoint = endpoint();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(REQUEST_TIMEOUT)
                .header(CONTENT_TYPE, JSON_TYPE)
                .header(API_KEY_HEADER, apiKey)
                .header(VERSION_HEADER, API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body(conversation), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = sendRetrying(client, request, endpoint);
        if (response.statusCode() >= FIRST_ERROR_STATUS) {
            throw new IllegalStateException(
                    String.format(BAD_STATUS, endpoint, response.statusCode(), trimmed(response.body())));
        }
        try {
            return json.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException(String.format(NOT_JSON, endpoint, trimmed(response.body())), exception);
        }
    }

    /**
     * Where the Messages API is, from the configured base or Anthropic's own.
     *
     * @return the endpoint's URL
     */
    String endpoint() {
        String base = baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
        while (base.endsWith(TRAILING_SLASH)) {
            base = base.substring(0, base.length() - TRAILING_SLASH.length());
        }
        return base + MESSAGES_PATH;
    }

    /**
     * Sends a request, and sends it again, up to twice, while it fails in a
     * way worth trying again; the answer of the last attempt is returned
     * whatever it is, and a dropped connection on the last one is reported.
     *
     * @param client the client to send through
     * @param request the request
     * @param endpoint what to name in a failure
     * @return the last response
     */
    private HttpResponse<String> sendRetrying(HttpClient client, HttpRequest request, String endpoint) {
        for (int attempt = FIRST_TURN; ; attempt++) {
            boolean last = attempt >= RETRIES;
            Optional<HttpResponse<String>> response = sendOnce(client, request, endpoint, last);
            if (response.isPresent() && (last || !retried(response.get()))) {
                return response.get();
            }
            pause(delayBefore(attempt, response));
        }
    }

    /**
     * One attempt.
     *
     * @param client the client to send through
     * @param request the request
     * @param endpoint what to name in a failure
     * @param last whether a transport failure is to be reported rather than
     *     retried
     * @return the response, or empty when the connection failed and another
     *     attempt follows
     */
    private static Optional<HttpResponse<String>> sendOnce(
            HttpClient client, HttpRequest request, String endpoint, boolean last) {
        try {
            return Optional.of(client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            if (last) {
                throw new IllegalStateException(String.format(UNREACHABLE, endpoint, reasonOf(exception)), exception);
            }
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(String.format(UNREACHABLE, endpoint, reasonOf(exception)), exception);
        }
    }

    /**
     * Whether a response is one the SDK this replaced would have retried: the
     * server says so, or it is a timeout, a conflict, a rate limit or a
     * server error, unless the server says not to.
     *
     * @param response what came back
     * @return {@code true} when it is worth another attempt
     */
    static boolean retried(HttpResponse<String> response) {
        Optional<String> said = response.headers().firstValue(SHOULD_RETRY_HEADER);
        if (said.filter(TRUE::equalsIgnoreCase).isPresent()) {
            return true;
        }
        if (said.filter(FALSE::equalsIgnoreCase).isPresent()) {
            return false;
        }
        int status = response.statusCode();
        return RETRIED_STATUSES.contains(status) || status >= FIRST_SERVER_ERROR;
    }

    /**
     * How long to wait before the next attempt: what the server asked for,
     * when it asked for something reasonable, or else a delay that doubles
     * with each attempt.
     *
     * @param attempt the attempt that just failed, from zero
     * @param response its response, empty when the connection failed
     * @return the delay
     */
    static Duration delayBefore(int attempt, Optional<HttpResponse<String>> response) {
        Optional<Duration> asked = response.flatMap(AnthropicApiFixer::askedDelay);
        if (asked.isPresent()) {
            return asked.get();
        }
        Duration backoff = FIRST_BACKOFF.multipliedBy(ONE << attempt);
        return backoff.compareTo(LONGEST_BACKOFF) > EQUAL ? LONGEST_BACKOFF : backoff;
    }

    /**
     * The delay a response asks for, in milliseconds or in seconds.
     *
     * @param response what came back
     * @return that delay, empty when none is named, it cannot be read, or it
     *     is longer than a minute
     */
    private static Optional<Duration> askedDelay(HttpResponse<String> response) {
        Optional<Duration> asked = response.headers().firstValue(RETRY_AFTER_MS_HEADER)
                .flatMap(value -> secondsOf(value, MILLISECONDS_PER_SECOND))
                .or(() -> response.headers().firstValue(RETRY_AFTER_HEADER).flatMap(value -> secondsOf(value, ONE_PER_SECOND)));
        return asked.filter(delay -> !delay.isNegative() && delay.compareTo(LONGEST_ASKED_DELAY) <= EQUAL);
    }

    /**
     * A number of some unit as a duration.
     *
     * @param value the number, as the header carries it
     * @param perSecond how many of that unit make a second
     * @return the duration, empty when the value is not a number
     */
    private static Optional<Duration> secondsOf(String value, double perSecond) {
        try {
            double seconds = Double.parseDouble(value.strip()) / perSecond;
            return Optional.of(Duration.ofMillis(Math.round(seconds * MILLISECONDS_PER_SECOND)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /**
     * Waits before another attempt.
     *
     * @param delay how long
     * @throws IllegalStateException when the wait is interrupted
     */
    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(reasonOf(exception), exception);
        }
    }

    /**
     * Why a transport failure happened, in words rather than as "null": a
     * refused connection carries no message at all.
     *
     * @param exception what went wrong
     * @return its message, or the name of its type when it has none
     */
    private static String reasonOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    /**
     * The request body for one call, with both file tools offered.
     *
     * @param conversation everything said so far
     * @return that body, as JSON
     */
    private String body(ArrayNode conversation) {
        ObjectNode payload = json.createObjectNode();
        payload.put(MODEL_FIELD, model);
        payload.put(MAX_TOKENS_FIELD, maxTokens);
        ArrayNode tools = payload.putArray(TOOLS_FIELD);
        tools.add(tool(READ_TOOL, READ_DESCRIPTION, false));
        tools.add(tool(WRITE_TOOL, WRITE_DESCRIPTION, true));
        payload.set(MESSAGES_FIELD, conversation);
        return payload.toString();
    }

    /**
     * One tool declaration.
     *
     * @param name what the tool is called
     * @param description what it is for
     * @param withContent whether it also takes the file's new contents
     * @return that declaration
     */
    private ObjectNode tool(String name, String description, boolean withContent) {
        ObjectNode declaration = json.createObjectNode();
        declaration.put(NAME_FIELD, name);
        declaration.put(DESCRIPTION_FIELD, description);
        ObjectNode schema = declaration.putObject(INPUT_SCHEMA_FIELD);
        schema.put(TYPE_FIELD, OBJECT_TYPE);
        ObjectNode properties = schema.putObject(PROPERTIES_FIELD);
        properties.putObject(PATH_ARGUMENT).put(TYPE_FIELD, STRING_TYPE);
        ArrayNode required = schema.putArray(REQUIRED_FIELD);
        required.add(PATH_ARGUMENT);
        if (withContent) {
            properties.putObject(CONTENT_ARGUMENT).put(TYPE_FIELD, STRING_TYPE);
            required.add(CONTENT_ARGUMENT);
        }
        return declaration;
    }

    /**
     * An empty message from one side of the conversation.
     *
     * @param role who says it
     * @return that message, its content still to be set
     */
    private ObjectNode message(String role) {
        return json.createObjectNode().put(ROLE_FIELD, role);
    }

    /**
     * Turns the model's tool calls into results the conversation can carry.
     *
     * @param calls what the model asked for
     * @param tools the file operations it is allowed
     * @param transcript the running log of what happened
     * @return one user message holding every result, which is how parallel
     *     tool calls must be answered
     */
    private ObjectNode resultsFor(List<JsonNode> calls, FileTools tools, List<String> transcript) {
        ObjectNode message = message(USER_ROLE);
        ArrayNode results = message.putArray(CONTENT_FIELD);
        for (JsonNode call : calls) {
            String name = call.path(NAME_FIELD).asText();
            String outcome = execute(name, call.path(INPUT_FIELD), tools);
            transcript.add(name + OUTCOME_SEPARATOR + outcome);
            results.addObject()
                    .put(TYPE_FIELD, TOOL_RESULT_TYPE)
                    .put(TOOL_USE_ID_FIELD, call.path(ID_FIELD).asText())
                    .put(CONTENT_FIELD, outcome);
        }
        return message;
    }

    /**
     * Runs one tool call against the file tools.
     *
     * @param name the tool the model called
     * @param input the arguments it gave
     * @param tools the file operations it is allowed
     * @return what to report back to the model, a refusal included
     */
    private static String execute(String name, JsonNode input, FileTools tools) {
        Map<String, String> arguments = argumentsOf(input);
        String path = arguments.getOrDefault(PATH_ARGUMENT, MISSING_ARGUMENT);
        if (READ_TOOL.equals(name)) {
            return tools.readFile(path);
        }
        if (WRITE_TOOL.equals(name)) {
            return tools.writeFile(path, arguments.get(CONTENT_ARGUMENT));
        }
        return String.format(UNKNOWN_TOOL, name);
    }

    /**
     * The arguments of one tool call, as plain strings.
     *
     * <p>Both tools take strings only, so an input holding anything else is
     * taken as no arguments at all: a model that sends something else gets a
     * readable refusal rather than a crash. A missing content stays missing,
     * which the write tool refuses.
     *
     * @param input the tool call's input
     * @return its arguments, empty when they were not what this agent offers
     */
    private static Map<String, String> argumentsOf(JsonNode input) {
        Map<String, String> arguments = new HashMap<>();
        if (!input.isObject()) {
            return Map.of();
        }
        Iterator<Map.Entry<String, JsonNode>> fields = input.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!field.getValue().isTextual()) {
                return Map.of();
            }
            arguments.put(field.getKey(), field.getValue().asText());
        }
        return arguments;
    }

    /**
     * The tool calls in one reply, in the order the model made them.
     *
     * @param reply the model's reply
     * @return its tool calls, empty when it is done
     */
    private static List<JsonNode> toolCalls(JsonNode reply) {
        List<JsonNode> calls = new ArrayList<>();
        reply.path(CONTENT_FIELD).forEach(block -> {
            if (TOOL_USE_TYPE.equals(block.path(TYPE_FIELD).asText())) {
                calls.add(block);
            }
        });
        return calls;
    }

    /**
     * Adds whatever the model said in prose to the transcript, so the prompt
     * log keeps its reasoning and not only the file operations.
     *
     * @param reply the model's reply
     * @param transcript the running log of what happened
     */
    private static void recordText(JsonNode reply, List<String> transcript) {
        reply.path(CONTENT_FIELD).forEach(block -> {
            if (TEXT_TYPE.equals(block.path(TYPE_FIELD).asText())) {
                transcript.add(block.path(TEXT_FIELD).asText());
            }
        });
    }

    /**
     * As much of a failing body as is worth putting in a message.
     *
     * @param body what came back
     * @return that body, shortened
     */
    private static String trimmed(String body) {
        String stripped = body.strip();
        return stripped.length() <= REPORTED_BODY_LIMIT ? stripped : stripped.substring(0, REPORTED_BODY_LIMIT);
    }
}
