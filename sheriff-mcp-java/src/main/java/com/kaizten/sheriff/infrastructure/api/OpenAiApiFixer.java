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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixer that talks to any endpoint speaking OpenAI's chat completions API.
 *
 * <p>One adapter covers two of the three backends on purpose. OpenAI's own
 * API, Ollama, LM Studio, vLLM and llama.cpp's server all answer the same
 * {@code /chat/completions} shape, so the difference between "OpenAI" and "a
 * model on this machine" is a base URL and whether a key is required. Writing
 * two adapters for that would be writing the same adapter twice.
 *
 * <p>It is written against the protocol with the JDK's own HTTP client rather
 * than against a vendor SDK, which is what lets it serve endpoints that no SDK
 * knows about, and what keeps it from adding a dependency to a module that
 * already carries one for Anthropic.
 *
 * <p>The tools are the same two the Anthropic fixer offers, executed by the
 * same {@link FileTools}: a model may read the files it was given and write
 * them back, and nothing else. A local model that cannot emit structured tool
 * calls will simply never call one, and the run ends having changed nothing
 * rather than doing something unintended.
 *
 * <p>A reply that stopped because it ran out of tokens, or because a content
 * filter cut it, is not acted on: its last tool call may be missing the very
 * content it was meant to write. The pass fails instead, saying which.
 */
public final class OpenAiApiFixer implements CodeFixer {

    /**
     * The path appended to the configured base URL.
     */
    private static final String COMPLETIONS_PATH = "/chat/completions";

    /**
     * The header carrying the key, where one is needed.
     */
    private static final String AUTHORIZATION = "Authorization";

    /**
     * The scheme that header uses.
     */
    private static final String BEARER = "Bearer ";

    /**
     * The header naming the body's type.
     */
    private static final String CONTENT_TYPE = "Content-Type";

    /**
     * The body's type.
     */
    private static final String JSON_TYPE = "application/json";

    /**
     * Reported when a key is required and none was configured.
     */
    private static final String MISSING_KEY =
            "No OpenAI API key configured. Set OPENAI_API_KEY, or use AI_BACKEND=local for a model "
                    + "served on this machine, which needs none.";

    /**
     * Reported when the tool loop hits its ceiling.
     */
    private static final String EXHAUSTED = "The model was still calling tools after %d turns.";

    /**
     * Reported when the endpoint answers with something other than success.
     */
    private static final String BAD_STATUS = "%s answered %d: %s";

    /**
     * What a server says when the prompt does not fit its context window.
     */
    private static final String CONTEXT_EXCEEDED = "exceed_context_size";

    /**
     * No hint to add.
     */
    private static final String NO_HINT = "";

    /**
     * How to fix that, for Ollama, whose default window is 4096 tokens and
     * cannot be widened through this API.
     */
    private static final String CONTEXT_HINT =
            "%n  The prompt does not fit the model's context window. With Ollama, make a copy of the "
            + "model with a larger one (16384 or more) and point OPENAI_MODEL at it:%n"
            + "    printf 'FROM %s\\nPARAMETER num_ctx 16384\\n' > Modelfile && ollama create %s-16k -f Modelfile";

    /**
     * Reported when the endpoint could not be reached at all.
     */
    private static final String UNREACHABLE = "Could not reach %s: %s";

    /**
     * Reported when a tool nobody offered is called.
     */
    private static final String UNKNOWN_TOOL = "Unknown tool '%s'.";

    /**
     * Between the lines of the transcript.
     */
    private static final String TRANSCRIPT_SEPARATOR = "\n";

    /**
     * Between a tool's name and what it answered.
     */
    private static final String OUTCOME_SEPARATOR = ": ";

    /**
     * The name of the tool that reads a file.
     */
    private static final String READ_TOOL = "read_file";

    /**
     * The name of the tool that writes one.
     */
    private static final String WRITE_TOOL = "write_file";

    /**
     * What the read tool is for.
     */
    private static final String READ_DESCRIPTION = "Read one of the files this task is allowed to change.";

    /**
     * What the write tool is for.
     */
    private static final String WRITE_DESCRIPTION = "Replace the whole contents of one of those files.";

    /**
     * The argument naming a file.
     */
    private static final String PATH_ARGUMENT = "path";

    /**
     * The argument carrying a file's new contents.
     */
    private static final String CONTENT_ARGUMENT = "content";

    /**
     * The value used when an argument is absent.
     */
    private static final String MISSING_ARGUMENT = "";

