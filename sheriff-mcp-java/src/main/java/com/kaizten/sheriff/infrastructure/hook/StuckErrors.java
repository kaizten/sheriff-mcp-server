package com.kaizten.sheriff.infrastructure.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The errors of a component that stayed after the work done for them, and
 * how many times, so they are left for the end instead of holding up the
 * rest.
 *
 * <p>A false positive of the checker, or an error no edit here can clear,
 * stopped everything after it: the gate refused new code because the
 * component "already had errors", the Stop hook sent the turn back for them
 * again and again, and each answer listed them first. An error is known by its
 * file, its rule and its message, not its line, which an edit above it moves.
 * One listed in an answer and still there at the next has had an attempt; one
 * still there at a stop with nothing changed has had all it will get. After
 * {@link #DEFERRED_AT} attempts it is left for the end, and after
 * {@link #LAST_AT} it is told to the user rather than worked on again. An
 * error that goes away leaves the record, and so does one whose file changes
 * its message.
 *
 * <p>It also keeps the errors Sheriff's own fixers removed. One of those that
 * is back had its repair undone, which a model does when the repair broke
 * something: on PetClinic a fixer raised checkstyle to a version that needs
 * Java 21, the model put it back, and every {@code sheriff_fix} raised it
 * again, five times in one session, as the error was gone after each repair
 * and so never counted as an attempt. Such an error is left for the end at
 * once, and {@code sheriff_fix} withholds it from the fixers.
 *
 * <p>A file per component in the user's folder of the temporary directory,
 * shared by the MCP's answers and the hooks, pruned after a week.
 */
public final class StuckErrors {

    private static final String DIRECTORY = "sheriff-stuck";
    private static final String ATTEMPTS_FIELD = "attempts";
    private static final String REPAIRED_FIELD = "repaired";
    private static final String KEY_SEPARATOR = "|";
    private static final int ONE_MORE = 1;
    private static final int NONE = 0;
    private final Path directory;
    private final ObjectMapper json = new ObjectMapper();
    /**
     * The attempts after which an error is left for the end.
     */
    public static final int DEFERRED_AT = 2;
    /**
     * The attempts after which an error is told to the user rather than
     * worked on again.
     */
    public static final int LAST_AT = 3;

    /**
     * The record in the user's own folder of the temporary directory.
     */
    public StuckErrors() {
        this(TemporaryFolders.forUser(DIRECTORY));
    }

    /**
     * The record in a chosen folder.
     *
     * @param directory where the records go
     */
    public StuckErrors(Path directory) {
        this.directory = directory;
    }

    /**
     * How an error is known across analyses.
     *
     * @param finding the error
     * @return its file, rule and message
     */
    public static String key(SheriffFinding finding) {
        return finding.file() + KEY_SEPARATOR + finding.referenceCode() + KEY_SEPARATOR + finding.description();
    }

    /**
     * How every error of a list is known.
     *
     * @param findings the errors
     * @return their keys
     */
    public static Set<String> keys(Collection<SheriffFinding> findings) {
        return findings.stream().map(StuckErrors::key).collect(Collectors.toSet());
    }

    /**
     * Counts an attempt for each error an answer listed that is still there,
     * and forgets the errors that are gone.
     *
     * @param component the component's directory
     * @param shown the errors the last answer listed
     * @param present the errors there now
     */
    public void attempted(Path component, Set<String> shown, Set<String> present) {
        JsonNode record = read(component);
        Map<String, Integer> attempts = new TreeMap<>(attemptsIn(record));
        attempts.keySet().retainAll(present);
        for (String key : shown) {
            if (present.contains(key)) {
                attempts.merge(key, ONE_MORE, Integer::sum);
            }
        }
        write(component, attempts, repairedIn(record));
    }

    /**
     * Leaves for the end every error still there at a stop with nothing
     * changed since the last one: the work on them is over.
     *
     * @param component the component's directory
     * @param present the errors there now
     */
    public void stalled(Path component, Set<String> present) {
        JsonNode record = read(component);
        Map<String, Integer> attempts = new TreeMap<>(attemptsIn(record));
        attempts.keySet().retainAll(present);
        present.forEach(key -> attempts.merge(key, DEFERRED_AT, Math::max));
        write(component, attempts, repairedIn(record));
    }

    /**
     * Remembers errors Sheriff's own fixers removed, so that one back later
     * is known as a repair that was undone.
     *
     * @param component the component's directory
     * @param keys the errors a repair removed
     */
    public void repaired(Path component, Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        JsonNode record = read(component);
        Set<String> repaired = new TreeSet<>(repairedIn(record));
        repaired.addAll(keys);
        write(component, attemptsIn(record), repaired);
    }

    /**
     * The errors Sheriff's fixers removed before, whatever is there now:
     * those a repair must not make again.
     *
     * @param component the component's directory
     * @return their keys
     */
    public Set<String> repairedBefore(Path component) {
        return repairedIn(read(component));
    }

    /**
     * The errors there now that Sheriff's fixers removed before, so their
     * repair was undone, left for the end from now on.
     *
     * @param component the component's directory
     * @param present the errors there now
     * @return those of them whose repair was undone
     */
    public Set<String> undone(Path component, Set<String> present) {
        JsonNode record = read(component);
        Set<String> back = new TreeSet<>(repairedIn(record));
        back.retainAll(present);
        if (!back.isEmpty()) {
            Map<String, Integer> attempts = new TreeMap<>(attemptsIn(record));
            back.forEach(key -> attempts.merge(key, DEFERRED_AT, Math::max));
            write(component, attempts, repairedIn(record));
        }
        return back;
    }

    /**
     * The errors left for the end: those that stayed through the work on
     * them, and those whose repair by Sheriff was undone.
     *
     * @param component the component's directory
     * @return their keys
     */
    public Set<String> deferred(Path component) {
        JsonNode record = read(component);
        Set<String> deferred = new TreeSet<>(reaching(attemptsIn(record), DEFERRED_AT));
        deferred.addAll(repairedIn(record));
        return deferred;
    }

    /**
     * The errors to tell the user about rather than work on again.
     *
     * @param component the component's directory
     * @return their keys
     */
    public Set<String> exhausted(Path component) {
        return reaching(attemptsIn(read(component)), LAST_AT);
    }

    /**
     * The errors that have had at least a number of attempts.
     *
     * @param attempts each error's attempts
     * @param least the number
     * @return their keys
     */
    private static Set<String> reaching(Map<String, Integer> attempts, int least) {
        return attempts.entrySet().stream().filter(entry -> entry.getValue() >= least)
                .map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /**
     * What is on record for a component.
     *
     * @param component the component's directory
     * @return the record, an empty object when there is none, a missing node
     *     when the file is empty
     */
    private JsonNode read(Path component) {
        try {
            return json.readTree(Files.readString(fileFor(component)));
        } catch (IOException exception) {
            return json.createObjectNode();
        }
    }

    /**
     * Each error's attempts in a record, which an earlier version wrote as
     * the whole object.
     *
     * @param record the record
     * @return the attempts
     */
    private static Map<String, Integer> attemptsIn(JsonNode record) {
        JsonNode attempts = record.has(ATTEMPTS_FIELD) ? record.path(ATTEMPTS_FIELD) : record;
        Map<String, Integer> counts = new TreeMap<>();
        attempts.properties().forEach(entry -> {
            if (entry.getValue().isNumber()) {
                counts.put(entry.getKey(), entry.getValue().asInt(NONE));
            }
        });
        return counts;
    }

    /**
     * The errors Sheriff's fixers removed, in a record.
     *
     * @param record the record
     * @return their keys
     */
    private static Set<String> repairedIn(JsonNode record) {
        Set<String> repaired = new TreeSet<>();
        record.path(REPAIRED_FIELD).forEach(key -> repaired.add(key.asText()));
        return repaired;
    }

    /**
     * Writes a component's record. Best effort: one that cannot be written
     * only means its errors are worked on as if new.
     *
     * @param component the component's directory
     * @param attempts each error's attempts
     * @param repaired the errors Sheriff's fixers removed
     */
    private void write(Path component, Map<String, Integer> attempts, Set<String> repaired) {
        ObjectNode record = json.createObjectNode();
        attempts.forEach(record.putObject(ATTEMPTS_FIELD)::put);
        ArrayNode removed = record.putArray(REPAIRED_FIELD);
        repaired.forEach(removed::add);
        try {
            Files.createDirectories(directory);
            TemporaryFolders.prune(directory, TemporaryFolders.MARKER_LIFETIME);
            Files.writeString(fileFor(component), record.toString());
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * The record file of a component.
     *
     * @param component the component's directory
     * @return its path, named after a name-based UUID of the directory
     */
    private Path fileFor(Path component) {
        return directory.resolve(UUID.nameUUIDFromBytes(component.toAbsolutePath().normalize().toString()
                .getBytes(StandardCharsets.UTF_8)).toString());
    }
}
