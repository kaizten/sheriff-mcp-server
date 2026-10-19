package com.kaizten.sheriff.infrastructure.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measuring a change's tests: which change, which commands, and what comes
 * of the reports. Git is real; Maven is stood in for by a runner that writes
 * the reports the real tools would, so none of this needs a build.
 */
class ChangeCoverageTests {

    private static final String COMMITTED = "package demo;\n\npublic class Calc {\n}\n";
    private static final String CHANGED = "package demo;\n\npublic class Calc {\n\n    public int abs(int a) {\n"
            + "        return a < 0 ? -a : a;\n    }\n}\n";
    private static final String JACOCO = "<report name=\"app\"><package name=\"demo\"><sourcefile name=\"Calc.java\">"
            + "<line nr=\"3\" mi=\"0\" ci=\"3\" mb=\"0\" cb=\"0\"/><line nr=\"6\" mi=\"0\" ci=\"5\" mb=\"1\" cb=\"1\"/>"
            + "</sourcefile></package></report>";
    private static final String PIT = "<mutations><mutation detected='true' status='KILLED'><sourceFile>Calc.java"
            + "</sourceFile><mutatedClass>demo.Calc</mutatedClass><lineNumber>6</lineNumber><description>negated "
            + "conditional</description></mutation><mutation detected='false' status='SURVIVED'><sourceFile>Calc.java"
            + "</sourceFile><mutatedClass>demo.Calc</mutatedClass><lineNumber>6</lineNumber><description>removed "
            + "negation</description></mutation></mutations>";

    private static final Pattern OUTPUT = Pattern.compile("-Doutput=(\\S+)");
    private static final String EFFECTIVE_POM = "<projects><project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>"
            + "<systemPropertyVariables><sheriff.state.home>/work/target/state</sheriff.state.home>"
            + "<spaced>a b</spaced><late>${not.resolved}</late></systemPropertyVariables></configuration></plugin>"
            + "</plugins></build></project></projects>";

    @TempDir
    Path mount;

    /**
     * Git as it is; Maven as a stand-in that records each command and writes
     * the reports it is told to.
     */
    private static final class StandInMaven implements ProcessRunner {

        private final ProcessRunner real = new SystemProcessRunner();
        private final List<String> commands = new ArrayList<>();
        private final Path root;
        private String jacoco;
        private String pit;
        private String effectivePom = EFFECTIVE_POM;
        private boolean copiesPlugin = true;
        private ProcessOutcome outcome = ProcessOutcome.completed(0, "[ERROR] the build said why", "");

        StandInMaven(Path root, boolean writesCoverage, boolean writesMutants) {
            this.root = root;
            this.jacoco = writesCoverage ? JACOCO : null;
            this.pit = writesMutants ? PIT : null;
        }

        @Override
        public ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
                Duration timeout) {
            if (command.get(0).equals("git")) {
                return real.run(command, workingDirectory, environment, timeout);
            }
            String line = command.get(command.size() - 1);
            commands.add(line);
            try {
                if (line.contains("prepare-agent") && jacoco != null) {
                    write(root.resolve("target/site/jacoco/jacoco.xml"), jacoco);
                }
                if (line.contains("mutationCoverage") && pit != null) {
                    write(root.resolve("target/pit-reports/mutations.xml"), pit);
                }
                Matcher output = OUTPUT.matcher(line);
                if (line.contains("effective-pom") && output.find() && effectivePom != null) {
                    Files.writeString(Path.of(output.group(1)), effectivePom);
                }
                if (line.contains("dependency-plugin") && copiesPlugin) {
                    write(root.resolve("target/sheriff-pitest/pitest-junit5-plugin-1.2.3.jar"), "jar");
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return outcome;
        }

        private static void write(Path file, String content) throws IOException {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }
    }

    private void git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertEquals(0, process.waitFor(), String.join(" ", command));
    }

    /**
     * A Maven component at its first commit, with its source changed since.
     */
    private Path changedProject(String test) throws IOException, InterruptedException {
        Path app = mount.resolve("app");
        Path source = Files.createDirectories(app.resolve("src/main/java/demo")).resolve("Calc.java");
        Files.writeString(source, COMMITTED);
        Files.writeString(app.resolve("pom.xml"), "<project/>");
        Files.writeString(Files.createDirectories(app.resolve("src/test/java/demo")).resolve("CalcTest.java"), test);
        git(app, "init", "-q");
        git(app, "add", "-A");
        git(app, "commit", "-q", "-m", "seed");
        Files.writeString(source, CHANGED);
        return app;
    }

