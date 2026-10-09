package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every default and every environment override {@link McpConfig} reads, one
 * variable at a time.
 */
final class McpConfigTest {

    @TempDir
    Path workspace;

    private Path javaModule(String name) throws IOException {
        Path module = workspace.resolve(name);
        Files.createDirectories(module.resolve("src/main/java"));
        Files.writeString(module.resolve("src/main/java/A.java"), "class A {}");
        return module;
    }

    @Test
    void aProjectHoldingComponentsIsMountedAsItStands() throws IOException {
        javaModule("backend");
        McpConfig config = new McpConfig(Map.of(), workspace);

        assertEquals(workspace.toAbsolutePath().normalize(), config.repository());
        assertEquals("backend", config.defaultComponent());
    }

    @Test
    void aSingleModuleProjectIsMountedThroughItsParent() throws IOException {
        Path module = javaModule("weather");
        McpConfig config = new McpConfig(Map.of(), module);

        assertEquals(workspace.toAbsolutePath().normalize(), config.repository());
        assertEquals("weather", config.defaultComponent());
    }

    @Test
    void theProfileFollowsTheComponentsSources() throws IOException {
        javaModule("backend");
        Path front = workspace.resolve("front");
        Files.createDirectories(front.resolve("src"));
        Files.writeString(front.resolve("src/app.ts"), "export const a = 1;");
        McpConfig config = new McpConfig(Map.of(), workspace);

        assertEquals("JAVA", config.profileFor("backend"));
        assertEquals("TYPESCRIPT", config.profileFor("front"));
    }

    @Test
    @DisplayName("a bad timeout is reported, not thrown: thrown at startup, a client shows only 'connection closed'")
    void aMalformedTimeoutIsAProblemNotACrash() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_TIMEOUT", "soon"), workspace);