    /**
     * How long one request is given before it is abandoned.
     */
    private static final int REQUEST_TIMEOUT_SECONDS = 600;

    /**
     * The lowest HTTP status that is not a success.
     */
    private static final int FIRST_ERROR_STATUS = 300;

    /**
     * How much of a failing body is worth reporting.
     */
    private static final int REPORTED_BODY_LIMIT = 400;

    /**
     * The field a model puts its tool calls in.
     */
    private static final String TOOL_CALLS = "tool_calls";

    /**
     * The field holding the answers.
     */
    private static final String CHOICES = "choices";

    /**
     * The field holding one answer's message.
     */
    private static final String MESSAGE = "message";

    /**
     * A fenced code block in a reply, where models put the calls they write.
     */
    private static final String FENCED_REGEX = "```(?:json)?\\s*(.*?)```";

    /**
     * A fenced code block in a reply, compiled.
     */
    private static final Pattern FENCED_BLOCK = Pattern.compile(OpenAiApiFixer.FENCED_REGEX, Pattern.DOTALL);

    /**
     * The group holding a fenced block's body.
     */
    private static final int FENCED_BODY = 1;

    /**
     * What the model is told about calls it wrote as text.
     */
    private static final String TEXTUAL_CALLS_HEADER =
            "You wrote these tool calls as text instead of calling the tools. They were run for you:%n";

    /**
     * One such call's result.
     */
    private static final String TEXTUAL_CALL_RESULT = "- %s(%s): %s%n";

    /**
     * How the transcript marks a call the model wrote as text.
     */
    private static final String TEXTUAL_CALL_NOTE = "(written as text) ";

    /**
     * The field saying why an answer stopped.
     */
    private static final String FINISH_REASON = "finish_reason";

    /**
     * The finish reason of an answer that ran out of tokens.
     */
    private static final String LENGTH_FINISH = "length";

    /**
     * The finish reason of an answer a content filter stopped.
     */
    private static final String FILTERED_FINISH = "content_filter";

    /**
     * What a pass reports when the reply ran out of tokens.
     */
    private static final String TRUNCATED =
            "The model's reply was cut off at the %d-token limit, so nothing in that reply was applied. "
                    + "Raise OPENAI_MAX_TOKENS if this keeps happening.";

    /**
     * What a pass reports when a content filter stopped the reply.
     */
    private static final String FILTERED =
            "The endpoint's content filter stopped the reply, so nothing in that reply was applied.";

    /**
     * The field naming the model.
     */
    private static final String MODEL_FIELD = "model";

    /**
     * The field capping the answer.
     */
    private static final String MAX_TOKENS_FIELD = "max_tokens";

    /**
     * The field carrying the conversation.
     */
    private static final String MESSAGES_FIELD = "messages";

    /**
     * The field offering the tools.
     */
    private static final String TOOLS_FIELD = "tools";

    /**
     * The field naming a thing's kind.
     */
    private static final String TYPE_FIELD = "type";

    /**
     * The only kind of tool this API has.
     */
    private static final String FUNCTION_TYPE = "function";

    /**
     * The field naming a function.
     */
    private static final String NAME_FIELD = "name";

    /**
     * The field describing one.
     */
    private static final String DESCRIPTION_FIELD = "description";

    /**
     * The field holding a function's parameters.
     */
    private static final String PARAMETERS_FIELD = "parameters";

    /**
     * The field holding a schema's properties.
     */
    private static final String PROPERTIES_FIELD = "properties";

    /**
     * The field listing which of them are required.
     */
    private static final String REQUIRED_FIELD = "required";

    /**
     * The JSON schema type of an object.
     */
    private static final String OBJECT_TYPE = "object";

    /**
     * The JSON schema type of a string.
     */
    private static final String STRING_TYPE = "string";

    /**
     * The field naming who is speaking.
     */
    private static final String ROLE_FIELD = "role";

    /**
     * The person asking.
     */
    private static final String USER_ROLE = "user";

    /**
     * A tool answering.
     */
    private static final String TOOL_ROLE = "tool";

    /**
     * The field carrying what was said.
     */
    private static final String CONTENT_FIELD = "content";

    /**
     * The field tying an answer to the call it answers.
     */
    private static final String TOOL_CALL_ID_FIELD = "tool_call_id";

    /**
     * The field holding a call's identifier.
     */
    private static final String ID_FIELD = "id";

