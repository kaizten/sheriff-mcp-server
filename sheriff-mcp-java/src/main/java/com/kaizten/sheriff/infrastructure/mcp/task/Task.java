package com.kaizten.sheriff.infrastructure.mcp.task;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * One tool call, as the client can ask about it later: an id, the command it
 * ran, its state, and what it answered.
 *
 * <p>Immutable: every change of state is a new value, which is what lets the
 * registry hand one to a reader while the background thread moves on.
 *
 * @param id the short id the client quotes back
 * @param command the tool and the arguments it resolved to
 * @param provenance what it ran with, such as this jar's version and the
 *     digest of Sheriff's image, recorded when it was accepted
 * @param state where the task is
 * @param result what it answered, empty until it finishes
 * @param directory where its record and Sheriff's exported JSON are
 * @param created when it was accepted
 * @param finished when it finished, {@code null} until then
 */
public record Task(
        String id,
        String command,
        Map<String, String> provenance,
        TaskState state,
        String result,
        Path directory,
        Instant created,
        Instant finished) {

    private static final String NO_RESULT = "";
    private static final String ID_FIELD = "id";
    private static final String COMMAND_FIELD = "command";
    private static final String STATE_FIELD = "state";
    private static final String CREATED_FIELD = "created";
    private static final String FINISHED_FIELD = "finished";
    private static final String RESULT_FIELD = "result";

    /**
     * A task just accepted.
     *
     * @param id its id
     * @param command what it runs
     * @param provenance what it runs with
     * @param state {@link TaskState#QUEUED} or {@link TaskState#RUNNING}
     * @param directory where it keeps its files
     * @return that task
     */
    public static Task accepted(String id, String command, Map<String, String> provenance, TaskState state,
            Path directory) {
        return new Task(id, command, Map.copyOf(provenance), state, NO_RESULT, directory, Instant.now(), null);
    }

    /**
     * The same task, started.
     *
     * @return it, running
     */
    public Task running() {
        return new Task(id, command, provenance, TaskState.RUNNING, result, directory, created, null);
    }

    /**
     * The same task, finished.
     *
     * @param state {@link TaskState#COMPLETED} or {@link TaskState#FAILED}
     * @param answer what it answered
     * @return it, finished now
     */
    public Task finishedAs(TaskState state, String answer) {
        return new Task(id, command, provenance, state, answer, directory, created, Instant.now());
    }

    /**
     * The task as its {@code task.json} records it: plain strings, so the
     * file reads the same from any language.
     *
     * @return its fields, in a stable order
     */
    public Map<String, String> asMap() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(ID_FIELD, id);
        fields.put(COMMAND_FIELD, command);
        new TreeMap<>(provenance).forEach(fields::put);
        fields.put(STATE_FIELD, state.wireName());
        fields.put(CREATED_FIELD, created.toString());
        fields.put(FINISHED_FIELD, finished == null ? NO_RESULT : finished.toString());
        fields.put(RESULT_FIELD, result);
        return fields;
    }
}
