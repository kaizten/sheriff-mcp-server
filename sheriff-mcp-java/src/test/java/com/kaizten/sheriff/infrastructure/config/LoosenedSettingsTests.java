package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which lines of a project's settings check less than the last commit has
 * them check.
 */
class LoosenedSettingsTests {

    private static final Path FILE = Path.of("project", ".sheriff.properties");
    private static final Path POM = Path.of("project", "pom.xml");
    private static final String POM_TEXT = """
            <project>
              <properties>
                %s
                <java.version>21</java.version>
              </properties>
              <profiles><profile><properties><sheriff.coverage>0</sheriff.coverage></properties></profile></profiles>
            </project>
            """;

    private static List<String> loosened(String committed, String current) {
        return LoosenedSettings.between(FILE, committed, current).stream().map(Loosening::key).toList();
    }

    @Test
    @DisplayName("prototype mode turned on, or the tests taken out of the end of a turn, check less")
    void flagsThatTurnAChecksOff() {
        assertEquals(List.of("SHERIFF_PROTOTYPE"), loosened("", "SHERIFF_PROTOTYPE=1\n"));
        assertEquals(List.of(), loosened("SHERIFF_PROTOTYPE=1\n", ""));
        assertEquals(List.of("SHERIFF_STOP_RUNS_TESTS"), loosened("SHERIFF_STOP_RUNS_TESTS=1\n", ""));
        assertEquals(List.of(), loosened("", "SHERIFF_STOP_RUNS_TESTS=1\n"));
        assertEquals(List.of("SHERIFF_SCOPE"), loosened("SHERIFF_SCOPE=all\n", "SHERIFF_SCOPE=applied\n"));
    }

    @Test
    @DisplayName("a threshold lowered checks less, by its own key or the setting's, against its default when unset")
    void lowerThresholdsCheckLess() {
        assertEquals(List.of("coverage"), loosened("", "coverage=80%\n"));
        assertEquals(List.of("SHERIFF_MUTATION"), loosened("SHERIFF_MUTATION=90\n", "SHERIFF_MUTATION=85\n"));
        assertEquals(List.of(), loosened("", "mutation=95\n"));
        assertEquals(List.of("SHERIFF_STOP_MAX_STALLS", "SHERIFF_TIMEOUT"),
                loosened("", "SHERIFF_TIMEOUT=5\nSHERIFF_STOP_MAX_STALLS=0\n"));
    }

    @Test
    @DisplayName("a profile changed or removed checks less, one added does not")
    void aProfileCountsWhenChangedOrRemoved() {
        assertEquals(List.of(), loosened("", "profile=JAVA_HEXAGONAL\n"));
        assertEquals(List.of("profile"), loosened("profile=JAVA_HEXAGONAL\n", "profile=JAVA\n"));
        assertEquals(List.of("profile"), loosened("profile=JAVA_HEXAGONAL\n", ""));
    }

    @Test
    @DisplayName("a value the setting does not take is read as unset, as the tools ignore it")
    void aValueTheToolsIgnoreIsUnset() {
        assertEquals(List.of(), loosened("", "SHERIFF_PROTOTYPE=true\n"));
        assertEquals(List.of("SHERIFF_STOP_RUNS_TESTS"), loosened("SHERIFF_STOP_RUNS_TESTS=1\n",
                "SHERIFF_STOP_RUNS_TESTS=yes\n"));
    }

    @Test
    @DisplayName("settings that do not decide what is checked never count")
    void otherSettingsNeverCount() {
        assertEquals(List.of(), loosened("SHERIFF_FAIL_FAST=0\n", "SHERIFF_FAIL_FAST=1\nSHERIFF_GATE_TTL=99999\n"));
        assertEquals(List.of(), loosened("", "unknown=0\nAI_BACKEND=local\n"));
    }

    @Test
    @DisplayName("in a pom.xml, only the project's own sheriff properties count, never a build profile's")
    void aPomCountsItsOwnProperties() {
        String before = String.format(POM_TEXT, "<sheriff.coverage>90</sheriff.coverage>");
        String after = String.format(POM_TEXT, "<sheriff.coverage>40</sheriff.coverage>");
        List<Loosening> loosened = LoosenedSettings.between(POM, before, after);
        assertEquals(1, loosened.size());
        assertEquals(POM + ": sheriff.coverage 90 -> 40", loosened.get(0).describe());
        assertEquals(List.of(), LoosenedSettings.between(POM, before, before.replace("21", "25")));
        assertEquals(List.of(), LoosenedSettings.between(POM, String.format(POM_TEXT, ""), String.format(POM_TEXT, "")));
        assertEquals("sheriff.profile", LoosenedSettings.between(POM,
                String.format(POM_TEXT, "<sheriff.profile>JAVA_DDD</sheriff.profile>"), "not xml").get(0).key());
    }

    @Test
    @DisplayName("a value not set reads as unset in the description")
    void describesAnUnsetValue() {
        Loosening loosening = LoosenedSettings.between(FILE, "", "SHERIFF_PROTOTYPE=1").get(0);
        assertEquals(FILE + ": SHERIFF_PROTOTYPE unset -> 1", loosening.describe());
        assertEquals(".sheriff.properties: SHERIFF_PROTOTYPE unset -> 1", loosening.describeFrom(Path.of("project")));
        assertEquals(FILE + ": SHERIFF_PROTOTYPE unset -> 1", loosening.describeFrom(Path.of("elsewhere")));
    }

    @Test
    @DisplayName("only a .sheriff.properties and a pom.xml hold settings")
    void knowsWhichFilesHoldSettings() {
        assertTrue(LoosenedSettings.holdsSettings(FILE));
        assertTrue(LoosenedSettings.holdsSettings(POM));
        assertFalse(LoosenedSettings.holdsSettings(Path.of("project", "build.gradle")));
    }
}
