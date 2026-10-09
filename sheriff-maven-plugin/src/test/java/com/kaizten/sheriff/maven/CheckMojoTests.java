package com.kaizten.sheriff.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the check goal does with an analysis, without needing Docker to
 * produce one. The analysis itself is the agent's and is tested there; what
 * is new here is the decision the build takes from it.
 */
class CheckMojoTests {

    /** A module directory that looks like it has code. */
    private static File moduleWithSources(Path root) throws Exception {
        Path module = root.resolve("componente");
        Files.createDirectories(module.resolve("src"));
        return module.toFile();
    }

    /** A CheckMojo answering with a canned analysis. */
    private static CheckMojo mojoAnswering(File module, AnalysisResult canned) {
        CheckMojo mojo = new CheckMojo() {
            @Override
            protected void ensureImage() {
            }

            @Override
            protected AnalysisResult analyze() {
                return canned;
            }
        };
        mojo.setLog(new SystemStreamLog());
        mojo.basedir = module;
        mojo.profile = "JAVA";
        mojo.image = "kaizten/sheriff:latest";
        mojo.timeoutSeconds = 300;
        return mojo;
    }

    /** One error, so an analysis can be made to fail. */
    private static AnalysisResult oneError() {
        return AnalysisResult.of(List.of(
                new SheriffFinding("A.java", "no JavaDoc", "add one", "R1", SheriffFinding.ERROR, Map.of())));
    }

    @Nested
    @DisplayName("what the build does with the result")
    class Deciding {

        @Test
        @DisplayName("a clean module lets the build continue")
        void cleanModulePasses(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), AnalysisResult.of(List.of()));
            assertDoesNotThrow(mojo::execute);
        }

        @Test
        @DisplayName("errors fail the build, and the message names the count, the profile and the module")
        void errorsFailTheBuild(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            MojoFailureException thrown = assertThrows(MojoFailureException.class, mojo::execute);
            assertTrue(thrown.getMessage().contains("1 error(s)"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("JAVA"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("componente"), thrown.getMessage());
        }

        @Test
        @DisplayName("failOnError=false reports the same errors and lets the build pass")
        void toleratedErrorsPass(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            mojo.failOnError = false;
            assertDoesNotThrow(mojo::execute);
        }

        @Test
        @DisplayName("Sheriff failing to run is an execution error, never a clean module")
        void unavailableIsNotClean(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), AnalysisResult.failure("no docker"));
            MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);
            assertTrue(thrown.getMessage().contains("no docker"), thrown.getMessage());
        }

        @Test
        @DisplayName("an unavailable Sheriff still fails when errors are only warnings")
        void unavailableFailsEvenWhenTolerant(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), AnalysisResult.failure("no docker"));
            mojo.failOnError = false;
            assertThrows(MojoExecutionException.class, mojo::execute);
        }
    }

    @Nested
    @DisplayName("when it declines to run")
    class Skipping {

        @Test
        @DisplayName("sheriff.skip stops it before Sheriff is reached")
        void skipFlagStopsIt(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            mojo.skip = true;
            assertDoesNotThrow(mojo::execute);
        }

        @Test
        @DisplayName("an aggregator module has no sources and is not reported as clean")
        void aggregatorIsSkipped(@TempDir Path root) throws Exception {
            Path module = root.resolve("agregador");
            Files.createDirectories(module);
            CheckMojo mojo = mojoAnswering(module.toFile(), oneError());
            assertDoesNotThrow(mojo::execute);
        }
    }

    @Nested
    @DisplayName("the configuration it derives from Maven")
    class Deriving {

        @Test
        @DisplayName("the module is the component and its parent is what gets mounted")
        void mountsTheParentAndNamesTheModule(@TempDir Path root) throws Exception {
            File module = moduleWithSources(root);
            Map<String, String> environment = mojoAnswering(module, oneError()).environment();
            assertEquals("componente", environment.get("SHERIFF_COMPONENT"));
            assertEquals(module.getParent(), environment.get("TARGET_REPO"));
        }

        @Test
        @DisplayName("the profile, with its base one, the image and the timeout are passed through")
        void passesTheRestThrough(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            mojo.profile = "JAVA_HEXAGONAL";
            mojo.timeoutSeconds = 45;
            Map<String, String> environment = mojo.environment();
            assertEquals("JAVA,JAVA_HEXAGONAL", environment.get("SHERIFF_TEST_TYPE"));
            assertEquals("kaizten/sheriff:latest", environment.get("SHERIFF_IMAGE"));
            assertEquals("45", environment.get("SHERIFF_TIMEOUT"));
        }

        @Test
        @DisplayName("Sheriff's findings and tracked files are exported under the build directory")
        void exportsUnderTheBuildDirectory(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            mojo.buildDirectory = root.resolve("componente/target").toFile();
            assertEquals(root.resolve("componente/target/sheriff").toString(),
                    mojo.environment().get("SHERIFF_EXPORT_DIR"));
        }

        @Test
        @DisplayName("left unset, the profile is the one the project declares for every tool, else JAVA")
        void theProfileFallsBackToTheProjectsDeclaration(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            mojo.profile = null;
            assertEquals("JAVA", mojo.environment().get("SHERIFF_TEST_TYPE"));
            Files.writeString(root.resolve("pom.xml"), "<project/>");
            Files.writeString(root.resolve(".sheriff.properties"), "profile=JAVA_HEXAGONAL");
            assertEquals("JAVA,JAVA_HEXAGONAL", mojo.environment().get("SHERIFF_TEST_TYPE"));
            mojo.profile = "JAVA_DDD";
            assertEquals("JAVA,JAVA_DDD", mojo.environment().get("SHERIFF_TEST_TYPE"));
        }

        @Test
        @DisplayName("the bundled catalog is where the agent looks first, not forced on it, and the cache is shared")
        void theBundledCatalogIsOnlyTheFirstChoice(@TempDir Path root) throws Exception {
            CheckMojo mojo = mojoAnswering(moduleWithSources(root), oneError());
            mojo.buildDirectory = root.resolve("componente/target").toFile();
            Map<String, String> environment = mojo.environment();
            assertEquals(null, environment.get("RULES_CATALOG"));
            assertTrue(environment.get("XDG_CACHE_HOME") != null);
            assertEquals(root.resolve("componente/target/sheriff"), mojo.agentDirectory());
        }
    }

    @Test
    @DisplayName("a finding that quotes several lines of code is still one [ERROR] line")
    void aMultiLineFindingIsLoggedOnOneLine() {
        assertEquals("a.java: Braces are not used in 'if (x) return false;' (lines 4-4).",
                CheckMojo.oneLine("a.java: Braces are not used in 'if (x)\n    return false;' (lines 4-4).\n"));
    }
}
