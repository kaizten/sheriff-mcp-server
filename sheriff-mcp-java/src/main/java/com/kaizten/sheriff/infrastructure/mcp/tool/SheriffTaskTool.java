package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.infrastructure.mcp.task.Task;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code sheriff_task}: a task's command, state and result, or the session's
 * tasks when no id is given. How the client asks after
 * {@code sheriff_autofix}, and finds the JSON any call exported.
 */
final class SheriffTaskTool {

    private static final String DESCRIPTION =
            "The state and result of a task, such as a sheriff_autofix running in the background, by "
            + "its id; with no id, the tasks of this session.";
    private static final String TITLE = "Check a Sheriff task";
    private static final ToolAnnotations ANNOTATIONS = ToolAnnotations.readingOnly(TITLE);
    private static final String ID_ARGUMENT = "id";
    private static final String ID_HELP =
            "The task id a previous call answered with. Leave it out to list every task of this session.";
    private static final String NO_SUCH_TASK = "No task with id '%s' in this session. %s";
    private static final String KNOWN_TASKS = "Known: %s.";
    private static final String NO_TASKS = "No tasks yet in this session.";
    private static final String ID_SEPARATOR = ", ";
    private static final String TASK_LINE = "%s  %-9s  %s";
    private static final String TASK_HEADER = "Task %s: %s%nState: %s (started %s%s)%nFolder: %s";
    private static final String TASK_FINISHED = ", finished %s";
    private static final String TASK_RESULT = "%n%nResult:%n%s";
    private static final String NEWLINE = "\n";

    private final ToolContext context;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context the session's tasks
     */
    SheriffTaskTool(ToolContext context) {
        this.context = context;
    }

    /**
     * The tool's definition, bound to its handler.
     *
     * @return that tool
     */
    Tool definition() {
        Map<String, Object> schema = Schemas.objectSchema(Map.of(ID_ARGUMENT, Schemas.stringProperty(ID_HELP)));
        return new Tool(context.taskTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Answers about one task, or lists them all.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        String id = ToolContext.textArgument(arguments, ID_ARGUMENT);
        if (id.isEmpty()) {
            return listing(context.tasks().all());
        }
        Optional<Task> task = context.tasks().find(id);
        if (task.isEmpty()) {
            List<String> known = context.tasks().all().stream().map(Task::id).toList();
            throw new IllegalArgumentException(String.format(NO_SUCH_TASK, id,
                    known.isEmpty() ? NO_TASKS : String.format(KNOWN_TASKS, String.join(ID_SEPARATOR, known))));
        }
        return describe(task.get());
    }

    /**
     * One line per task, newest first.
     *
     * @param all the tasks
     * @return that listing
     */
    private static String listing(List<Task> all) {
        if (all.isEmpty()) {
            return NO_TASKS;
        }
        List<String> lines = new ArrayList<>();
        for (Task task : all) {
            lines.add(String.format(TASK_LINE, task.id(), task.state().wireName(), task.command()));
        }
        return String.join(NEWLINE, lines);
    }

    /**
     * Everything the client can know about one task.
     *
     * @param task the task
     * @return its id, command, state, folder and result
     */
    private static String describe(Task task) {
        String finishedSuffix = task.finished() == null
                ? ToolContext.EMPTY
                : String.format(TASK_FINISHED, task.finished());
        StringBuilder body = new StringBuilder(String.format(TASK_HEADER, task.id(), task.command(),
                task.state().wireName(), task.created(), finishedSuffix, task.directory()));
        if (task.state().finished()) {
            body.append(String.format(TASK_RESULT, task.result()));
        }
        return body.toString();
    }
}