        assertTrue(config.problem().orElse("").contains("SHERIFF_TIMEOUT"), config.problem().toString());
        assertEquals(Duration.ofSeconds(300), config.timeout());
    }

    @Test
    @DisplayName("a timeout of zero or less is a problem too: with it, every analysis timed out at once")
    void aTimeoutBelowOneSecondIsAProblem() {
        assertTrue(new McpConfig(Map.of("SHERIFF_TIMEOUT", "0"), workspace).problem().isPresent());
        assertTrue(new McpConfig(Map.of("SHERIFF_TIMEOUT", "-5"), workspace).problem().isPresent());
        assertTrue(new McpConfig(Map.of("SHERIFF_TIMEOUT", "60"), workspace).problem().isEmpty());
        assertEquals(Duration.ofSeconds(60), new McpConfig(Map.of("SHERIFF_TIMEOUT", "60"), workspace).timeout());
    }

    @Test
    void theCatalogCacheFollowsXdg() {
        McpConfig config = new McpConfig(Map.of("XDG_CACHE_HOME", "/var/cache/u"), workspace);

        assertEquals(Path.of("/var/cache/u/sheriff-mcp/rules_catalog.json"), config.cachedCatalog());
    }

    @Test
    void readsTheRepositoryFromSheriffRepo() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", "/tmp"));

        assertEquals(Path.of("/tmp").toAbsolutePath().normalize(), config.repository());
    }

    @Test
    void defaultsToTheKaiztenImage() {
        McpConfig config = new McpConfig(Map.of());

        assertEquals("kaizten/sheriff:latest", config.image());
    }

    @Test
    void readsTheImageFromSheriffImage() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_IMAGE", "kaizten/sheriff:1.2.3"));

        assertEquals("kaizten/sheriff:1.2.3", config.image());
    }

    @Test
    void defaultsToTheJavaProfileWhenThereIsNothingToTellBy() {
        McpConfig config = new McpConfig(Map.of(), workspace);

        assertEquals("JAVA", config.profileFor(""));
    }

    @Test
    @DisplayName("a declared profile is added to its language's base one, and SHERIFF_PROFILE beats the declaration")
    void theProjectsDeclarationComesBeforeItsLanguage() throws IOException {
        Path backend = Files.createDirectories(workspace.resolve("backend/src"));
        Files.writeString(backend.resolve("A.java"), "class A {}");
        Files.writeString(workspace.resolve("backend/pom.xml"), "<project/>");
        Path front = Files.createDirectories(workspace.resolve("front/src"));
        Files.writeString(front.resolve("app.ts"), "export const a = 1;");
        Files.writeString(workspace.resolve("pom.xml"),
                "<project><properties><sheriff.profile>JAVA_HEXAGONAL</sheriff.profile></properties></project>");

        McpConfig config = new McpConfig(Map.of(), workspace);

        assertEquals("JAVA,JAVA_HEXAGONAL", config.profileFor("backend"));
        assertEquals("JAVA,JAVA_HEXAGONAL", config.profileFor(""));
        assertEquals("TYPESCRIPT", config.profileFor("front"));
        Files.writeString(workspace.resolve("front/.sheriff.properties"), "profile=TYPESCRIPT_HEXAGONAL");
        assertEquals("TYPESCRIPT,TYPESCRIPT_HEXAGONAL", config.profileFor("front"));
        assertEquals("JAVA,JAVA_DDD", new McpConfig(Map.of("SHERIFF_PROFILE", "JAVA_DDD"), workspace).profileFor("front"));
    }

    @Test
    @DisplayName("a directory that is not a Maven module inherits nothing from the pom.xml above it")
    void onlyMavenModulesInheritFromThePom() throws IOException {
        Path gradle = Files.createDirectories(workspace.resolve("service/src"));
        Files.writeString(gradle.resolve("A.java"), "class A {}");
        Files.writeString(workspace.resolve("pom.xml"),
                "<project><properties><sheriff.profile>JAVA_HEXAGONAL</sheriff.profile></properties></project>");

        assertEquals("JAVA", new McpConfig(Map.of(), workspace).profileFor("service"));
    }

    @Test
    @DisplayName("a single-module project's parent holds its siblings, so a declaration there is not its own")
    void aSingleModuleProjectIgnoresItsParent() throws IOException {
        Path project = Files.createDirectories(workspace.resolve("app/src"));
        Files.writeString(project.resolve("A.java"), "class A {}");
        Files.writeString(workspace.resolve(".sheriff.properties"), "profile=JAVA_DDD");

        McpConfig config = new McpConfig(Map.of(), workspace.resolve("app"));

        assertEquals("JAVA", config.profileFor("app"));
    }

    @Test
    void readsTheProfileFromSheriffProfile() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_PROFILE", "JAVA_HEXAGONAL"), workspace);

        assertEquals("JAVA,JAVA_HEXAGONAL", config.profileFor("anything"));
    }

    @Test
    void anEmptyProjectHasNoDefaultComponent() {
        McpConfig config = new McpConfig(Map.of(), workspace);

        assertEquals("", config.defaultComponent());
    }

    @Test
    void readsTheComponentFromSheriffComponent() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_COMPONENT", "backend"));

        assertEquals("backend", config.defaultComponent());
    }

    @Test
    void defaultsToFiveMinutes() {
        McpConfig config = new McpConfig(Map.of());

        assertEquals(Duration.ofSeconds(300), config.timeout());
    }

    @Test
    void readsTheTimeoutFromSheriffTimeout() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_TIMEOUT", "60"));

        assertEquals(Duration.ofSeconds(60), config.timeout());
    }

    @Test
    void defaultsToTheSheriffToolPrefix() {
        McpConfig config = new McpConfig(Map.of());

        assertEquals("sheriff", config.toolPrefix());
    }

    @Test
    void readsTheToolPrefixFromSheriffToolPrefix() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_TOOL_PREFIX", "code_quality"));

        assertEquals("code_quality", config.toolPrefix());
    }

    @Test
    void theServerNameFollowsTheToolPrefixByDefault() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_TOOL_PREFIX", "code_quality"));

        assertEquals("code_quality", config.serverName());
    }

    @Test
    void theServerNameCanBeSetIndependently() {
        McpConfig config = new McpConfig(Map.of(
                "SHERIFF_TOOL_PREFIX", "code_quality",
                "SHERIFF_SERVER_NAME", "standards"));

        assertEquals("standards", config.serverName());
    }

    @Test
    void anExplicitCatalogPathWinsOutright() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_RULES_CATALOG", "/tmp/rules_catalog.json"));

        assertEquals(Path.of("/tmp/rules_catalog.json"), config.rulesCatalog());
    }
}
