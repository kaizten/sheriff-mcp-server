package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings page: what it shows, how it changes a setting, and what it
 * refuses to answer.
 */
class SettingsPageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AS_JSON = "application/json";

    @TempDir
    Path folder;

    private Path project;
    private Path userFile;
    private SettingsPage page;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void open() throws IOException {
        project = Files.createDirectories(folder.resolve("project"));
        Path config = Files.createDirectories(folder.resolve("config"));
        userFile = config.resolve("sheriff-mcp/sheriff.properties");
        page = SettingsPage.open(Map.of("XDG_CONFIG_HOME", config.toString(), "APPDATA", config.toString(),
                "SHERIFF_PULL", "never"), project, Duration.ofMinutes(5));
    }

    @AfterEach
    void close() {
        page.close();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(page.address().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String type, String body) throws IOException,
            InterruptedException {
        return http.send(HttpRequest.newBuilder(page.address().resolve(path)).header("Content-Type", type)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private String rawRequest(String request) throws IOException {
        try (Socket socket = new Socket(page.address().getHost(), page.address().getPort())) {
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }

    private static JsonNode find(JsonNode listing, String name) {
        for (JsonNode section : listing.path("sections")) {
            for (JsonNode setting : section.path("settings")) {
                if (setting.path("name").asText().equals(name)) {
                    return setting;
                }
            }
        }
        throw new AssertionError(name + " is not listed");
    }

    @Test
    @DisplayName("the page is served on this machine only, under a token, with headers that keep it to itself")
    void thePageIsServedUnderItsToken() throws Exception {
        URI address = page.address();
        assertEquals("127.0.0.1", address.getHost());
        assertTrue(address.getPath().matches("/[0-9a-f]{48}/"), address.toString());
        assertTrue(page.isOpen());

        HttpResponse<String> served = get("");

        assertEquals(200, served.statusCode());
        assertTrue(served.body().contains("<title>Sheriff settings</title>"));
        assertEquals("text/html; charset=utf-8", served.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("no-store", served.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("DENY", served.headers().firstValue("X-Frame-Options").orElseThrow());
        assertTrue(served.headers().firstValue("Content-Security-Policy").orElseThrow()
                .contains("frame-ancestors 'none'"));
    }

    @Test
    @DisplayName("the settings come under their sections, each with its value, where it comes from and what it "
            + "takes, and the files they are read from")
    void theSettingsAreListed() throws Exception {
        Files.writeString(project.resolve(".sheriff.properties"), "SHERIFF_GATE_TTL=30\nSHERIFF_NOPE=1\n");

        JsonNode listing = JSON.readTree(get("settings").body());

        assertEquals(userFile.toString(), listing.path("userFile").asText());
        assertEquals(project.resolve(".sheriff.properties").toString(), listing.path("projectFile").asText());
        assertEquals("Sheriff", listing.path("sections").path(0).path("title").asText());
        JsonNode pull = find(listing, "SHERIFF_PULL");
        assertEquals("never", pull.path("value").asText());
        assertEquals("the environment", pull.path("source").asText());
        assertEquals("never", pull.path("environment").asText());
        assertEquals("choice", pull.path("kind").asText());
        assertEquals(3, pull.path("choices").size());
        JsonNode ttl = find(listing, "SHERIFF_GATE_TTL");
        assertEquals("30", ttl.path("value").asText());
        assertEquals("30", ttl.path("inProject").asText());
        assertEquals("", ttl.path("yours").asText());
        assertEquals("900", ttl.path("default").asText());
        assertTrue(ttl.path("accepted").asText().startsWith("Takes a whole number of seconds"));
        assertEquals(1, find(listing, "SHERIFF_STOP_MAX_BLOCKS").path("minimum").asInt());
        assertFalse(find(listing, "AI_BACKEND").path("projectMay").asBoolean());
        assertFalse(listing.toString().contains("\"SHERIFF_IMAGE\""), "Sheriff's image is no setting for now");
        assertEquals("Reuse a verdict for", ttl.path("label").asText());
        assertTrue(listing.path("problems").toString().contains("SHERIFF_NOPE is not a setting"));
    }

    @Test
    @DisplayName("with no home in the environment the page still lists them, and names no file of the user's")
    void noUserFile() throws Exception {
        try (SettingsPage bare = SettingsPage.open(Map.of(), project, Duration.ofMinutes(1))) {
            Map<String, Object> listing = bare.listing();
            assertEquals("", listing.get("userFile"));
            assertEquals(List.of(), listing.get("problems"));
        }
    }

    @Test
    @DisplayName("a change goes into the user's file or the project's, checked first, as sheriff_config does")
    void aSettingIsChanged() throws Exception {
        HttpResponse<String> changed = post("settings", AS_JSON,
                "{\"name\":\"SHERIFF_STOP_MAX_STALLS\",\"value\":\"0\",\"project\":false}");
        HttpResponse<String> forProject = post("settings", "application/json; charset=utf-8",
                "{\"name\":\"SHERIFF_GATE_TTL\",\"value\":\"60\",\"project\":true}");
        HttpResponse<String> refused = post("settings", AS_JSON,
                "{\"name\":\"SHERIFF_PULL\",\"value\":\"sometimes\",\"project\":false}");
        HttpResponse<String> userOnly = post("settings", AS_JSON,
                "{\"name\":\"AI_BACKEND\",\"value\":\"local\",\"project\":true}");

        assertEquals(200, changed.statusCode(), changed.body());
        assertTrue(JSON.readTree(changed.body()).path("message").asText().startsWith("SHERIFF_STOP_MAX_STALLS is 0"));
        assertTrue(Files.readString(userFile).contains("\nSHERIFF_STOP_MAX_STALLS=0\n"));
        assertEquals(200, forProject.statusCode(), forProject.body());
        assertEquals("SHERIFF_GATE_TTL=60\n", Files.readString(project.resolve(".sheriff.properties")));
        assertEquals("60", find(JSON.readTree(get("settings").body()), "SHERIFF_GATE_TTL").path("value").asText());
        assertEquals(400, refused.statusCode());
        assertEquals("SHERIFF_PULL takes one of always, check, never, not 'sometimes'.",
                JSON.readTree(refused.body()).path("error").asText());
        assertEquals(400, userOnly.statusCode());
        assertFalse(Files.readString(project.resolve(".sheriff.properties")).contains("AI_BACKEND"));
        assertEquals(200, post("settings", AS_JSON, "{\"name\":\"SHERIFF_STOP_MAX_STALLS\",\"value\":\"\"}")
                .statusCode(), "an empty value puts the default back");
        assertTrue(Files.readString(userFile).contains("\n# SHERIFF_STOP_MAX_STALLS=1\n"));
    }

    @Test
    @DisplayName("a change that is not JSON, not a change, or too long is refused and writes nothing")
    void aBadChangeIsRefused() throws Exception {
        assertEquals(415, post("settings", "application/x-www-form-urlencoded", "name=SHERIFF_GATE_TTL&value=1")
                .statusCode());
        assertEquals(415, http.send(HttpRequest.newBuilder(page.address().resolve("settings"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode(), "with no type at all");
        assertEquals(400, post("settings", AS_JSON, "not json").statusCode());
        assertEquals(400, post("settings", AS_JSON, "").statusCode());
        assertEquals(400, post("settings", AS_JSON, "{\"value\":\"1\"}").statusCode());
        HttpResponse<String> noValue = post("settings", AS_JSON, "{\"name\":\"SHERIFF_GATE_TTL\"}");
        assertEquals(400, noValue.statusCode());
        assertTrue(noValue.body().contains("Give the value to set SHERIFF_GATE_TTL to"));
        assertEquals(413, post("settings", AS_JSON, "x".repeat(70_000)).statusCode());
        assertEquals(405, post("", AS_JSON, "{}").statusCode());
        assertEquals(405, get("close").statusCode());
        assertEquals(405, http.send(HttpRequest.newBuilder(page.address().resolve("settings"))
                .PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode());
        assertEquals(404, get("elsewhere").statusCode());
        assertFalse(Files.exists(project.resolve(".sheriff.properties")));
        assertFalse(Files.exists(userFile));
    }

    @Test
    @DisplayName("without the token, for another host or from another site, nothing is answered")
    void strangersAreRefused() throws Exception {
        URI elsewhere = URI.create("http://127.0.0.1:" + page.address().getPort() + "/settings");
        HttpResponse<String> noToken = http.send(HttpRequest.newBuilder(elsewhere).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String path = page.address().getPath();
        String wrongToken = path.substring(0, path.length() - 2) + (path.charAt(path.length() - 2) == 'a' ? "b/"
                : "a/");
        HttpResponse<String> guessed = http.send(HttpRequest.newBuilder(page.address().resolve(wrongToken
                + "settings")).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> otherSite = http.send(HttpRequest.newBuilder(page.address().resolve("settings"))
                .header("Origin", "https://evil.example").header("Content-Type", AS_JSON)
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"SHERIFF_GATE_TTL\",\"value\":\"1\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        String otherHost = rawRequest("GET " + path + "settings HTTP/1.1\r\nHost: rebound.example:"
                + page.address().getPort() + "\r\nConnection: close\r\n\r\n");
        String noHost = rawRequest("GET " + path + "settings HTTP/1.0\r\n\r\n");

        assertEquals(404, noToken.statusCode());
        assertEquals(404, guessed.statusCode());
        assertEquals(403, otherSite.statusCode());
        assertTrue(otherHost.startsWith("HTTP/1.1 403"), otherHost);
        assertFalse(otherHost.contains("SHERIFF_GATE_TTL"));
        assertTrue(noHost.startsWith("HTTP/1.1 403"), noHost);
        assertFalse(Files.exists(userFile));
        assertEquals(200, http.send(HttpRequest.newBuilder(page.address().resolve("settings"))
                .header("Origin", "http://127.0.0.1:" + page.address().getPort()).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode(), "the page's own origin is answered");
        String byName = rawRequest("GET " + path + "settings HTTP/1.1\r\nHost: localhost:" + page.address().getPort()
                + "\r\nConnection: close\r\n\r\n");
        assertTrue(byName.startsWith("HTTP/1.1 200"), "localhost is this machine too");
    }

    @Test
    @DisplayName("Close closes the page, a second close does nothing, and its time limit unused closes it too")
    void thePageCloses() throws Exception {
        assertEquals(200, post("close", AS_JSON, "{}").statusCode());
        page.awaitClosed();
        assertFalse(page.isOpen());
        page.close();
        assertFalse(page.isOpen());

        try (SettingsPage idle = SettingsPage.open(Map.of(), project, Duration.ofMillis(50))) {
            idle.awaitClosed();
            assertFalse(idle.isOpen());
        }
    }

    @Test
    @DisplayName("a page in use stays open past its time limit, and closes once it has gone that long unused")
    void aPageInUseStaysOpen() throws Exception {
        try (SettingsPage used = SettingsPage.open(Map.of(), project, Duration.ofSeconds(1))) {
            Thread.sleep(600);
            assertEquals(200, http.send(HttpRequest.newBuilder(used.address()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            Thread.sleep(700);
            assertTrue(used.isOpen(), "the check at one second found it used 0.4 s before");
            long waited = System.nanoTime();
            used.awaitClosed();
            assertTrue(System.nanoTime() - waited < Duration.ofSeconds(5).toNanos());
        }
    }

    @Test
    @DisplayName("a jar without the page says which resource is missing")
    void aMissingPageIsSaidSo() {
        IOException missing = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> SettingsPage.pageText("/no-such-page.html"));
        assertEquals("The settings page /no-such-page.html is not in this jar.", missing.getMessage());
    }

    @Test
    @DisplayName("the browser is opened by each system's own program, and one that is not there is said so")
    void theBrowserOpensOnEachSystem() {
        URI address = URI.create("http://127.0.0.1:1/t/");
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", "http://127.0.0.1:1/t/"),
                SettingsPage.browserCommand(address, true, "Windows 11"));
        assertEquals(List.of("open", "http://127.0.0.1:1/t/"), SettingsPage.browserCommand(address, false,
                "Mac OS X"));
        assertEquals(List.of("xdg-open", "http://127.0.0.1:1/t/"), SettingsPage.browserCommand(address, false,
                "Linux"));
        assertFalse(SettingsPage.started(List.of(folder.resolve("no-such-program").toString())));
        assertTrue(SettingsPage.browser() != null, "the real one is only built here: running it opens a browser");
    }

    @Test
    @DisplayName("the command that opens the page names this jar and the Java running it, quoted for each system's "
            + "shells, and the jar's usual name when the code is not in a jar")
    void theCommandThatOpensThePage() {
        Optional<Path> jar = Optional.of(Path.of("/opt/it's here/sheriff-mcp.jar"));
        assertEquals("'/usr/bin/java' -jar '/opt/it'\\''s here/sheriff-mcp.jar' --config-ui",
                SettingsPage.openCommand(jar, Optional.of("/usr/bin/java"), false));
        assertEquals("java -jar '/opt/it'\\''s here/sheriff-mcp.jar' --config-ui",
                SettingsPage.openCommand(jar, Optional.empty(), false));
        assertEquals("'/j' -jar sheriff-mcp.jar --config-ui",
                SettingsPage.openCommand(Optional.of(Path.of("/work/target/classes")), Optional.of("/j"), false));
        assertEquals("java -jar sheriff-mcp.jar --config-ui", SettingsPage.openCommand(Optional.empty(),
                Optional.empty(), false));
        Path quoted = Path.of("/x/it's/sheriff-mcp.jar");
        assertEquals("java -jar " + com.kaizten.sheriff.infrastructure.process.Platform.quotedForAnyWindowsShell(
                quoted.toAbsolutePath().toString()) + " --config-ui",
                SettingsPage.openCommand(Optional.of(quoted), Optional.of("/j"), true),
                "on Windows java is named as it is, and the jar quoted as every Windows shell reads");
        assertTrue(SettingsPage.openCommand().endsWith(" --config-ui"));
    }

    @Test
    @DisplayName("the code's location is the file it names, and nothing when it names no file")
    void theCodesLocation() throws Exception {
        assertEquals(Optional.of(Path.of("/opt/sheriff-mcp.jar")),
                SettingsPage.codeSource(new java.net.URL("file:/opt/sheriff-mcp.jar")));
        assertEquals(Optional.empty(), SettingsPage.codeSource(new java.net.URL("http://example.com/x.jar")),
                "a location that is no file");
        assertEquals(Optional.empty(), SettingsPage.codeSource(new java.net.URL("file:/a b/x.jar")),
                "a location that is not a URI");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    @DisplayName("a program that is there is started and left to run")
    void aProgramIsStarted() {
        assertTrue(SettingsPage.started(List.of("true")));
    }
}
