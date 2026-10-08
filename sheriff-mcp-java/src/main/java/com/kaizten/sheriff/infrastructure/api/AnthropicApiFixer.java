package com.kaizten.sheriff.infrastructure.api;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 */
public final class AnthropicApiFixer implements CodeFixer {

    private static final String READ_TOOL = "read_file";
    private static final String WRITE_TOOL = "write_file";
    private static final String PATH_ARGUMENT = "path";
    private static final String CONTENT_ARGUMENT = "content";
    private static final String STRING_TYPE = "string";
    private static final String TYPE_KEY = "type";
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

    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final long maxTokens;
    private final int maxToolIterations;
    private final Path repositoryRoot;
    private final PromptLog promptLog;

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
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.model = model;
        this.maxTokens = maxTokens;
        this.maxToolIterations = maxToolIterations;
        this.repositoryRoot = repositoryRoot;
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
        AnthropicClient client = client();
        try {
            return converseWith(client, request, tools, transcript);
        } finally {
            client.close();
        }
    }

    /**
     * A client for this pass, pointed at the configured endpoint.
     *
     * <p>One per pass and closed after it: the client owns connection and
     * thread pools, and a run makes one pass per iteration.
     *
     * @return that client
     */
    private AnthropicClient client() {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey);
        if (!baseUrl.isBlank()) {
            builder = builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * The tool-use loop itself, over a client the caller owns.
     *
     * @param client the client to ask through
     * @param request what to fix
     * @param tools the file operations the model is allowed
     * @param transcript the running log of what happened
     * @return the outcome, failed when a reply is unusable or the turn cap is
     *     reached first
     */
    private FixResult converseWith(
            AnthropicClient client, FixRequest request, FileTools tools, List<String> transcript) {
        List<MessageParam> conversation = new ArrayList<>();
        conversation.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(request.prompt())
                .build());
        for (int turn = 0; turn < maxToolIterations; turn++) {
            Message message = client.messages().create(paramsFor(conversation));
            conversation.add(message.toParam());
            recordText(message, transcript);
            String unusable = unusableReply(message);
            if (!unusable.isEmpty()) {
                transcript.add(unusable);
                return FixResult.failed(unusable);
            }
            List<ToolUseBlock> calls = toolCalls(message);
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
     * @param message the reply that came back
     * @return the reason, or the empty string when the reply is usable
     */
    private String unusableReply(Message message) {
        Optional<StopReason> reason = message.stopReason();
        if (reason.filter(StopReason.MAX_TOKENS::equals).isPresent()) {
            return String.format(TRUNCATED, maxTokens);
        }
        if (reason.filter(StopReason.REFUSAL::equals).isPresent()) {
            return REFUSED;
        }
        return NOTHING_WRONG;
    }

    /**
     * One request to the Messages API, with both file tools offered.
     *
     * @param conversation everything said so far
     * @return the parameters for the next call
     */
    private MessageCreateParams paramsFor(List<MessageParam> conversation) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .addTool(readTool())
                .addTool(writeTool())
                .messages(conversation)
                .build();
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
    private MessageParam resultsFor(List<ToolUseBlock> calls, FileTools tools, List<String> transcript) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            String outcome = execute(call, tools);
            transcript.add(call.name() + OUTCOME_SEPARATOR + outcome);
            results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(outcome)
                    .build()));
        }
        return MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(results)
                .build();
    }

    /**
     * Runs one tool call against the file tools.
     *
     * @param call what the model asked for
     * @param tools the file operations it is allowed
     * @return what to report back to the model, a refusal included
     */
    private String execute(ToolUseBlock call, FileTools tools) {
        Map<String, String> arguments = argumentsOf(call);
        String path = arguments.getOrDefault(PATH_ARGUMENT, MISSING_ARGUMENT);
        if (READ_TOOL.equals(call.name())) {
            return tools.readFile(path);
        }
        if (WRITE_TOOL.equals(call.name())) {
            return tools.writeFile(path, arguments.get(CONTENT_ARGUMENT));
        }
        return String.format(UNKNOWN_TOOL, call.name());
    }

    /**
     * The arguments of one tool call, as plain strings.
     *
     * <p>Both tools take strings only, so converting the whole input in one
     * step is simpler and safer than walking a JSON tree by hand — and a model
     * that sends something else gets a readable refusal rather than a crash.
     *
     * @param call the tool call the model made
     * @return its arguments, empty when they were not what this agent offers
     */
    private static Map<String, String> argumentsOf(ToolUseBlock call) {
        try {
            return call._input().convert(new TypeReference<Map<String, String>>() {
            });
        } catch (RuntimeException exception) {
            return Map.of();
        }
    }

    /**
     * The tool calls in one reply, in the order the model made them.
     *
     * @param message the model's reply
     * @return its tool calls, empty when it is done
     */
    private static List<ToolUseBlock> toolCalls(Message message) {
        List<ToolUseBlock> calls = new ArrayList<>();
        message.content().forEach(block -> block.toolUse().ifPresent(calls::add));
        return calls;
    }

    /**
     * Adds whatever the model said in prose to the transcript, so the prompt
     * log keeps its reasoning and not only the file operations.
     *
     * @param message the model's reply
     * @param transcript the running log of what happened
     */
    private static void recordText(Message message, List<String> transcript) {
        message.content().forEach(block -> block.text().ifPresent(text -> transcript.add(text.text())));
    }

    /**
     * The declaration of the read tool the model is offered.
     *
     * @return the tool, taking a repository-relative path
     */
    private static Tool readTool() {
        return Tool.builder()
                .name(READ_TOOL)
                .description(READ_DESCRIPTION)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty(PATH_ARGUMENT, JsonValue.from(Map.of(TYPE_KEY, STRING_TYPE)))
                                .build())
                        .required(List.of(PATH_ARGUMENT))
                        .build())
                .build();
    }

    /**
     * The declaration of the write tool the model is offered.
     *
     * @return the tool, taking a repository-relative path and the full new
     *     contents of that file
     */
    private static Tool writeTool() {
        return Tool.builder()
                .name(WRITE_TOOL)
                .description(WRITE_DESCRIPTION)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty(PATH_ARGUMENT, JsonValue.from(Map.of(TYPE_KEY, STRING_TYPE)))
                                .putAdditionalProperty(CONTENT_ARGUMENT, JsonValue.from(Map.of(TYPE_KEY, STRING_TYPE)))
                                .build())
                        .required(List.of(PATH_ARGUMENT, CONTENT_ARGUMENT))
                        .build())
                .build();
    }
}
