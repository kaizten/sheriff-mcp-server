package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.infrastructure.config.Setting;
import com.kaizten.sheriff.infrastructure.config.SettingValue;
import com.kaizten.sheriff.infrastructure.config.Settings;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * A page in the browser to see every setting and change one, for whoever
 * would rather not edit a file or ask the assistant: each setting under its
 * section, with what it does, the values it takes, the value in force and
 * where that comes from, and a field to change it in the user's file or the
 * project's. A change goes through {@link Settings#change}, as
 * {@code sheriff_config} and {@code --config NAME=VALUE} do, so a value the
 * tools could not use is refused here too.
 *
 * <p>It is served on this machine alone ({@code 127.0.0.1}, a port the system
 * picks) and only under a path no other page can guess, a random token, as
 * the settings files can name where code is sent and which image runs: a
 * web page open in the same browser must neither read them nor change them.
 * A request whose {@code Host} is not this machine is refused, so a name
 * that resolves here cannot reach it either, and a change must come as JSON,
 * which a plain form on another site cannot send. It closes when its Done
 * button is pressed, when it has been unused for its time limit, or with the
 * process that started it.
 */
public final class SettingsPage implements AutoCloseable {

    /**
     * How long a page waits unused before it closes.
     */
    public static final Duration IDLE_LIMIT = Duration.ofMinutes(30);

    private static final String PAGE_RESOURCE = "/settings-page.html";
    private static final String NO_PAGE = "The settings page %s is not in this jar.";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String LOCALHOST = "localhost";
    private static final List<String> LOCAL_NAMES = List.of(LOOPBACK, LOCALHOST);
    private static final int ANY_PORT = 0;
    private static final int DEFAULT_BACKLOG = 0;
    private static final String ROOT = "/";
    private static final String ADDRESS = "http://%s:%d/%s/";
    private static final String HOST_AND_PORT = "%s:%d";
    private static final int TOKEN_BYTES = 24;
    private static final String SETTINGS_PATH = "settings";
    private static final String CLOSE_PATH = "close";
    private static final String GET = "GET";
    private static final String POST = "POST";
    private static final String HOST_HEADER = "Host";
    private static final String ORIGIN_HEADER = "Origin";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_MEDIA_TYPE = "application/json";
    private static final String JSON_TYPE = "application/json; charset=utf-8";
    private static final String HTML_TYPE = "text/html; charset=utf-8";
    private static final String CACHE_HEADER = "Cache-Control";
    private static final String NO_STORE = "no-store";
    private static final String FRAME_HEADER = "X-Frame-Options";
    private static final String DENY = "DENY";
    private static final String TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    private static final String NO_SNIFF = "nosniff";
    private static final String REFERRER_HEADER = "Referrer-Policy";
    private static final String NO_REFERRER = "no-referrer";
    private static final String POLICY_HEADER = "Content-Security-Policy";
    private static final String POLICY = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int FORBIDDEN = 403;
    private static final int NOT_FOUND = 404;
    private static final int NOT_ALLOWED = 405;
    private static final int TOO_LARGE = 413;
    private static final int NOT_JSON = 415;
    private static final int MOST_BODY_BYTES = 64 * 1024;
    private static final String ERROR_FIELD = "error";
    private static final String MESSAGE_FIELD = "message";
    private static final String NAME_FIELD = "name";
    private static final String VALUE_FIELD = "value";
    private static final String PROJECT_FIELD = "project";
    private static final String FORBIDDEN_TEXT = "This page answers only on this machine.";
    private static final String NOT_FOUND_TEXT = "There is nothing here.";
    private static final String NOT_ALLOWED_TEXT = "Not with %s.";
    private static final String TOO_LARGE_TEXT = "That is more than a setting.";
    private static final String NOT_JSON_TEXT = "A change is sent as JSON.";
    private static final String BAD_JSON_TEXT = "A change is {\"name\": ..., \"value\": ..., \"project\": false}.";
    private static final String NO_VALUE_TEXT = "Give the value to set %s to, or an empty one to put its default back.";
    private static final String CLOSED_TEXT = "The settings page is closed.";
    private static final String NOTHING = "";
    private static final String USER_FILE_FIELD = "userFile";
    private static final String PROJECT_FILE_FIELD = "projectFile";
    private static final String SECTIONS_FIELD = "sections";
    private static final String TITLE_FIELD = "title";
    private static final String SETTINGS_FIELD = "settings";
    private static final String PROBLEMS_FIELD = "problems";
    private static final String SOURCE_FIELD = "source";
    private static final String DEFAULT_FIELD = "default";
    private static final String HELP_FIELD = "help";
    private static final String ACCEPTED_FIELD = "accepted";
    private static final String KIND_FIELD = "kind";
    private static final String CHOICES_FIELD = "choices";
    private static final String MINIMUM_FIELD = "minimum";
    private static final String PROJECT_MAY_FIELD = "projectMay";
    private static final String PROJECT_IMAGE_FIELD = "projectImage";
    private static final String YOURS_FIELD = "yours";
    private static final String IN_PROJECT_FIELD = "inProject";
    private static final String ENVIRONMENT_FIELD = "environment";
    private static final String THREAD_NAME = "sheriff-settings-page";
    private static final Duration LONGEST_CHECK = Duration.ofSeconds(15);
    private static final long NOT_YET = 0L;
    private static final int START = 0;
    private static final int STOP_AT_ONCE = 0;
    private static final int ONE_MORE = 1;
    private static final String ADDRESS_START = "http://";
    private static final String WINDOWS_OPENER = "rundll32";
    private static final String WINDOWS_HANDLER = "url.dll,FileProtocolHandler";
    private static final String MAC_OPENER = "open";
    private static final String OTHER_OPENER = "xdg-open";
    private static final String OS_NAME = "os.name";
    private static final String MAC = "mac";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Map<String, String> environment;
    private final Path project;
    private final Duration idleLimit;
    private final String token;
    private final HttpServer server;
    private final ScheduledExecutorService timer;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong lastUse = new AtomicLong(System.nanoTime());
    private final byte[] page;

