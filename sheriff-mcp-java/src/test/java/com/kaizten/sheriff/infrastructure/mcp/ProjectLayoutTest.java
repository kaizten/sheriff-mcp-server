package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How the server works a project out by itself: what to mount, which
 * components there are, which profile and which test command each needs.
 */
final class ProjectLayoutTest {

    @TempDir
    Path workspace;

    private Path file(String relative, String content) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("sources at the root make one module, mounted through its parent")
    void aModuleAtTheRootIsItsOwnComponent() throws IOException {
        file("app/src/main/java/A.java", "class A {}");
        ProjectLayout layout = ProjectLayout.detect(workspace.resolve("app"));
        assertEquals(workspace.toAbsolutePath().normalize(), layout.mount());
        assertEquals("app", layout.ownComponent());
        assertEquals(List.of("app"), layout.components());
    }

    @Test
    @DisplayName("Codex runs the server and the hooks where its session was opened: inside src/ is the module")
    void aDirectoryInsideTheSourcesIsItsModule() throws IOException {
        file("weather/pom.xml", "<project/>");
        file("weather/src/main/java/demo/Thermometer.java", "class Thermometer {}");
        file("weather/src/test/java/demo/ThermometerTest.java", "class ThermometerTest {}");
        for (String inside : List.of("weather/src/main/java/demo", "weather/src/test", "weather/src")) {
            ProjectLayout layout = ProjectLayout.detect(workspace.resolve(inside));
            assertEquals(workspace.toAbsolutePath().normalize(), layout.mount(), inside);
            assertEquals("weather", layout.ownComponent(), inside);
        }
    }

    @Test
    @DisplayName("a module kept inside another project's src/ is never climbed out of")
    void aModuleInsideAnotherProjectsSourcesStaysItself() throws IOException {
        file("site/package.json", "{}");
        file("site/src/packages/widget/package.json", "{}");
        file("site/src/packages/widget/src/index.ts", "export const x = 1;");
        Path docs = Files.createDirectories(workspace.resolve("site/src/packages/widget/docs"));
        Path normalized = docs.toAbsolutePath().normalize();
        assertEquals(normalized, ProjectLayout.enclosingModule(normalized));
    }

    @Test
    @DisplayName("a folder called src that holds projects is not a module of the folder above it")
    void aFolderOfProjectsCalledSrcIsNotASourceFolder() throws IOException {
        file("work/src/weather/pom.xml", "<project/>");
        file("work/src/weather/src/main/java/A.java", "class A {}");
        file("work/src/weather/docs/readme.md", "text");
        assertEquals("weather", ProjectLayout.detect(workspace.resolve("work/src/weather")).ownComponent());
        Path docs = workspace.resolve("work/src/weather/docs").toAbsolutePath().normalize();
        assertEquals(docs, ProjectLayout.detect(docs).mount());
    }

    @Test
    @DisplayName("a package called src is not a module, and a module inside another's sources stays itself")
    void onlyAModuleWithABuildFileIsClimbedTo() throws IOException {
        file("weather/pom.xml", "<project/>");
        file("weather/src/main/java/com/src/tools/A.java", "class A {}");
        file("site/package.json", "{}");
        file("site/src/packages/widget/src/index.ts", "export {}");
        assertEquals("weather",
                ProjectLayout.detect(workspace.resolve("weather/src/main/java/com/src/tools")).ownComponent());
        assertEquals("widget", ProjectLayout.detect(workspace.resolve("site/src/packages/widget")).ownComponent());
    }

    @Test
    @DisplayName("an aggregator pom at the root does not make it a module: src/ does")
    void anAggregatorHoldsItsModulesBelowIt() throws IOException {
        file("pom.xml", "<project/>");
        file("api/src/main/java/A.java", "class A {}");
        file("web/src/app.ts", "export const a = 1;");
        file("docs/readme.md", "not code");
        ProjectLayout layout = ProjectLayout.detect(workspace);
        assertEquals(workspace.toAbsolutePath().normalize(), layout.mount());
        assertEquals(List.of("api", "web"), layout.components());
        assertEquals("", layout.defaultComponent());
    }

    @Test
    void theOnlyComponentIsTheDefault() throws IOException {
        file("api/src/main/java/A.java", "class A {}");
        assertEquals("api", ProjectLayout.detect(workspace).defaultComponent());
    }

    @Test
    @DisplayName("dependencies and build output are not the project's sources")
    void dependenciesAreNotCounted() throws IOException {
        file("web/node_modules/lib/index.ts", "export {}");
        file("web/target/Generated.java", "class G {}");
        file("web/.cache/x.java", "class X {}");
        assertEquals(List.of(), ProjectLayout.detect(workspace).components());
    }