    /**
     * The field holding a call's arguments.
     */
    private static final String ARGUMENTS_FIELD = "arguments";

    /**
     * The key for an OpenAI-compatible endpoint, empty when none is needed.
     */
    private final String apiKey;

    /**
     * The endpoint's base URL, without a trailing slash.
     */
    private final String baseUrl;

    /**
     * The model to ask for.
     */
    private final String model;

    /**
     * The ceiling on one answer.
     */
    private final long maxTokens;

    /**
     * How many tool round trips one fix may take.
     */
    private final int maxToolIterations;

    /**
     * Whether a missing key should stop the run before it starts.
     */
    private final boolean keyRequired;

    /**
     * The repository the tools are scoped to.
     */
    private final Path repositoryRoot;

    /**
     * Where the exchange is recorded.
     */
    private final PromptLog promptLog;

    /**
     * How JSON is read and written.
     */
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the fixer to an endpoint.
     *
     * @param apiKey the key, or empty where the endpoint needs none
     * @param baseUrl the endpoint's base URL
     * @param model the model to ask for
     * @param maxTokens the ceiling on one answer
     * @param maxToolIterations how many tool round trips one fix may take
     * @param keyRequired whether a missing key stops the run
     * @param repositoryRoot the repository the tools are scoped to
     * @param promptLog where to record what was asked
     */
    public OpenAiApiFixer(
            String apiKey,
            String baseUrl,
            String model,
            long maxTokens,
            int maxToolIterations,
            boolean keyRequired,
            Path repositoryRoot,
            PromptLog promptLog) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxToolIterations = maxToolIterations;
        this.keyRequired = keyRequired;
        this.repositoryRoot = repositoryRoot;
        this.promptLog = promptLog;
    }

    /**
     * Runs one fixing conversation and reports what came of it.
     *
     * @param request the prompt, its label, and the files it may touch
     * @return what the conversation produced, or why it could not run
     */
    @Override
    public FixResult fix(FixRequest request) {
        if (keyRequired && apiKey.isBlank()) {
            return FixResult.failed(MISSING_KEY);
        }
        FileTools tools = new FileTools(repositoryRoot, request.allowedFiles());
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
     * @param request what to fix
     * @param tools the file operations the model is allowed
     * @param transcript the running log of what happened
     * @return the outcome, failed when the turn cap is reached first
     */
    private FixResult converse(FixRequest request, FileTools tools, List<String> transcript) {
        ArrayNode conversation = json.createArrayNode();
        conversation.add(userMessage(request.prompt()));
        for (int turn = 0; turn < maxToolIterations; turn++) {
            JsonNode message = ask(conversation);
            conversation.add(message);
            recordText(message, transcript);
            JsonNode calls = message.path(TOOL_CALLS);
            if (!calls.isArray() || calls.isEmpty()) {
                List<JsonNode> written = textualCalls(message.path(CONTENT_FIELD).asText(MISSING_ARGUMENT));
                if (written.isEmpty()) {
                    return FixResult.succeeded(String.join(TRANSCRIPT_SEPARATOR, transcript));
                }
                conversation.add(userMessage(runTextualCalls(written, tools, transcript)));
                continue;
            }
            for (JsonNode call : calls) {
                conversation.add(resultFor(call, tools, transcript));
            }
        }
        return FixResult.failed(String.format(EXHAUSTED, maxToolIterations));
    }

    /**
     * One round trip to the endpoint.
     *
     * @param conversation everything said so far
     * @return the assistant message that came back
     * @throws IllegalStateException when the endpoint fails, or when the reply
     *     stopped short and must not be acted on
     */
    private JsonNode ask(ArrayNode conversation) {
        String endpoint = baseUrl + COMPLETIONS_PATH;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header(CONTENT_TYPE, JSON_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body(conversation), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            builder = builder.header(AUTHORIZATION, BEARER + apiKey);
        }
        HttpResponse<String> response = send(builder.build(), endpoint);
        if (response.statusCode() >= FIRST_ERROR_STATUS) {
            String hint = response.body().contains(CONTEXT_EXCEEDED) ? String.format(CONTEXT_HINT, model, model) : NO_HINT;
            throw new IllegalStateException(
                    String.format(BAD_STATUS, endpoint, response.statusCode(), trimmed(response.body())) + hint);
        }
        JsonNode choice = parse(response.body()).path(CHOICES).path(0);
        String finishReason = choice.path(FINISH_REASON).asText(MISSING_ARGUMENT);
        if (LENGTH_FINISH.equals(finishReason)) {
            throw new IllegalStateException(String.format(TRUNCATED, maxTokens));
        }
        if (FILTERED_FINISH.equals(finishReason)) {
            throw new IllegalStateException(FILTERED);
        }
        return choice.path(MESSAGE);
    }

    /**
     * Sends one request, turning transport failures into a reported reason.
     *
     * @param request the request to send
     * @param endpoint what to name in the failure
     * @return the response
     */
    private HttpResponse<String> send(HttpRequest request, String endpoint) {
        try {
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw new IllegalStateException(String.format(UNREACHABLE, endpoint, reasonOf(exception)), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(String.format(UNREACHABLE, endpoint, reasonOf(exception)), exception);
        }
    }

    /**
     * Why a transport failure happened, in words rather than as "null".
     *
     * <p>A refused connection arrives as a ConnectException carrying no
     * message at all, which is the commonest failure of this whole backend:
     * the endpoint is not running, or -- from inside a container -- localhost
     * is the container's own. Printing "null" there tells the reader nothing
     * about the one thing they need to change.
     *
     * @param exception what went wrong
     * @return its message, or the name of its type when it has none
     */
    private String reasonOf(Exception exception) {
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
        payload.set(MESSAGES_FIELD, conversation);
        ArrayNode tools = payload.putArray(TOOLS_FIELD);
        tools.add(tool(READ_TOOL, READ_DESCRIPTION, false));
        tools.add(tool(WRITE_TOOL, WRITE_DESCRIPTION, true));
        return payload.toString();
    }

    /**
     * One tool declaration in the schema the API expects.
     *
     * @param name what the tool is called
     * @param description what it is for
     * @param withContent whether it also takes the file's new contents
     * @return that declaration
     */
    private ObjectNode tool(String name, String description, boolean withContent) {
        ObjectNode declaration = json.createObjectNode();
        declaration.put(TYPE_FIELD, FUNCTION_TYPE);
        ObjectNode function = declaration.putObject(FUNCTION_TYPE);
        function.put(NAME_FIELD, name);
        function.put(DESCRIPTION_FIELD, description);
        ObjectNode parameters = function.putObject(PARAMETERS_FIELD);
        parameters.put(TYPE_FIELD, OBJECT_TYPE);
        ObjectNode properties = parameters.putObject(PROPERTIES_FIELD);
        properties.putObject(PATH_ARGUMENT).put(TYPE_FIELD, STRING_TYPE);
        ArrayNode required = parameters.putArray(REQUIRED_FIELD);
        required.add(PATH_ARGUMENT);
        if (withContent) {
            properties.putObject(CONTENT_ARGUMENT).put(TYPE_FIELD, STRING_TYPE);
            required.add(CONTENT_ARGUMENT);
        }
        return declaration;
    }

    /**
     * One user message.
     *
     * @param prompt what to say
     * @return that message
     */
    private ObjectNode userMessage(String prompt) {
        ObjectNode message = json.createObjectNode();
        message.put(ROLE_FIELD, USER_ROLE);
        message.put(CONTENT_FIELD, prompt);
        return message;
    }

    /**
     * The answer to one tool call, as the message the API expects back.
     *
     * @param call what the model asked for
     * @param tools the file operations it is allowed
     * @param transcript the running log of what happened
     * @return that message
     */
    private ObjectNode resultFor(JsonNode call, FileTools tools, List<String> transcript) {
        String name = call.path(FUNCTION_TYPE).path(NAME_FIELD).asText();
        String outcome = execute(name, call.path(FUNCTION_TYPE).path(ARGUMENTS_FIELD).asText(), tools);
        transcript.add(name + OUTCOME_SEPARATOR + outcome);
        ObjectNode message = json.createObjectNode();
        message.put(ROLE_FIELD, TOOL_ROLE);
        message.put(TOOL_CALL_ID_FIELD, call.path(ID_FIELD).asText());
        message.put(CONTENT_FIELD, outcome);
        return message;
    }

    /**
     * Carries out one tool call.
     *
     * @param name the tool being called
     * @param arguments its arguments, which this API sends as a JSON string
     * @param tools the file operations available
     * @return what to report back to the model
     */
    private String execute(String name, String arguments, FileTools tools) {
        return execute(name, parse(arguments), tools);
    }

    /**
     * Carries out one tool call whose arguments are already parsed.
     *
     * @param name the tool being called
     * @param parsed its arguments
     * @param tools the file operations available
     * @return what to report back to the model
     */
    private String execute(String name, JsonNode parsed, FileTools tools) {
        String path = parsed.path(PATH_ARGUMENT).asText(MISSING_ARGUMENT);
        if (READ_TOOL.equals(name)) {
            return tools.readFile(path);
        }
        if (WRITE_TOOL.equals(name)) {
            return tools.writeFile(path, contentOf(parsed));
        }
        return String.format(UNKNOWN_TOOL, name);
    }

    /**
     * Tool calls a model wrote into its reply as JSON instead of making them.
     *
     * <p>Small local models do this: {@code qwen2.5-coder:7b} answers with a
     * fenced JSON block naming {@code write_file} and its arguments, which no
     * caller acts on, so the pass changed nothing and the loop parked every
     * file. The intent is unambiguous and the tools are the same ones, with
     * the same refusals, so they are run as if they had been called. Only the
     * two tools this fixer offers are recognised.
     *
     * @param content the text of the reply
     * @return the calls found, each with a {@code name} and {@code arguments}
     */
    private List<JsonNode> textualCalls(String content) {
        List<JsonNode> found = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        Matcher fenced = FENCED_BLOCK.matcher(content);
        while (fenced.find()) {
            candidates.add(fenced.group(FENCED_BODY));
        }
        candidates.add(content);
        for (String candidate : candidates) {
            JsonNode parsed = parse(candidate.strip());
            for (JsonNode entry : parsed.isArray() ? parsed : List.of(parsed)) {
                String name = entry.path(NAME_FIELD).asText(MISSING_ARGUMENT);
                if ((READ_TOOL.equals(name) || WRITE_TOOL.equals(name)) && entry.has(ARGUMENTS_FIELD)) {
                    found.add(entry);
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        return found;
    }

    /**
     * Runs the calls a model wrote as text, and says so back to it.
     *
     * @param calls the calls found in its reply
     * @param tools the file operations available
     * @param transcript the running log of what happened
     * @return the message telling the model what came of them
     */
    private String runTextualCalls(List<JsonNode> calls, FileTools tools, List<String> transcript) {
        StringBuilder answer = new StringBuilder(String.format(TEXTUAL_CALLS_HEADER));
        for (JsonNode call : calls) {
            String name = call.path(NAME_FIELD).asText();
            JsonNode arguments = call.path(ARGUMENTS_FIELD);
            JsonNode parsed = arguments.isTextual() ? parse(arguments.asText()) : arguments;
            String outcome = execute(name, parsed, tools);
            transcript.add(TEXTUAL_CALL_NOTE + name + OUTCOME_SEPARATOR + outcome);
            answer.append(String.format(TEXTUAL_CALL_RESULT, name, parsed.path(PATH_ARGUMENT).asText(), outcome));
        }
        return answer.toString();
    }

    /**
     * The new contents a write call carried, if it carried any.
     *
     * <p>Absent and empty are different answers here: an absent content is a
     * call that never finished, and reading it as the empty string would
     * overwrite a source file with nothing.
     *
     * @param arguments the call's parsed arguments
     * @return the content, or {@code null} when there was none
     */
    private String contentOf(JsonNode arguments) {
        JsonNode content = arguments.path(CONTENT_ARGUMENT);
        return content.isTextual() ? content.asText() : null;
    }

    /**
     * Reads JSON, treating anything unreadable as an empty object rather than
     * as a crash, since a model's arguments are not under our control.
     *
     * @param text the JSON to read
     * @return what it held
     */
    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (IOException exception) {
            return json.createObjectNode();
        }
    }

    /**
     * Notes whatever prose the model wrote alongside its tool calls.
     *
     * @param message the message that came back
     * @param transcript the running log to add to
     */
    private void recordText(JsonNode message, List<String> transcript) {
        String text = message.path(CONTENT_FIELD).asText(MISSING_ARGUMENT);
        if (!text.isBlank()) {
            transcript.add(text);
        }
    }

    /**
     * As much of a failing body as is worth putting in a message.
     *
     * @param body what came back
     * @return that body, shortened
     */
    private String trimmed(String body) {
        String stripped = body.strip();
        return stripped.length() <= REPORTED_BODY_LIMIT ? stripped : stripped.substring(0, REPORTED_BODY_LIMIT);
    }
}