    /**
     * Opens a page on a port of its own.
     *
     * @param environment the environment the tools were started with, as
     *     {@link Settings} reads it
     * @param project the project whose {@code .sheriff.properties} the page
     *     changes
     * @param idleLimit how long it waits unused before it closes
     * @throws IOException when no port can be opened
     */
    private SettingsPage(Map<String, String> environment, Path project, Duration idleLimit) throws IOException {
        this.environment = Map.copyOf(environment);
        this.project = project;
        this.idleLimit = idleLimit;
        this.page = pageText(PAGE_RESOURCE);
        byte[] secret = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(secret);
        this.token = HexFormat.of().formatHex(secret);
        this.server = HttpServer.create(new InetSocketAddress(LOOPBACK, ANY_PORT), DEFAULT_BACKLOG);
        this.timer = Executors.newSingleThreadScheduledExecutor(SettingsPage::daemon);
        this.server.setExecutor(timer);
        this.server.createContext(ROOT, this::handle);
    }

    /**
     * Opens a page for a project's settings and starts serving it.
     *
     * <p>Its threads are daemons, so a page left open never keeps a process
     * alive that has finished everything else.
     *
     * @param environment the environment the tools were started with
     * @param project the project whose file the page changes
     * @param idleLimit how long it waits unused before it closes
     * @return the page, serving
     * @throws IOException when no port can be opened
     */
    public static SettingsPage open(Map<String, String> environment, Path project, Duration idleLimit)
            throws IOException {
        SettingsPage settingsPage = new SettingsPage(environment, project, idleLimit);
        Thread starter = daemon(settingsPage.server::start);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        long period = Math.min(idleLimit.toMillis(), LONGEST_CHECK.toMillis());
        settingsPage.timer.scheduleAtFixedRate(settingsPage::closeWhenIdle, period, period, TimeUnit.MILLISECONDS);
        return settingsPage;
    }

