package com.kaizten.sheriff.infrastructure.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.docker.SheriffContainer;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The last verdict on each component, shared by every process that runs
 * Sheriff for this user: the MCP's {@code sheriff_test} and
 * {@code sheriff_fix} write it, and the hooks read it before running Sheriff
 * themselves.
 *
 * <p>Without the sharing, a turn whose last call was a clean
 * {@code sheriff_test} ended with the Stop hook analyzing the same files
 * again, a whole run of Sheriff for an answer already known: seconds on a
 * small component, minutes through WSL's Docker.
 *
 * <p>An entry is one file per component, profile and way of running (to the
 * end, or stopping at the first error), named after a digest of the three
 * and written whole with a move, in the user's folder of the temporary
 * directory, where the mount locks and the hooks' markers already are. It is
 * served only while two things hold: the fingerprint of the component's
 * sources is the one taken before the analysis it records, and it is younger
 * than {@code SHERIFF_GATE_TTL} seconds (15 minutes by default), which is
 * what covers a new Sheriff image. A verdict that stopped at the first error
 * is never served for a full one; a full one is served for a reader that
 * would stop at the first error, as it says more, not less. A failed
 * analysis is never recorded: when Sheriff cannot run, every caller tries
 * again.
 */
public final class VerdictCache {

    private static final String DIRECTORY = "sheriff-verdicts";
    private static final String ENTRY_SUFFIX = ".json";
    private static final String PARTIAL_SUFFIX = ".partial";
    private static final String TTL_VARIABLE = "SHERIFF_GATE_TTL";
    private static final long DEFAULT_TTL_SECONDS = 900L;
    private static final String COMPONENT_FIELD = "component";
    private static final String PROFILE_FIELD = "profile";
    private static final String FAIL_FAST_FIELD = "fail_fast";
    private static final String CHECKED_AT_FIELD = "checked_at";
    private static final String ERRORS_FIELD = "errors";
    private static final String SAMPLE_FIELD = "sample";
    private static final String FINGERPRINT_FIELD = "fingerprint";
    private static final String FILES_FIELD = "files";
    private static final String KEYS_FIELD = "keys";
    private static final String KEY_SEPARATOR = "\n";
    private static final String SEPARATOR = ":";
    private static final String ENTRY_SEPARATOR = "|";
    private static final String NO_FINGERPRINT = "";
    private static final String SAMPLE_LINE = "  - %s: %s";
    private static final String MORE = "%n  ... and %d more";
    private static final String DIGEST = "SHA-256";
    private static final String DEPENDENCIES_FOLDER = "node_modules";
    private static final String GIT_FOLDER = ".git";
    private static final Set<String> NEVER_SOURCES = Set.of(DEPENDENCIES_FOLDER, GIT_FOLDER);
    private static final int SAMPLE_SIZE = 5;
    private static final int FINGERPRINT_LENGTH = 16;
    private static final int FIRST = 0;
    private static final long MILLISECONDS_PER_SECOND = 1000L;
    private final Path directory;
    private final Duration lifetime;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * The user's own cache, with the lifetime {@code SHERIFF_GATE_TTL} gives.
     */
    public VerdictCache() {
        this(TemporaryFolders.forUser(DIRECTORY), Duration.ofSeconds(ttlSeconds()));
    }

    /**
     * The user's own cache, with a chosen lifetime: the hooks', which read it
     * from the settings rather than the process's environment.
     *
     * @param lifetime how long an entry is served
     * @return that cache
     */
    public static VerdictCache lasting(Duration lifetime) {
        return new VerdictCache(TemporaryFolders.forUser(DIRECTORY), lifetime);
    }

    /**
     * A cache in a chosen folder, with a chosen lifetime.
     *
     * @param directory where the entries go
     * @param lifetime how long an entry is served
     */
    public VerdictCache(Path directory, Duration lifetime) {
        this.directory = directory;
        this.lifetime = lifetime;
    }

    /**
     * The verdict recorded for a component, if it still holds.
     *
     * @param component the component's directory
     * @param profile the profile it is judged under
     * @param failFast whether the reader stops at the first error, in which
     *     case a full verdict serves too, and first, as it says more
     * @param fingerprint the component's fingerprint now
     * @return that verdict, or empty when there is none that holds
     */
    public Optional<GateVerdict> recalled(Path component, String profile, boolean failFast, String fingerprint) {
        Optional<GateVerdict> full = recalledExactly(component, profile, false, fingerprint);
        return full.isPresent() || !failFast ? full : recalledExactly(component, profile, true, fingerprint);
    }