    @Test
    void theProfileFollowsTheMajorityLanguage() throws IOException {
        file("svc/src/A.java", "class A {}");
        file("svc/src/B.java", "class B {}");
        file("svc/src/static/c.ts", "export {}");
        assertEquals(Optional.of("JAVA"), ProjectLayout.detect(workspace).profileFor("svc"));
    }

    @Test
    @DisplayName("a .vue file makes a project Vue, and a JavaScript config file does not")
    void vueIsTellByVueFiles() throws IOException {
        file("front/src/App.vue", "<template/>");
        file("front/src/store.ts", "export {}");
        file("front/src/api.ts", "export {}");
        file("tsapp/src/a.ts", "export {}");
        file("tsapp/vite.config.js", "export default {}");
        file("tsapp/eslint.config.js", "export default {}");
        ProjectLayout layout = ProjectLayout.detect(workspace);
        assertEquals(Optional.of("VUEJS"), layout.profileFor("front"));
        assertEquals(Optional.of("TYPESCRIPT"), layout.profileFor("tsapp"));
    }

    @Test
    void aComponentWithoutSourcesHasNoProfile() throws IOException {
        file("docs/readme.md", "text");
        assertEquals(Optional.empty(), ProjectLayout.detect(workspace).profileFor("docs"));
    }

    @Test
    void tellsWhetherAComponentHoldsTheProfilesLanguage() throws IOException {
        file("api/src/A.java", "class A {}");
        ProjectLayout layout = ProjectLayout.detect(workspace);
        assertTrue(layout.holdsSourcesFor("api", "JAVA_HEXAGONAL"));
        assertFalse(layout.holdsSourcesFor("api", "TYPESCRIPT"));
    }

    @Test
    @DisplayName("the verification command follows the build tool the component uses")
    void theTestCommandFollowsTheBuildTool() throws IOException {
        file("maven/pom.xml", "<project/>");
        file("wrapped/pom.xml", "<project/>");
        file("wrapped/mvnw", "#!/bin/sh");
        file("gradle/build.gradle.kts", "");
        file("node/package.json", "{\"scripts\":{\"test\":\"vitest\"}}");
        file("untested/package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        ProjectLayout layout = ProjectLayout.detect(workspace);
        assertEquals("cd 'maven' && mvn -q test", layout.verificationCommand("maven", false));
        assertEquals("cd 'wrapped' && ./mvnw -q test", layout.verificationCommand("wrapped", false));
        assertEquals("cd 'gradle' && gradle test", layout.verificationCommand("gradle", false));
        assertEquals("cd 'node' && npm test", layout.verificationCommand("node", false));
        assertEquals("cd \"maven\" && mvn -q test", layout.verificationCommand("maven", true));
        assertEquals("cd \"wrapped\" && mvnw.cmd -q test", layout.verificationCommand("wrapped", true));
        assertEquals("cd \"node\" && npm test", layout.verificationCommand("node", true));
        assertTrue(layout.verificationCommand("untested").startsWith("echo"),
                "npm test without a test script fails, which the loop would read as a broken build");
    }

    @Test
    @DisplayName("the root and the home directory are not projects, and are not walked looking for one")
    void theRootAndHomeAreNotSearched() {
        assertEquals(List.of(), ProjectLayout.detect(Path.of("/")).components());
        assertEquals(List.of(), ProjectLayout.detect(Path.of(System.getProperty("user.home"))).components());
    }

    @Test
    @DisplayName("Sheriff checks Python too, and a Python project used to read as nothing to analyze")
    void pythonIsALanguage() throws IOException {
        file("tool/src/suma.py", "def suma(a, b):\n    return a + b\n");
        assertEquals(Optional.of("PYTHON"), ProjectLayout.detect(workspace).profileFor("tool"));
    }

    @Test
    @DisplayName("the model's test command runs from anywhere, and shows the result -q hid")
    void theModelsTestCommandIsAbsoluteAndNotQuiet() throws IOException {
        file("app/src/A.java", "class A {}");
        file("app/pom.xml", "<project/>");
        String command = ProjectLayout.detect(workspace).testCommandForModel("app");
        assertTrue(command.contains(workspace.resolve("app").toAbsolutePath().normalize().toString()), command);
        assertFalse(command.contains(" -q"), command);
        assertTrue(ProjectLayout.detect(workspace).verificationCommand("app").contains(" -q"),
                "the server's own run stays quiet: it reads the exit code");
    }
}
