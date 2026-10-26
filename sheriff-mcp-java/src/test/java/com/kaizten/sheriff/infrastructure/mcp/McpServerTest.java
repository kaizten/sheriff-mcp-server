package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the entry point decides by itself: that standard output carries the
 * protocol and nothing else, and what the agent's command line runs on when
 * it is not told.
 */
final class McpServerTest {

    @TempDir
    Path workspace;

    private Path module() throws IOException {
        Path module = Files.createDirectories(workspace.resolve("weather/src/main/java/demo"));
        Files.writeString(workspace.resolve("weather/pom.xml"), "<project/>");
        Files.writeString(module.resolve("Thermometer.java"), "class Thermometer {}");
        return workspace.resolve("weather");
    }

    @Test
    @DisplayName("the command the hooks suggest failed from the project: the agent mounted the jar's own folder")
    void theAgentWorksOutWhatToRunOnFromWhereItIsRun() throws IOException {
        Path module = module();
        for (Path from : new Path[] {module, module.resolve("src/main/java/demo")}) {
            Map<String, String> environment = McpServer.agentEnvironment(Map.of(), from);
            assertEquals(workspace.toAbsolutePath().normalize().toString(), environment.get("TARGET_REPO"));
            assertEquals("weather", environment.get("SHERIFF_COMPONENT"));
        }
    }

    @Test
    @DisplayName("the command the Stop hook suggests names the mount and the component, and repaired TypeScript as Java")
    void theProfileAndTheTestsComeFromTheComponent() throws IOException {
        Path web = Files.createDirectories(workspace.resolve("web/src"));
        Files.writeString(web.resolve("basket.ts"), "export class Basket {}");
        Files.writeString(workspace.resolve("web/package.json"), "{\"scripts\":{\"test\":\"vitest\"}}");
        Map<String, String> environment = McpServer.agentEnvironment(
                Map.of("TARGET_REPO", workspace.toString(), "SHERIFF_COMPONENT", "web"), workspace.resolve("web"));
        assertEquals("TYPESCRIPT", environment.get("SHERIFF_TEST_TYPE"));
        assertTrue(environment.get("VERIFICATION_TEST_CMD").contains("npm"), environment.get("VERIFICATION_TEST_CMD"));
        assertEquals("web", environment.get("SHERIFF_COMPONENT"));
    }

    @Test
    void whatTheUserSetIsLeftAlone() throws IOException {
        Map<String, String> set = Map.of("TARGET_REPO", workspace.toString(), "SHERIFF_COMPONENT", "weather",
                "SHERIFF_TEST_TYPE", "JAVA_HEXAGONAL", "VERIFICATION_TEST_CMD", "make check");
        module();
        Map<String, String> environment = McpServer.agentEnvironment(set, workspace);
        for (Map.Entry<String, String> entry : set.entrySet()) {
            assertEquals(entry.getValue(), environment.get(entry.getKey()), entry.getKey());
        }
    }

    @Test
    @DisplayName("run outside any project, the command line used to mount the whole home directory")
    void outsideAProjectNothingIsFilledIn() {
        assertEquals(Map.of(), McpServer.agentEnvironment(Map.of(), workspace));
    }

    @Test
    void progressPrintedAnywhereInTheProcessLandsOnStandardErrorInstead() {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            PrintStream protocol = McpServer.claimStandardOutput();
            System.out.println("--- Iteration 1 ---");
            protocol.println("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}" + System.lineSeparator(),
                out.toString(StandardCharsets.UTF_8));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Iteration 1"));
    }

    @Test
    @DisplayName("read by a program, the protocol and the progress are UTF-8 even where Java writes a pipe in Cp1252")
    void bothStreamsAreUtf8ForAProgram() {
        List<String> written = writtenThroughClaimedStreams(false, Charset.forName("windows-1252"));

        assertEquals(List.of("A\u00f1o.java" + System.lineSeparator(), "Jes\u00fas" + System.lineSeparator()),
                written);
    }