    private static ChangeCoverage coverage(Path mount, StandInMaven maven) {
        return new ChangeCoverage(maven, mount, (component, arguments) -> mount.resolve(component)
                .resolve("pom.xml").toFile().isFile() ? Optional.of("mvn " + String.join(" ", arguments))
                : Optional.empty(), component -> CoverageThresholds.defaults(), Duration.ofMinutes(1),
                Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("JaCoCo runs the tests, then PIT mutates the changed classes, with JUnit 5's plugin when a test "
            + "uses JUnit 5; only the changed lines count")
    void measuresTheChange() throws IOException, InterruptedException {
        Path app = changedProject("import org.junit.jupiter.api.Test;\nclass CalcTest {}\n");
        StandInMaven maven = new StandInMaven(app, true, true);

        CoverageVerdict verdict = coverage(mount, maven).measure("app");

        assertEquals(4, maven.commands.size(), maven.commands.toString());
        assertTrue(maven.commands.get(0).contains("org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent test "
                + "org.jacoco:jacoco-maven-plugin:0.8.15:report"), maven.commands.get(0));
        assertTrue(maven.commands.get(1).contains("maven-dependency-plugin:3.8.1:copy "
                + "-Dartifact=org.pitest:pitest-junit5-plugin:1.2.3"), maven.commands.get(1));
        assertTrue(maven.commands.get(2).contains("maven-help-plugin:3.5.1:effective-pom"), maven.commands.get(2));
        String pit = maven.commands.get(3);
        assertTrue(pit.contains("-DtargetClasses=demo.Calc,demo.Calc$* -DtargetTests=*"), pit);
        assertTrue(pit.contains("-DadditionalClasspathElements=" + app.resolve(
                "target/sheriff-pitest/pitest-junit5-plugin-1.2.3.jar").toAbsolutePath().normalize()));
        assertTrue(pit.contains("-Dmutators=STRONGER,REMOVE_CONDITIONALS,EXPERIMENTAL_ARGUMENT_PROPAGATION"), pit);
        assertEquals(new Tally(1, 1), verdict.lines(), "line 3 holds code, but the change did not write it");
        assertEquals(new Tally(2, 1), verdict.branches());
        assertEquals(List.of("src/main/java/demo/Calc.java:6 takes 1 of its 2 branches"), verdict.partial());
        assertEquals(new Tally(2, 1), verdict.mutants());
        assertEquals(List.of("src/main/java/demo/Calc.java:6 removed negation"), verdict.survivors());
        assertFalse(verdict.passes());
    }

    @Test
    @DisplayName("with JUnit 4 alone PIT's JUnit 5 plugin stays out, as it stops PIT")
    void junit4LeavesThePluginOut() throws IOException, InterruptedException {
        Path app = changedProject("import org.junit.Test;\npublic class CalcTest {}\n");
        StandInMaven maven = new StandInMaven(app, true, true);

        coverage(mount, maven).measure("app");

        assertEquals(3, maven.commands.size(), maven.commands.toString());
        assertFalse(maven.commands.get(2).contains("additionalClasspathElements"), maven.commands.get(2));
    }

    @Test
    @DisplayName("PIT runs the tests without surefire, so it gets the system properties surefire gives them, but "
            + "for a value with a space, on which its test process dies")
    void surefirePropertiesReachPit() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        StandInMaven maven = new StandInMaven(app, true, true);

        coverage(mount, maven).measure("app");

        String pit = maven.commands.get(maven.commands.size() - 1);
        assertTrue(pit.endsWith("-DargLine=-Dsheriff.state.home=/work/target/state"), pit);
        assertFalse(pit.contains("spaced"), pit);
        assertFalse(pit.contains("not.resolved"), "what Maven could not resolve is no value: " + pit);
    }

    @Test
    @DisplayName("no JaCoCo report means the tests ran without its agent, and it says so with Maven's words")
    void noCoverageReportIsUnmeasured() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");

        CoverageVerdict verdict = coverage(mount, new StandInMaven(app, false, true)).measure("app");

        assertTrue(verdict.applies());
        assertFalse(verdict.measured());
        assertTrue(verdict.problem().contains("@{argLine}"), verdict.problem());
        assertTrue(verdict.problem().contains("the build said why"), verdict.problem());
    }

    @Test
    @DisplayName("PIT finishing with no report made no mutant, which is no failure")
    void noMutantsIsMeasured() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");

