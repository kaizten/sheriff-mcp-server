package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
}
