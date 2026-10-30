package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.util.Optional;

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
    @DisplayName("a threshold comes from the environment, then the project's declaration, then the settings "
            + "files, then the default")
    void thresholdsAreLayered() throws IOException {
        Path module = javaModule("app");
        Path home = Files.createDirectories(workspace.resolve("home"));
        Files.createDirectories(home.resolve(".config/sheriff-mcp"));
        Files.writeString(home.resolve(".config/sheriff-mcp/sheriff.properties"),
                "SHERIFF_COVERAGE=70\nSHERIFF_BRANCHES=60\nSHERIFF_MUTATION=50\n");
        Files.writeString(module.resolve(".sheriff.properties"), "SHERIFF_BRANCHES=65\n");
        Files.writeString(module.resolve("pom.xml"), "<project><properties><sheriff.coverage>90</sheriff.coverage>"
                + "<sheriff.mutation>70</sheriff.mutation></properties></project>");
        McpConfig config = new McpConfig(Map.of("HOME", home.toString(), "SHERIFF_MUTATION", "40"), module);

        assertEquals(90, config.coverageThresholds("app").lines(), "the pom's declaration over the user's file");
        assertEquals(65, config.coverageThresholds("app").branches(), "the project's file over the user's");
        assertEquals(40, config.coverageThresholds("app").mutants(), "the environment over every file");
        assertEquals(70, new McpConfig(Map.of(), module).coverageThresholds("app").mutants(),
                "with no variable, the pom's declaration");
        assertEquals(65, new McpConfig(Map.of(), module).coverageThresholds("app").branches(),
                "the project's file needs no home");
    }

    @Test
    @DisplayName("a timeout comes from the settings, and one that is no whole number of seconds is the default")
    void timeoutsComeFromTheSettings() throws IOException {
        Path module = javaModule("app");
        Files.writeString(module.resolve(".sheriff.properties"), "SHERIFF_TEST_TIMEOUT=42\nSHERIFF_PULL=check\n");
        McpConfig config = new McpConfig(Map.of("SHERIFF_MUTATION_TIMEOUT", "soon"), module);

        assertEquals(Duration.ofSeconds(42), config.testTimeout());
        assertEquals(Duration.ofMinutes(30), config.mutationTimeout());
        assertEquals(Duration.ofMinutes(10), config.coverageTimeout());
        assertEquals("check", config.pullPolicy());
        McpConfig set = new McpConfig(Map.of("SHERIFF_COVERAGE_TIMEOUT", "1", "SHERIFF_MUTATION_TIMEOUT", "7",
                "SHERIFF_TEST_TIMEOUT", "0"), module);
        assertEquals(Duration.ofSeconds(1), set.coverageTimeout(), "one second is a time limit");
        assertEquals(Duration.ofSeconds(7), set.mutationTimeout());
        assertEquals(Duration.ofMinutes(10), set.testTimeout(), "none is not");
    }

    @Test
    @DisplayName("the settings are read once, when they are first asked for, as the tools read them at the start")
    void theSettingsAreReadOnce() throws IOException {
        Path module = javaModule("app");
        McpConfig config = new McpConfig(Map.of(), module);
        Files.writeString(module.resolve(".sheriff.properties"), "SHERIFF_GATE_TTL=30\n");
        assertEquals("30", config.settings().get("SHERIFF_GATE_TTL"));
        Files.writeString(module.resolve(".sheriff.properties"), "SHERIFF_GATE_TTL=60\n");
        assertEquals("30", config.settings().get("SHERIFF_GATE_TTL"));
        assertEquals(Map.of(), config.givenEnvironment());
    }

    @Test
    @DisplayName("a declaration a threshold does not take is passed over, so the next place that sets it decides; "
            + "a share may end in %")
    void aBadDeclarationIsPassedOver() throws IOException {
        Path module = javaModule("app");
        Files.writeString(module.resolve(".sheriff.properties"),
                "coverage=9O\nSHERIFF_COVERAGE=70\nbranches=85%\nmutators=STRONGER, ALL\n");
        McpConfig config = new McpConfig(Map.of(), module);
        assertEquals(70, config.coverageThresholds("app").lines());
        assertEquals(85, config.coverageThresholds("app").branches());
        assertEquals("STRONGER,ALL", config.coverageThresholds("app").mutators());
    }

    @Test
    @DisplayName("a blank environment variable is no threshold: the project's declaration gives it")
    void aBlankVariableIsNoThreshold() throws IOException {
        Path module = javaModule("app");
        Files.writeString(module.resolve("pom.xml"),
                "<project><properties><sheriff.coverage>90</sheriff.coverage></properties></project>");
        assertEquals(90, new McpConfig(Map.of("SHERIFF_COVERAGE", " "), module).coverageThresholds("app").lines());
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
    @DisplayName("what is wrong in the settings is read from the project the server works on and the user's file")
    void theSettingsProblemsAreThoseOfItsProject() throws IOException {
        Path module = javaModule("app");
        Path home = Files.createDirectories(workspace.resolve("home"));
        Map<String, String> environment = Map.of("HOME", home.toString());
        assertEquals(List.of(), new McpConfig(environment, module).settingsProblems(), "nothing wrong, nothing said");
        Files.writeString(module.resolve(".sheriff.properties"), "SHERIFF_COVERAGE=120\n");

        List<String> problems = new McpConfig(environment, module).settingsProblems();

        assertEquals(List.of("In " + module.resolve(".sheriff.properties") + ", SHERIFF_COVERAGE takes a share from 0 "
                + "to 100, not '120'. It is ignored."), problems);
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
    @DisplayName("a declared profile is added to its language's base one, and SHERIFF_PROFILE beats the declaration "
            + "in its own language only")
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
        McpConfig chosen = new McpConfig(Map.of("SHERIFF_PROFILE", "JAVA_DDD"), workspace);
        assertEquals("JAVA,JAVA_DDD", chosen.profileFor("backend"));
        assertEquals("TYPESCRIPT,TYPESCRIPT_HEXAGONAL", chosen.profileFor("front"));
        assertEquals("JAVA,JAVA_DDD", chosen.profileFor(""));
        Path javaOnly = Files.createDirectories(workspace.resolve("java-only/app/src"));
        Files.writeString(javaOnly.resolve("A.java"), "class A {}");
        assertEquals("TYPESCRIPT", new McpConfig(Map.of("SHERIFF_PROFILE", "TYPESCRIPT"),
                workspace.resolve("java-only")).profileFor(""), "with no component named, the one asked for is used");
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

    @Test
    @DisplayName("the rounds of Sheriff's fixers have no limit unless a whole number from one sets one")
    void theRepairRoundsComeFromTheSettings() throws IOException {
        Path module = javaModule("app");
        assertEquals(Integer.MAX_VALUE, new McpConfig(Map.of(), module).repairRounds());
        assertEquals(1, new McpConfig(Map.of("SHERIFF_REPAIR_ROUNDS", "1"), module).repairRounds());
        assertEquals(Integer.MAX_VALUE, new McpConfig(Map.of("SHERIFF_REPAIR_ROUNDS", "0"), module).repairRounds());
        assertEquals(Integer.MAX_VALUE, new McpConfig(Map.of("SHERIFF_REPAIR_ROUNDS", "many"), module).repairRounds());
    }

    @Test
    @DisplayName("sessions are reported beside the tasks only with SHERIFF_REPORT on")
    void sessionReportsGoBesideTheTasks() {
        Path state = workspace.resolve("state");
        assertEquals(Optional.of(state.resolve("sheriff-mcp").resolve("reports")), new McpConfig(Map.of(
                "SHERIFF_REPORT", "1", "XDG_STATE_HOME", state.toString()), workspace).reportsDirectory());
        assertEquals(Optional.empty(), new McpConfig(Map.of("XDG_STATE_HOME", state.toString()), workspace)
                .reportsDirectory());
    }
}