    /**
     * The verdict recorded under exactly these terms, if it still holds.
     *
     * @param component the component's directory
     * @param profile the profile
     * @param failFast whether it stopped at the first error
     * @param fingerprint the component's fingerprint now
     * @return that verdict, or empty
     */
    private Optional<GateVerdict> recalledExactly(Path component, String profile, boolean failFast,
            String fingerprint) {
        if (fingerprint.isEmpty()) {
            return Optional.empty();
        }
        JsonNode entry;
        try {
            entry = json.readTree(Files.readString(entryFor(component, profile, failFast)));
        } catch (IOException exception) {
            return Optional.empty();
        }
        if (entry == null || !entry.isObject() || !entry.has(ERRORS_FIELD)
                || !keyOf(component).equals(entry.path(COMPONENT_FIELD).asText())
                || !profile.equals(entry.path(PROFILE_FIELD).asText())
                || !fingerprint.equals(entry.path(FINGERPRINT_FIELD).asText())) {
            return Optional.empty();
        }
        long age = nowInSeconds() - entry.path(CHECKED_AT_FIELD).asLong();
        if (age < FIRST || age > lifetime.toSeconds()) {
            return Optional.empty();
        }
        Set<String> files = new TreeSet<>();
        entry.path(FILES_FIELD).forEach(file -> files.add(file.asText()));
        Set<String> keys = new TreeSet<>();
        entry.path(KEYS_FIELD).forEach(key -> keys.add(key.asText()));
        return Optional.of(new GateVerdict(entry.path(ERRORS_FIELD).asInt(), entry.path(SAMPLE_FIELD).asText(),
                files, keys));
    }

    /**
     * Records what an analysis found, under the fingerprint taken before it
     * ran. Best effort: a cache that cannot be written only means the next
     * caller runs Sheriff.
     *
     * @param component the component's directory
     * @param profile the profile it was analyzed under
     * @param failFast whether the analysis stopped at the first error
     * @param fingerprint the fingerprint taken before the analysis; empty,
     *     nothing is recorded, as nothing could tell it still holds
     * @param result what the analysis found, which must not be a failure
     * @return the verdict it amounts to
     */
    public GateVerdict remember(Path component, String profile, boolean failFast, String fingerprint,
            AnalysisResult result) {
        GateVerdict verdict = new GateVerdict(result.total(), sampleOf(result), filesOf(result),
                failFast ? Set.of() : StuckErrors.keys(result.errors()));
        if (fingerprint.isEmpty() || result.error()) {
            return verdict;
        }
        ObjectNode entry = json.createObjectNode();
        entry.put(COMPONENT_FIELD, keyOf(component));
        entry.put(PROFILE_FIELD, profile);
        entry.put(FAIL_FAST_FIELD, failFast);
        entry.put(CHECKED_AT_FIELD, nowInSeconds());
        entry.put(FINGERPRINT_FIELD, fingerprint);
        entry.put(ERRORS_FIELD, verdict.errors());
        entry.put(SAMPLE_FIELD, verdict.sample());
        verdict.files().stream().sorted().forEach(entry.putArray(FILES_FIELD)::add);
        verdict.keys().stream().sorted().forEach(entry.putArray(KEYS_FIELD)::add);
        write(entryFor(component, profile, failFast), entry.toString());
        return verdict;
    }

    /**
     * Records what an analysis the MCP ran found, when it is still the
     * component as it is: every source the fingerprint covers is one Sheriff
     * analyzed, with the same content, by the SHA-256 its tracked-files
     * record gives, and nothing changed while that was checked. So a repair,
     * whose last analysis cannot be fingerprinted from outside before it
     * runs, is recorded as safely as a plain analysis, and an edit made while
     * either ran leaves nothing recorded.
     *
     * @param mount the directory Sheriff mounted, which the tracked paths are
     *     relative to
     * @param component the component, as Sheriff names it
     * @param profile the profile it was analyzed under
     * @param result what the analysis found, run to the end
     */
    public void rememberAnalyzed(Path mount, String component, String profile, AnalysisResult result) {
        Path directory = mount.resolve(component);
        if (result.error() || result.trackedFiles().isEmpty()) {
            return;
        }
        Map<String, String> hashes = new HashMap<>();
        result.trackedFiles().forEach(file -> hashes.put(file.path(), file.hash()));
        String fingerprint = fingerprintOf(directory);
        if (fingerprint.isEmpty() || !analyzedAsTheyAre(mount, directory, hashes)
                || !fingerprint.equals(fingerprintOf(directory))) {
            return;
        }
        remember(directory, profile, false, fingerprint, result);
    }