        CoverageVerdict verdict = coverage(mount, new StandInMaven(app, true, false)).measure("app");

        assertTrue(verdict.measured(), verdict.problem());
        assertEquals(Tally.none(), verdict.mutants());
    }

    @Test
    @DisplayName("nothing applies with no change, with no Maven, or with no repository")
    void whenItDoesNotApply() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        StandInMaven maven = new StandInMaven(app, true, true);
        assertTrue(coverage(mount, maven).applies("app"));
        git(app, "add", "-A");
        git(app, "commit", "-q", "-m", "done");
        assertFalse(coverage(mount, maven).applies("app"), "committed, the change is not this one");
        assertFalse(coverage(mount, maven).measure("app").applies());
        Files.writeString(app.resolve("src/main/java/demo/Calc.java"), COMMITTED);
        Files.delete(app.resolve("pom.xml"));
        assertFalse(coverage(mount, maven).applies("app"), "not a Maven component");
        Path loose = Files.createDirectories(mount.resolve("loose/src/main/java"));
        Files.writeString(loose.resolve("A.java"), "class A {}");
        Files.writeString(mount.resolve("loose/pom.xml"), "<project/>");
        assertFalse(coverage(mount, maven).applies("loose"), "no repository, nothing to compare with");
        assertTrue(maven.commands.isEmpty(), "knowing whether it applies runs no build");
    }

    @Test
    @DisplayName("a report that is not XML is no measure, and says the report could not be read")
    void aBrokenReportIsUnmeasured() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        StandInMaven maven = new StandInMaven(app, true, true);
        maven.jacoco = "<report";

        CoverageVerdict verdict = coverage(mount, maven).measure("app");

        assertTrue(verdict.problem().startsWith("a report could not be read: "), verdict.problem());
        assertFalse(verdict.problem().contains("%s"), verdict.problem());
    }

    @Test
    @DisplayName("PIT failing with no report is no measure, in Maven's words")
    void aFailedMutationRunIsUnmeasured() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        StandInMaven maven = new StandInMaven(app, true, false);
        maven.outcome = ProcessOutcome.completed(1, "", "Tests failing without mutation");

        CoverageVerdict verdict = coverage(mount, maven).measure("app");

        assertEquals(String.format("PIT wrote no report. Maven said:%nTests failing without mutation"),
                verdict.problem());
    }

    @Test
    @DisplayName("PIT's JUnit 5 plugin is copied once, and a copy that fails is no measure")
    void theJunit5PluginIsCopiedOnce() throws IOException, InterruptedException {
        Path app = changedProject("import org.junit.jupiter.api.Test;\nclass CalcTest {}\n");
        StandInMaven failing = new StandInMaven(app, true, true);
        failing.copiesPlugin = false;
        assertTrue(coverage(mount, failing).measure("app").problem()
                .startsWith("PIT's JUnit 5 plugin (org.pitest:pitest-junit5-plugin:1.2.3) could not be downloaded."));
        StandInMaven copying = new StandInMaven(app, true, true);
        coverage(mount, copying).measure("app");
        StandInMaven again = new StandInMaven(app, true, true);
        coverage(mount, again).measure("app");
        assertTrue(again.commands.stream().noneMatch(command -> command.contains("dependency-plugin")),
                again.commands.toString());
        assertTrue(again.commands.get(again.commands.size() - 1).contains("-DadditionalClasspathElements=" + app.resolve(
                "target/sheriff-pitest/pitest-junit5-plugin-1.2.3.jar").toAbsolutePath().normalize()));
    }

    @Test
    @DisplayName("a Maven that could not start, or said a lot, is quoted as it was: why, or its end")
    void whatMavenSaidIsQuoted() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        StandInMaven unavailable = new StandInMaven(app, false, false);
        unavailable.outcome = ProcessOutcome.unavailable("mvn is not on the PATH");
        assertTrue(coverage(mount, unavailable).measure("app").problem().endsWith("mvn is not on the PATH"));
        StandInMaven talkative = new StandInMaven(app, false, false);
        talkative.outcome = ProcessOutcome.completed(1, "start" + "x".repeat(1499) + "END", "");
        String problem = coverage(mount, talkative).measure("app").problem();
        assertTrue(problem.endsWith("x".repeat(1497) + "END"), problem);
        assertFalse(problem.contains("start"), "only the last 1500 characters");
        StandInMaven exact = new StandInMaven(app, false, false);
        exact.outcome = ProcessOutcome.completed(1, "s" + "x".repeat(1499), "");
        assertTrue(coverage(mount, exact).measure("app").problem().endsWith("s" + "x".repeat(1499)), "1500 are kept");
    }

    @Test
    @DisplayName("with no system property for the tests, PIT gets no argLine; a missing effective pom is none either")
    void noSurefirePropertiesNoArgLine() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        StandInMaven plain = new StandInMaven(app, true, true);
        plain.effectivePom = "<project><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId>"
                + "</plugin></plugins></build></project>";
        coverage(mount, plain).measure("app");
        assertFalse(plain.commands.get(plain.commands.size() - 1).contains("argLine"), plain.commands.toString());
        StandInMaven none = new StandInMaven(app, true, true);
        none.effectivePom = null;
        coverage(mount, none).measure("app");
        assertFalse(none.commands.get(none.commands.size() - 1).contains("argLine"), none.commands.toString());
    }

    @Test
    @DisplayName("JUnit 5 is told by a Java test that imports it: no test folder, another file, or an unreadable "
            + "one is none")
    void junit5IsToldByAJavaTest() throws IOException, InterruptedException {
        Path app = changedProject("import org.junit.Test;\npublic class CalcTest {}\n");
        Files.writeString(app.resolve("src/test/java/demo/notes.txt"), "org.junit.jupiter");
        Files.write(app.resolve("src/test/java/demo/Broken.java"), new byte[] {(byte) 0xFF, (byte) 0xFE, 0x41});
        StandInMaven maven = new StandInMaven(app, true, true);
        coverage(mount, maven).measure("app");
        assertTrue(maven.commands.stream().noneMatch(command -> command.contains("additionalClasspathElements")),
                maven.commands.toString());
        deleteTree(app.resolve("src/test"));
        StandInMaven untested = new StandInMaven(app, true, true);
        assertTrue(coverage(mount, untested).measure("app").measured());
        assertTrue(untested.commands.stream().noneMatch(command -> command.contains("additionalClasspathElements")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "permissions are POSIX's")
    @DisplayName("a test folder that cannot be read says nothing of JUnit 5, and the measure goes on")
    void anUnreadableTestFolderIsNoJunit5() throws IOException, InterruptedException {
        assumeTrue(!"root".equals(System.getProperty("user.name")), "root reads every folder");
        Path app = changedProject("import org.junit.Test;\npublic class CalcTest {}\n");
        Path locked = Files.createDirectories(app.resolve("src/test/java/locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            StandInMaven maven = new StandInMaven(app, true, true);
            assertTrue(coverage(mount, maven).measure("app").measured());
            assertTrue(maven.commands.stream().noneMatch(command -> command.contains("additionalClasspathElements")),
                    maven.commands.toString());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    @DisplayName("only the main Java sources the change wrote count: no other file, no deleted one, no line removed")
    void onlyWrittenJavaSourcesCount() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        Path other = app.resolve("src/main/java/demo/Other.java");
        Files.writeString(other, "package demo;\n\nclass Other {\n    int a;\n    int b;\n}\n");
        Path gone = app.resolve("src/main/java/demo/Gone.java");
        Files.writeString(gone, "package demo;\nclass Gone {}\n");
        git(app, "add", "-A");
        git(app, "commit", "-q", "-m", "more");
        Files.writeString(app.resolve("src/main/java/demo/Calc.java"), COMMITTED);
        Files.writeString(other, "package demo;\n\nclass Other {\n    int a;\n}\n");
        Files.delete(gone);
        Files.writeString(app.resolve("src/main/java/demo/notes.txt"), "not Java\n");
        Files.writeString(app.resolve("src/test/java/demo/CalcTest.java"), "class CalcTest {\n    int t;\n}\n");
        StandInMaven maven = new StandInMaven(app, true, true);

        assertFalse(coverage(mount, maven).applies("app"), "a removed line, a deleted file and a text file are no "
                + "lines written");
        assertFalse(coverage(mount, maven).measure("app").applies());
    }

    @Test
    @DisplayName("a change in a component that does not build with Maven is not measured, and runs nothing")
    void noMavenNoMeasure() throws IOException, InterruptedException {
        Path app = changedProject("class CalcTest {}\n");
        Files.delete(app.resolve("pom.xml"));
        StandInMaven maven = new StandInMaven(app, true, true);

        assertFalse(coverage(mount, maven).applies("app"));
        assertFalse(coverage(mount, maven).measure("app").applies());
        assertTrue(maven.commands.isEmpty());
    }

    private static void deleteTree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