    /**
     * A thread that does not keep the process alive.
     *
     * @param work what it runs
     * @return that thread, not started
     */
    private static Thread daemon(Runnable work) {
        Thread thread = new Thread(work, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    }

    /**
     * The page itself, from this jar.
     *
     * @param resource where it is in the jar
     * @return its bytes
     * @throws IOException when it is not there or cannot be read
     */
    static byte[] pageText(String resource) throws IOException {
        try (InputStream input = SettingsPage.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException(String.format(NO_PAGE, resource));
            }
            return input.readAllBytes();
        }
    }

    /**
     * Where the page is, with the token that opens it.
     *
     * @return its address
     */
    public URI address() {
        return URI.create(String.format(ADDRESS, LOOPBACK, server.getAddress().getPort(), token));
    }

    /**
     * Whether the page still answers.
     *
     * @return {@code true} until it is closed
     */
    public boolean isOpen() {
        return closed.getCount() > NOT_YET;
    }

    /**
     * Waits until the page is closed: by its Done button, by its time
     * limit, or by {@link #close()}.
     *
     * @throws InterruptedException when the wait is interrupted
     */
    public void awaitClosed() throws InterruptedException {
        closed.await();
    }

    /**
     * Stops serving, once; a second call does nothing.
     */
    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        server.stop(STOP_AT_ONCE);
        timer.shutdownNow();
        closed.countDown();
    }

    /**
     * What shows an address in the user's browser, without waiting for the
     * browser: {@code xdg-open}, {@code open} on macOS, the URL handler on
     * Windows. Best effort, as a session over SSH has no browser; the
     * address is always printed too.
     *
     * @return a test of an address that is {@code true} when the program
     *     that opens it started
     */
    public static Predicate<URI> browser() {
        return address -> started(browserCommand(address, Platform.windows(), System.getProperty(OS_NAME, NOTHING)));
    }

    /**
     * The command that shows an address in the browser on a system.
     *
     * @param address the address
     * @param windows whether the system is Windows
     * @param system the system's name, as {@code os.name} gives it
     * @return that command
     */
    static List<String> browserCommand(URI address, boolean windows, String system) {
        if (windows) {
            return List.of(WINDOWS_OPENER, WINDOWS_HANDLER, address.toString());
        }
        String opener = system.toLowerCase(Locale.ROOT).contains(MAC) ? MAC_OPENER : OTHER_OPENER;
        return List.of(opener, address.toString());
    }

    /**
     * Starts a program and leaves it running, its output thrown away.
     *
     * @param command the program and its arguments
     * @return {@code true} when it started
     */
    static boolean started(List<String> command) {
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Closes the page when it has gone unused for its time limit.
     */
    private void closeWhenIdle() {
        if (System.nanoTime() - lastUse.get() >= idleLimit.toNanos()) {
            close();
        }
    }

    /**
     * Answers one request: the page, the settings as JSON, a change, or Done.
     *
     * @param exchange the request and its answer
     * @throws IOException when the answer cannot be written
     */
    private void handle(HttpExchange exchange) throws IOException {
        try {
            lastUse.set(System.nanoTime());
            if (!fromThisMachine(exchange)) {
                answer(exchange, FORBIDDEN, Map.of(ERROR_FIELD, FORBIDDEN_TEXT));
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            String base = ROOT + token + ROOT;
            if (!startsWithToken(path, base)) {
                answer(exchange, NOT_FOUND, Map.of(ERROR_FIELD, NOT_FOUND_TEXT));
                return;
            }
            route(exchange, path.substring(base.length()));
        } finally {
            exchange.close();
        }
    }

    /**
     * Whether a path starts with the page's own, compared in constant time
     * so that its answers say nothing of how much of the token was right.
     *
     * @param path the path asked for
     * @param base the page's own, ending with {@code /}
     * @return {@code true} when it starts with it
     */
    private static boolean startsWithToken(String path, String base) {
        byte[] asked = path.substring(START, Math.min(path.length(), base.length()))
                .getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(asked, base.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a request under the page's own path.
     *
     * @param exchange the request
     * @param rest what follows the token
     * @throws IOException when the answer cannot be written
     */
    private void route(HttpExchange exchange, String rest) throws IOException {
        String method = exchange.getRequestMethod();
        if (rest.isEmpty() && GET.equals(method)) {
            answerPage(exchange);
        } else if (SETTINGS_PATH.equals(rest) && GET.equals(method)) {
            answer(exchange, OK, listing());
        } else if (SETTINGS_PATH.equals(rest) && POST.equals(method)) {
            change(exchange);
        } else if (CLOSE_PATH.equals(rest) && POST.equals(method)) {
            answer(exchange, OK, Map.of(MESSAGE_FIELD, CLOSED_TEXT));
            timer.execute(this::close);
        } else if (rest.isEmpty() || SETTINGS_PATH.equals(rest) || CLOSE_PATH.equals(rest)) {
            answer(exchange, NOT_ALLOWED, Map.of(ERROR_FIELD, String.format(NOT_ALLOWED_TEXT, method)));
        } else {
            answer(exchange, NOT_FOUND, Map.of(ERROR_FIELD, NOT_FOUND_TEXT));
        }
    }

    /**
     * Whether a request names this machine as its host, and, when it says
     * where it comes from, comes from this page: what keeps a site the
     * browser has open, or a name that resolves to this machine, out.
     *
     * @param exchange the request
     * @return {@code true} when it may be answered
     */
    private boolean fromThisMachine(HttpExchange exchange) {
        int port = server.getAddress().getPort();
        List<String> hosts = LOCAL_NAMES.stream().map(name -> String.format(HOST_AND_PORT, name, port)).toList();
        String host = exchange.getRequestHeaders().getFirst(HOST_HEADER);
        String origin = exchange.getRequestHeaders().getFirst(ORIGIN_HEADER);
        boolean sameOrigin = origin == null || hosts.stream().anyMatch(name -> origin.equals(ADDRESS_START + name));
        return host != null && hosts.contains(host) && sameOrigin;
    }

    /**
     * Changes one setting, as a JSON request asks.
     *
     * @param exchange the request
     * @throws IOException when the answer cannot be written
     */
    private void change(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst(CONTENT_TYPE_HEADER);
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith(JSON_MEDIA_TYPE)) {
            answer(exchange, NOT_JSON, Map.of(ERROR_FIELD, NOT_JSON_TEXT));
            return;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MOST_BODY_BYTES + ONE_MORE);
        if (body.length > MOST_BODY_BYTES) {
            answer(exchange, TOO_LARGE, Map.of(ERROR_FIELD, TOO_LARGE_TEXT));
            return;
        }
        JsonNode request;
        try {
            request = JSON.readTree(body);
        } catch (IOException exception) {
            answer(exchange, BAD_REQUEST, Map.of(ERROR_FIELD, BAD_JSON_TEXT));
            return;
        }
        if (!request.path(NAME_FIELD).isTextual()) {
            answer(exchange, BAD_REQUEST, Map.of(ERROR_FIELD, BAD_JSON_TEXT));
            return;
        }
        String name = request.path(NAME_FIELD).asText();
        if (!request.path(VALUE_FIELD).isTextual()) {
            answer(exchange, BAD_REQUEST, Map.of(ERROR_FIELD, String.format(NO_VALUE_TEXT, name)));
            return;
        }
        try {
            answer(exchange, OK, Map.of(MESSAGE_FIELD, Settings.change(environment, project, name,
                    request.path(VALUE_FIELD).asText(), request.path(PROJECT_FIELD).asBoolean())));
        } catch (IllegalArgumentException refused) {
            answer(exchange, BAD_REQUEST, Map.of(ERROR_FIELD, refused.getMessage()));
        }
    }

    /**
     * Every setting as the page shows it, under its section, with the files
     * and what is wrong in them.
     *
     * @return that, to be written as JSON
     */
    Map<String, Object> listing() {
        Map<String, List<Map<String, Object>>> sections = new LinkedHashMap<>();
        for (SettingValue value : Settings.values(environment, project)) {
            sections.computeIfAbsent(value.setting().section(), title -> new ArrayList<>()).add(entry(value));
        }
        List<Map<String, Object>> titled = new ArrayList<>();
        sections.forEach((title, settings) -> titled.add(Map.of(TITLE_FIELD, title, SETTINGS_FIELD, settings)));
        Map<String, Object> listing = new LinkedHashMap<>();
        listing.put(USER_FILE_FIELD, Settings.userFile(environment).map(Path::toString).orElse(NOTHING));
        listing.put(PROJECT_FILE_FIELD, Settings.projectFile(project).toString());
        listing.put(SECTIONS_FIELD, titled);
        listing.put(PROBLEMS_FIELD, Settings.problems(environment, project));
        return listing;
    }

    /**
     * One setting as the page shows it.
     *
     * @param value the setting and its value now
     * @return its fields
     */
    private Map<String, Object> entry(SettingValue value) {
        Setting setting = value.setting();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(NAME_FIELD, setting.name());
        entry.put(VALUE_FIELD, value.value());
        entry.put(SOURCE_FIELD, value.source());
        entry.put(DEFAULT_FIELD, setting.defaultValue());
        entry.put(HELP_FIELD, setting.help());
        entry.put(ACCEPTED_FIELD, setting.accepted());
        entry.put(KIND_FIELD, setting.kind().name().toLowerCase(Locale.ROOT));
        entry.put(CHOICES_FIELD, setting.choices());
        entry.put(MINIMUM_FIELD, setting.minimum());
        entry.put(PROJECT_MAY_FIELD, setting.project());
        entry.put(PROJECT_IMAGE_FIELD, setting.projectImage());
        entry.put(YOURS_FIELD, value.inUserFile());
        entry.put(IN_PROJECT_FIELD, value.inProjectFile());
        entry.put(ENVIRONMENT_FIELD, environment.getOrDefault(setting.name(), NOTHING).strip());
        return entry;
    }

    /**
     * Writes the page itself.
     *
     * @param exchange the request
     * @throws IOException when it cannot be written
     */
    private void answerPage(HttpExchange exchange) throws IOException {
        write(exchange, OK, HTML_TYPE, page);
    }

    /**
     * Writes an answer as JSON.
     *
     * @param exchange the request
     * @param status the HTTP status
     * @param body what to write
     * @throws IOException when it cannot be written
     */
    private static void answer(HttpExchange exchange, int status, Map<String, Object> body) throws IOException {
        write(exchange, status, JSON_TYPE, JSON.writeValueAsBytes(body));
    }

    /**
     * Writes an answer, with the headers that keep it out of caches, frames
     * and other sites.
     *
     * @param exchange the request
     * @param status the HTTP status
     * @param type its content type
     * @param body its bytes
     * @throws IOException when it cannot be written
     */
    private static void write(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, type);
        exchange.getResponseHeaders().set(CACHE_HEADER, NO_STORE);
        exchange.getResponseHeaders().set(FRAME_HEADER, DENY);
        exchange.getResponseHeaders().set(TYPE_OPTIONS_HEADER, NO_SNIFF);
        exchange.getResponseHeaders().set(REFERRER_HEADER, NO_REFERRER);
        exchange.getResponseHeaders().set(POLICY_HEADER, POLICY);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