    /**
     * Whether every source of a component is one Sheriff analyzed, as it is
     * now.
     *
     * @param mount the directory Sheriff mounted
     * @param directory the component's directory
     * @param hashes each file Sheriff analyzed, by its mount-relative path,
     *     with the SHA-256 of what it read
     * @return {@code true} when every source matches
     */
    private static boolean analyzedAsTheyAre(Path mount, Path directory, Map<String, String> hashes) {
        try {
            for (Path file : guardedFilesUnder(directory, SheriffContainer.buildOutput(directory))) {
                String analyzed = hashes.get(Platform.slashes(mount.relativize(file)));
                if (analyzed == null || !analyzed.equalsIgnoreCase(contentDigest(file))) {
                    return false;
                }
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * The SHA-256 of a file's content, as Sheriff records it.
     *
     * @param file the file
     * @return its digest, in hexadecimal
     * @throws IOException when it cannot be read
     */
    private static String contentDigest(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(DIGEST).digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Writes an entry whole: to a file of its own, then moved into place, so
     * that a reader in another process never sees half of it.
     *
     * @param target the entry's file
     * @param content what it holds
     */
    private void write(Path target, String content) {
        try {
            Files.createDirectories(directory);
            TemporaryFolders.prune(directory, TemporaryFolders.MARKER_LIFETIME);
            Path partial = Files.createTempFile(directory, target.getFileName().toString(), PARTIAL_SUFFIX);
            Files.writeString(partial, content, StandardCharsets.UTF_8);
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * The file one entry is kept in.
     *
     * @param component the component's directory
     * @param profile the profile
     * @param failFast whether the analysis stopped at the first error
     * @return that file
     */
    Path entryFor(Path component, String profile, boolean failFast) {
        return directory.resolve(digest(keyOf(component) + KEY_SEPARATOR + profile + KEY_SEPARATOR + failFast)
                + ENTRY_SUFFIX);
    }

    /**
     * How a component is named in the cache: its absolute, normalized path.
     *
     * @param component the component's directory
     * @return that name
     */
    private static String keyOf(Path component) {
        return component.toAbsolutePath().normalize().toString();
    }

    /**
     * A cheap signature of the sources Sheriff reads in a component.
     *
     * <p>Any edit changes it, whichever tool made the edit, which is what lets
     * a recorded verdict expire on content instead of only on time. It skips
     * what Sheriff never reads (dependencies, git's own folder, and the build
     * output {@link SheriffContainer} hides from it), so a build does not make
     * a verdict look stale. An unreadable or missing tree returns the empty
     * string, meaning "cannot tell", which must stay distinguishable from
     * "no files".
     *
     * @param component the component's directory
     * @return that signature, or the empty string
     */
    public static String fingerprintOf(Path component) {
        if (!Files.isDirectory(component)) {
            return NO_FINGERPRINT;
        }
        try {
            List<String> entries = new ArrayList<>();
            for (Path file : guardedFilesUnder(component, SheriffContainer.buildOutput(component))) {
                entries.add(file + SEPARATOR + Files.size(file) + SEPARATOR
                        + Files.getLastModifiedTime(file).toMillis());
            }
            entries.sort(String::compareTo);
            return digest(String.join(ENTRY_SEPARATOR, entries)).substring(FIRST, FINGERPRINT_LENGTH);
        } catch (IOException exception) {
            return NO_FINGERPRINT;
        }
    }

    /**
     * Every file of a guarded language under a directory, without going into
     * the folders that never hold a project's own sources.
     *
     * <p>{@code node_modules} alone can hold a hundred thousand files, and
     * walking them only to filter them out afterwards cost every edit and
     * every end of a turn once the hooks were on in every project.
     *
     * @param directory where to look
     * @param skipped the names of the folders of this directory to leave out,
     *     besides those that are never sources
     * @return those files
     * @throws IOException when a directory cannot be listed
     */
    private static List<Path> guardedFilesUnder(Path directory, List<String> skipped) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (!NEVER_SOURCES.contains(name) && !skipped.contains(name)) {
                        found.addAll(guardedFilesUnder(child, List.of()));
                    }
                } else if (Files.isRegularFile(child) && isGuarded(name)) {
                    found.add(child);
                }
            }
        }
        return found;
    }

    /**
     * Whether a file is in a language Sheriff analyzes.
     *
     * @param name the file's name
     * @return {@code true} when it is
     */
    private static boolean isGuarded(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return ComponentGate.GUARDED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }

    /**
     * The first errors of a result, one line each, for a hook's message.
     *
     * @param result what an analysis found
     * @return those lines, and how many more there are
     */
    static String sampleOf(AnalysisResult result) {
        List<SheriffFinding> errors = result.errors();
        List<String> lines = new ArrayList<>();
        for (SheriffFinding finding : errors.subList(FIRST, Math.min(SAMPLE_SIZE, errors.size()))) {
            lines.add(String.format(SAMPLE_LINE, finding.file(), SheriffFinding.brief(finding.description())));
        }
        String sample = String.join(System.lineSeparator(), lines);
        return errors.size() > SAMPLE_SIZE
                ? sample + String.format(MORE, errors.size() - SAMPLE_SIZE)
                : sample;
    }

    /**
     * The files a result found errors in.
     *
     * @param result what an analysis found
     * @return those files, as Sheriff names them
     */
    private static Set<String> filesOf(AnalysisResult result) {
        Set<String> files = new TreeSet<>();
        result.errors().forEach(finding -> files.add(finding.file()));
        return files;
    }

    /**
     * A digest of a text, in hexadecimal.
     *
     * @param text what to digest
     * @return its digest
     */
    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance(DIGEST).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * The time now, in seconds.
     *
     * @return that time
     */
    private static long nowInSeconds() {
        return System.currentTimeMillis() / MILLISECONDS_PER_SECOND;
    }

    /**
     * How long an entry is served, from {@code SHERIFF_GATE_TTL}.
     *
     * @return that many seconds, 900 when the variable is unset or not a
     *     number
     */
    private static long ttlSeconds() {
        String configured = System.getenv(TTL_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            return Long.parseLong(configured.strip());
        } catch (NumberFormatException exception) {
            return DEFAULT_TTL_SECONDS;
        }
    }
}
