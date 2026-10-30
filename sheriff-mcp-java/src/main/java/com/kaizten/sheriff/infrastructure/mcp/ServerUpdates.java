package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Whether a newer version of these tools is published, and what to do about
 * it, as {@code SHERIFF_UPDATES} says: {@code notify} (the default) has the
 * first answer of a session say so, with the command that updates them;
 * {@code auto} downloads the new jar, checks it against its release's
 * checksum and puts it in place of this one, for the sessions that start
 * after; {@code never} does not look.
 *
 * <p>It looks at most once a {@link #CHECK_EVERY}, from the server's start
 * and in the background, and remembers what it found in the user's cache, as
 * every session of every assistant starts a server. Only a jar installed
 * where the installers put it is replaced: one in a build's {@code target/}
 * is a checkout's, which {@code git pull} and the installer update, so it is
 * only told about. The installer also writes each assistant's configuration,
 * which a new jar alone does not, so even an update made here says how to run
 * it.
 */
public final class ServerUpdates {

    private static final String AUTO = "auto";
    private static final String NEVER = "never";
    private static final String NOTIFY = "notify";
    private static final String UNKNOWN = "unknown";
    private static final String JAR = "sheriff-mcp.jar";
    private static final String CHECKSUM = "sheriff-mcp.jar.sha256";
    private static final String JAR_SUFFIX = ".jar";
    private static final String BUILD_FOLDER = "target";
    private static final String PARTIAL = ".sheriff-mcp.jar.download";
    private static final String CACHE_FILE = "latest-release.properties";
    private static final String CHECKED_KEY = "checked";
    private static final String VERSION_KEY = "version";
    private static final String DIGEST = "SHA-256";
    private static final String WHITESPACE = "\\s+";
    private static final String VERSION_SEPARATOR = "\\.";
    private static final String WHOLE_NUMBER = "\\d+";
    private static final int FIRST = 0;
    private static final int SAME = 0;
    private static final String HOW_TO_UPDATE = " To update them, run on this machine: curl -fsSL "
            + GitHubReleases.REPOSITORY + "/releases/latest/download/install.sh | bash, or, in a clone of the "
            + "repository: git pull && scripts/install.sh. Open sessions use it once restarted.";
    private static final String PUBLISHED = "Sheriff tools %s is published; this is %s. Tell the user." + HOW_TO_UPDATE;
    private static final String UPDATED = "Sheriff tools updated themselves from %s to %s (SHERIFF_UPDATES=auto): "
            + "tell the user that open sessions use it once restarted, and that when a version changes what the "
            + "installer writes for the assistants, the install line does that: curl -fsSL "
            + GitHubReleases.REPOSITORY + "/releases/latest/download/install.sh | bash.";
    private static final String NOT_UPDATED = "Sheriff tools %s is published; this is %s, and updating to it by "
            + "itself failed (%s). Tell the user." + HOW_TO_UPDATE;
    private static final String CHECKSUM_DIFFERS = "the jar downloaded does not match its release's checksum";
    private static final String NO_CHECKSUM = "the release's checksum names no hash";
    /**
     * The setting that chooses what is done.
     */
    public static final String SETTING = "SHERIFF_UPDATES";
    /**
     * How long what was found is trusted before looking again.
     */
    public static final Duration CHECK_EVERY = Duration.ofDays(1);
    private final String mode;
    private final String current;
    private final ReleaseSource releases;
    private final Path cache;
    private final Optional<Path> jar;
    private final Supplier<Instant> clock;
    private volatile String notice;

    /**
     * What looks for a newer version, and what it does when there is one.
     *
     * @param mode {@code auto}, {@code notify} or {@code never}; anything else
     *     is {@code notify}
     * @param current the version running, {@code unknown} when the build did
     *     not record it, which is never compared
     * @param releases where the releases are
     * @param cache the folder that remembers what was found
     * @param jar the jar running, when the code is one
     * @param clock what tells the time
     */
    public ServerUpdates(String mode, String current, ReleaseSource releases, Path cache, Optional<Path> jar,
            Supplier<Instant> clock) {
        this.mode = List.of(AUTO, NEVER).contains(mode) ? mode : NOTIFY;
        this.current = current;
        this.releases = releases;
        this.cache = cache;
        this.jar = jar;
        this.clock = clock;
    }

    /**
     * What looks for updates of the server that runs this jar, as the user's
     * settings say, in the user's cache, against this repository's releases.
     *
     * @param config the server's configuration
     * @param jar the jar running
     * @param releases where the releases are
     * @return that, not yet looking
     */
    public static ServerUpdates forServer(McpConfig config, Path jar, ReleaseSource releases) {
        return new ServerUpdates(config.settings().getOrDefault(SETTING, NOTIFY).strip(), ServerVersion.current(),
                releases, config.cachedCatalog().getParent(), Optional.of(jar), Instant::now);
    }

    /**
     * Looks once, in the background, so the server starts at once.
     *
     * @return this, looking
     */
    public ServerUpdates inBackground() {
        CompletableFuture.runAsync(this::check);
        return this;
    }

    /**
     * What it does when a newer version is published.
     *
     * @return {@code auto}, {@code notify} or {@code never}
     */
    String mode() {
        return mode;
    }

    /**
     * What to tell the user, once something was found.
     *
     * @return the note, or empty when there is nothing to say
     */
    public Optional<String> notice() {
        return Optional.ofNullable(notice);
    }

    /**
     * Looks for a newer version now, and acts as the setting says. Nothing
     * that goes wrong here stops the server: an update is never worth that.
     */
    void check() {
        if (NEVER.equals(mode) || UNKNOWN.equals(current)) {
            return;
        }
        Optional<String> latest = latestRelease().filter(version -> newer(version, current));
        if (latest.isEmpty()) {
            return;
        }
        Optional<Path> installed = jar.filter(ServerUpdates::installed);
        if (!AUTO.equals(mode) || installed.isEmpty()) {
            notice = String.format(PUBLISHED, latest.get(), current);
            return;
        }
        try {
            replace(installed.get(), latest.get());
            notice = String.format(UPDATED, current, latest.get());
        } catch (IOException exception) {
            notice = String.format(NOT_UPDATED, latest.get(), current, exception.getMessage());
        }
    }

    /**
     * The newest release: what the cache remembers while it is younger than
     * {@link #CHECK_EVERY}, else what the releases say, remembered.
     *
     * @return its version, or empty when it cannot be told
     */
    Optional<String> latestRelease() {
        Path file = cache.resolve(CACHE_FILE);
        Optional<String> remembered = remembered(file);
        if (remembered.isPresent()) {
            return remembered;
        }
        try {
            Optional<String> latest = releases.latest();
            latest.ifPresent(version -> remember(file, version));
            return latest;
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * What the cache remembers of the newest release, while it is younger
     * than {@link #CHECK_EVERY}.
     *
     * @param file the cache file
     * @return that release, or empty when the file is missing, old or
     *     cannot be read
     */
    private Optional<String> remembered(Path file) {
        Properties remembered = new Properties();
        try {
            remembered.load(new StringReader(Files.readString(file)));
            Instant checked = Instant.ofEpochSecond(Long.parseLong(remembered.getProperty(CHECKED_KEY)));
            return checked.plus(CHECK_EVERY).isAfter(clock.get())
                    ? Optional.ofNullable(remembered.getProperty(VERSION_KEY)) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Writes down what was found and when, best effort.
     *
     * @param file the cache file
     * @param version the newest release
     * @return {@code true} when it was written
     */
    boolean remember(Path file, String version) {
        Properties found = new Properties();
        found.setProperty(CHECKED_KEY, Long.toString(clock.get().getEpochSecond()));
        found.setProperty(VERSION_KEY, version);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                found.store(writer, null);
            }
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Downloads a release's jar, checks it against the release's checksum, and
     * puts it in place of the running one in one move: a session already
     * running keeps the jar it opened, and the next one starts the new.
     *
     * @param target the jar to replace
     * @param version the release
     * @throws IOException when it cannot be downloaded, does not match its
     *     checksum, or cannot be put in place
     */
    void replace(Path target, String version) throws IOException {
        byte[] downloaded = releases.download(version, JAR);
        String[] fields = new String(releases.download(version, CHECKSUM), StandardCharsets.UTF_8).strip()
                .split(WHITESPACE);
        if (fields[FIRST].isEmpty()) {
            throw new IOException(NO_CHECKSUM);
        }
        if (!fields[FIRST].equalsIgnoreCase(hash(downloaded, DIGEST))) {
            throw new IOException(CHECKSUM_DIFFERS);
        }
        Path partial = target.resolveSibling(PARTIAL);
        Files.write(partial, downloaded);
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Whether a jar is one an installer put where it is, rather than a build's.
     *
     * @param jar the jar
     * @return {@code true} for a jar outside a build's {@code target/}
     */
    static boolean installed(Path jar) {
        Path folder = jar.toAbsolutePath().getParent();
        return jar.toString().endsWith(JAR_SUFFIX) && folder.getFileName() != null
                && !BUILD_FOLDER.equals(folder.getFileName().toString());
    }

    /**
     * Whether one version comes after another, number by number: {@code
     * 0.1.10} after {@code 0.1.9}. A version that is not numbers alone comes
     * after nothing.
     *
     * @param candidate the version that may be newer
     * @param than the one it is compared with
     * @return {@code true} when it is newer
     */
    static boolean newer(String candidate, String than) {
        String[] one = candidate.split(VERSION_SEPARATOR);
        String[] other = than.split(VERSION_SEPARATOR);
        for (String part : one) {
            if (!part.matches(WHOLE_NUMBER)) {
                return false;
            }
        }
        for (int index = FIRST; index < Math.max(one.length, other.length); index++) {
            int compared = Long.compare(number(one, index), number(other, index));
            if (compared != SAME) {
                return compared > SAME;
            }
        }
        return false;
    }

    /**
     * One number of a version, zero past its end or where it is no number.
     *
     * @param parts the version's parts
     * @param index which one
     * @return that number
     */
    private static long number(String[] parts, int index) {
        return index < parts.length && parts[index].matches(WHOLE_NUMBER) ? Long.parseLong(parts[index]) : SAME;
    }

    /**
     * The hash of some bytes, in hexadecimal.
     *
     * @param bytes the bytes
     * @param algorithm the hash, as {@code SHA-256}
     * @return their hash
     * @throws IllegalStateException when this Java has no such hash
     */
    static String hash(byte[] bytes, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