    @Test
    @DisplayName("on a terminal Java's own encoding stays, as UTF-8 showed A\u00f1o as A\u251c\u2592o in code page 850")
    void bothStreamsKeepTheConsolesEncodingOnATerminal() {
        Charset console = Charset.forName("IBM850");
        List<String> written = writtenThroughClaimedStreams(true, console);

        assertEquals(List.of(new String(("A\u00f1o.java" + System.lineSeparator()).getBytes(console),
                StandardCharsets.UTF_8), new String(("Jes\u00fas" + System.lineSeparator()).getBytes(console),
                StandardCharsets.UTF_8)), written);
    }

    @Test
    @DisplayName("a test, whose streams are pipes, is never on a terminal")
    void aPipeIsNotATerminal() {
        assertFalse(McpServer.onTerminal());
    }

    private static List<String> writtenThroughClaimedStreams(boolean terminal, Charset platform) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, platform));
        System.setErr(new PrintStream(err, true, platform));
        try {
            PrintStream protocol = McpServer.claimStandardOutput(terminal);
            protocol.println("A\u00f1o.java");
            System.err.println("Jes\u00fas");
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        return List.of(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("an installed hook asks for fail-fast as an option, which every platform's shell passes the same")
    void aHookCommandWithFailFastRunsWithTheVariable() {
        Map<String, String> environment = Map.of("HOME", "/home/me");

        assertEquals("1", McpServer.hookEnvironment(environment, List.of("--hook-stop", "--fail-fast"))
                .get("SHERIFF_FAIL_FAST"));
        assertEquals(environment, McpServer.hookEnvironment(environment, List.of("--hook-stop")));
    }

    @Test
    @DisplayName("a mistyped option is refused: it used to start the server, which waited or ended doing nothing")
    void aMistypedOptionIsNamed() {
        assertEquals(Optional.of("--instal-hooks"), McpServer.unknownOption(List.of("--instal-hooks", "--user")));
        assertEquals(Optional.of("-x"), McpServer.unknownOption(List.of("-x")));
        assertEquals(Optional.of("--profile=JAVA"), McpServer.unknownOption(List.of("--check", "--profile=JAVA")));
    }

    @Test
    @DisplayName("every option the jar documents, and the values that follow them, are accepted")
    void theDocumentedOptionsAreKnown() {
        List<List<String>> commandLines = List.of(List.of(),
                List.of("--check", "--component", "app", "--profile", "JAVA"),
                List.of("--call", "sheriff_fix", "component=app", "verify=true"),
                List.of("--hook-stop", "--fail-fast"), List.of("--install-hooks", "--user", "--fail-fast"),
                List.of("--uninstall-hooks", "--user"), List.of("--install-codex", "--no-hooks", "--pull-always"),
                List.of("--uninstall-codex"), List.of("--install-antigravity", "--pull-always"),
                List.of("--uninstall-antigravity"), List.of("--tools"), List.of("--help"), List.of("-h"),
                List.of("--version"), List.of("--instructions"), List.of("--hook-gate"), List.of("--hook-turn"));

        for (List<String> commandLine : commandLines) {
            assertEquals(Optional.empty(), McpServer.unknownOption(commandLine), commandLine.toString());
        }
    }

    @Test
    @DisplayName("SHERIFF_JAVA is used only when it names a file that can be run")
    void theJavaInstallShCheckedIsUsedWhenItCanRun() throws IOException {
        Path java = Files.writeString(workspace.resolve("java"), "#!/bin/sh\n");
        boolean runnable = java.toFile().setExecutable(true);

        assertEquals(Optional.empty(), McpServer.chosenJava(Map.of()));
        assertEquals(Optional.empty(), McpServer.chosenJava(Map.of("SHERIFF_JAVA", " ")));
        assertEquals(Optional.empty(), McpServer.chosenJava(Map.of("SHERIFF_JAVA",
                workspace.resolve("missing").toString())));
        if (runnable && Files.isExecutable(java)) {
            assertEquals(Optional.of(java), McpServer.chosenJava(Map.of("SHERIFF_JAVA", java.toString())));
        }
    }

    @Test
    @DisplayName("every start writes the user's settings at their defaults; --config lists them and nothing else runs")
    void theSettingsAreWrittenAndListed() throws IOException {
        Map<String, String> environment = Map.of("XDG_CONFIG_HOME", workspace.toString(), "APPDATA",
                workspace.toString());
        McpConfig config = new McpConfig(environment, workspace);
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream protocol = new PrintStream(printed, true, StandardCharsets.UTF_8);

        assertEquals(McpServer.NOT_SETTINGS, McpServer.settingsCommand(config, List.of("--tools"), protocol,
                protocol));
        assertTrue(Files.exists(workspace.resolve("sheriff-mcp/sheriff.properties")));
        assertEquals("", printed.toString(StandardCharsets.UTF_8));
        assertEquals(0, McpServer.settingsCommand(config, List.of("--config"), protocol, protocol));
        String listing = printed.toString(StandardCharsets.UTF_8);
        assertTrue(listing.startsWith("Settings, each from the first that sets it"), listing);
        assertTrue(listing.contains("--config NAME=VALUE"), listing);
    }

    @Test
    @DisplayName("--config NAME=VALUE changes one in the user's file, or the project's with --project, and refuses "
            + "a value the setting does not take")
    void theSettingsAreChangedFromTheCommandLine() throws IOException {
        Map<String, String> environment = Map.of("XDG_CONFIG_HOME", workspace.toString(), "APPDATA",
                workspace.toString());
        Files.createDirectories(workspace.resolve("app/src"));
        McpConfig config = new McpConfig(environment, workspace.resolve("app"));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        ByteArrayOutputStream refused = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
        PrintStream errors = new PrintStream(refused, true, StandardCharsets.UTF_8);

        assertEquals(0, McpServer.settingsCommand(config, List.of("--config", "SHERIFF_PROTOTYPE=1"), out, errors));
        assertTrue(Files.readString(workspace.resolve("sheriff-mcp/sheriff.properties"))
                .contains("\nSHERIFF_PROTOTYPE=1"));
        assertEquals(0, McpServer.settingsCommand(config, List.of("--config", "SHERIFF_GATE_TTL=60", "--project"),
                out, errors));
        assertTrue(Files.readString(config.settingsProject().resolve(".sheriff.properties"))
                .contains("SHERIFF_GATE_TTL=60"));
        assertEquals(2, McpServer.settingsCommand(config, List.of("--config", "SHERIFF_PROTOTYPE=yes"), out, errors));
        assertEquals(2, McpServer.settingsCommand(config, List.of("--config", "SHERIFF_PROTOTYPE=0",
                "SHERIFF_GATE_TTL=30"), out, errors));
        assertTrue(refused.toString(StandardCharsets.UTF_8).contains("--config takes at most one NAME=VALUE"));
        assertTrue(Files.readString(workspace.resolve("sheriff-mcp/sheriff.properties"))
                .contains("\nSHERIFF_PROTOTYPE=1"), "nothing is written when two are given");
        java.util.List<Integer> exits = new java.util.ArrayList<>();
        assertFalse(McpServer.answeredSettings(config, List.of("--tools"), out, exits::add));
        assertTrue(McpServer.answeredSettings(config, List.of("--config"), out, exits::add));
        assertTrue(McpServer.answeredSettings(config, List.of("--config", "SHERIFF_PROTOTYPE=2"), out, exits::add));
        assertEquals(List.of(2), exits, "only a refusal ends the process, with 2");
        assertTrue(printed.toString(StandardCharsets.UTF_8).contains("SHERIFF_PROTOTYPE is 1 in "));
        assertTrue(refused.toString(StandardCharsets.UTF_8).contains("SHERIFF_PROTOTYPE"));
    }

    @Test
    @DisplayName("--config-ui prints the page's address, shows it in the browser, and ends once the page is closed")
    void theSettingsPageIsOpenedFromTheCommandLine() throws Exception {
        Map<String, String> environment = Map.of("XDG_CONFIG_HOME", workspace.toString(), "APPDATA",
                workspace.toString());
        Files.createDirectories(workspace.resolve("app/src"));
        McpConfig config = new McpConfig(environment, workspace.resolve("app"));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
        List<java.net.URI> shown = new java.util.ArrayList<>();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();

        List<Integer> exits = new java.util.ArrayList<>();
        boolean answered = McpServer.answeredSettings(config, List.of("--config-ui"), out, exits::add, address -> {
            shown.add(address);
            try {
                http.send(java.net.http.HttpRequest.newBuilder(address.resolve("settings"))
                        .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers
                        .ofString("{\"name\":\"SHERIFF_GATE_TTL\",\"value\":\"60\",\"project\":true}")).build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                http.send(java.net.http.HttpRequest.newBuilder(address.resolve("close"))
                        .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers
                        .ofString("{}")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (IOException | InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
            return true;
        });

        assertTrue(answered);
        assertEquals(List.of(), exits, "a page that closed ends with 0, which exits nothing");
        assertEquals(1, shown.size());
        String text = printed.toString(StandardCharsets.UTF_8);
        assertTrue(text.startsWith("Sheriff's settings are at " + shown.get(0)), text);
        assertTrue(text.contains("after 30 minutes unused"), text);
        assertTrue(Files.readString(config.settingsProject().resolve(".sheriff.properties"))
                .contains("SHERIFF_GATE_TTL=60"));
        assertEquals(java.util.Optional.empty(), McpServer.unknownOption(List.of("--config-ui")));
    }

    @Test
    @DisplayName("--config-ui with a page that closes by itself ends too, and an interrupted wait ends quietly")
    void theSettingsPageEndsWhenItClosesItself() {
        McpConfig config = new McpConfig(Map.of(), workspace);
        PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, McpServer.settingsPage(config, out, address -> false, java.time.Duration.ofMillis(50),
                SettingsPage::open));
        Thread.currentThread().interrupt();
        assertEquals(0, McpServer.settingsPage(config, out, address -> false, java.time.Duration.ofMinutes(1),
                SettingsPage::open));
        assertTrue(Thread.interrupted(), "the interrupt is kept for whoever asked for it");
    }

    @Test
    @DisplayName("--config-ui that cannot open its page says so, names --config instead, and ends with 2")
    void theSettingsPageThatCannotOpen() {
        McpConfig config = new McpConfig(Map.of(), workspace);
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(printed, true, StandardCharsets.UTF_8);
        PrintStream errors = System.err;
        ByteArrayOutputStream said = new ByteArrayOutputStream();
        System.setErr(new PrintStream(said, true, StandardCharsets.UTF_8));
        try {
            assertEquals(2, McpServer.settingsPage(config, out, address -> true, java.time.Duration.ofMinutes(1),
                    (environment, project, idle) -> {
                        throw new IOException("no port");
                    }));
        } finally {
            System.setErr(errors);
        }
        assertEquals("", printed.toString(StandardCharsets.UTF_8));
        assertTrue(said.toString(StandardCharsets.UTF_8).startsWith("The settings page could not be opened (no port); "
                + "java -jar sheriff-mcp.jar --config NAME=VALUE"), said.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the image is made sure of as the settings say: never asks Docker Hub nothing and pulls nothing")
    void theImageFollowsThePullSetting() throws IOException {
        Files.createDirectories(workspace.resolve("app/src"));
        Files.writeString(workspace.resolve("app/src/A.java"), "class A {}");
        Files.writeString(workspace.resolve(".sheriff.properties"), "SHERIFF_PULL=never\n");
        List<List<String>> commands = new java.util.ArrayList<>();
        com.kaizten.sheriff.infrastructure.process.ProcessRunner docker = (command, directory, environment, timeout)
                -> {
            commands.add(command);
            return com.kaizten.sheriff.infrastructure.process.ProcessOutcome.completed(0,
                    "[\"kaizten/sheriff@sha256:aaa\"] \"2026-10-16T12:00:00Z\"", "");
        };

        assertTrue(McpServer.imageFor(new McpConfig(Map.of(), workspace), docker).now());
        assertTrue(commands.stream().noneMatch(command -> command.contains("pull")), commands.toString());
    }
}
