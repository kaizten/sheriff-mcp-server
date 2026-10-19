package com.kaizten.sheriff.infrastructure.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading JaCoCo's and PIT's XML reports, as those tools write them.
 */
class CoverageReportsTests {

    /**
     * A JaCoCo report as 0.8.15 writes it, document type included.
     */
    static final String JACOCO = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?><!DOCTYPE report PUBLIC "-//JACOCO//DTD \
            Report 1.1//EN" "report.dtd"><report name="demo"><sessioninfo id="x" start="1" dump="2"/>\
            <package name="demo"><class name="demo/Calc" sourcefilename="Calc.java"/><sourcefile name="Calc.java">\
            <line nr="2" mi="0" ci="3" mb="0" cb="0"/><line nr="4" mi="0" ci="3" mb="1" cb="1"/>\
            <line nr="10" mi="7" ci="0" mb="2" cb="0"/><counter type="LINE" missed="1" covered="2"/></sourcefile>\
            </package><package name=""><sourcefile name="Main.java"><line nr="3" mi="2" ci="0" mb="0" cb="0"/>\
            </sourcefile></package></report>""";

    /**
     * A PIT report as 1.30 writes it, in single quotes.
     */
    static final String PIT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mutations partial="true">
            <mutation detected='false' status='NO_COVERAGE' numberOfTestsRun='0'><sourceFile>Calc.java</sourceFile>\
            <mutatedClass>demo.Calc</mutatedClass><mutatedMethod>abs</mutatedMethod><methodDescription>(I)I\
            </methodDescription><lineNumber>10</lineNumber><mutator>x</mutator><killingTest/>\
            <description>removed negation</description></mutation>
            <mutation detected='true' status='KILLED' numberOfTestsRun='1'><sourceFile>Calc.java</sourceFile>\
            <mutatedClass>demo.Calc$Inner</mutatedClass><mutatedMethod>max</mutatedMethod><methodDescription>(II)I\
            </methodDescription><lineNumber>4</lineNumber><mutator>x</mutator><killingTest>t</killingTest>\
            <description>negated conditional</description></mutation>
            </mutations>
            """;

    @TempDir
    Path folder;

    private Path report(String name, String content) throws IOException {
        return Files.writeString(folder.resolve(name), content);
    }

    @Test
    @DisplayName("JaCoCo's lines of code per file under its package folder, run or not, with the branches taken; "
            + "its document type is not fetched")
    void readsJacocoLines() throws IOException {
        Map<String, Map<Integer, CoveredLine>> lines = CoverageReports.lines(report("jacoco.xml", JACOCO));
        assertEquals(Map.of(2, new CoveredLine(true, 0, 0), 4, new CoveredLine(true, 2, 1), 10,
                new CoveredLine(false, 2, 0)), lines.get("demo/Calc.java"));
        assertEquals(Map.of(3, new CoveredLine(false, 0, 0)), lines.get("Main.java"), "a class in no package");
    }

    @Test
    @DisplayName("PIT's mutants, an inner class's named by the file that holds it")
    void readsPitMutants() throws IOException {
        assertEquals(List.of(new Mutant("demo/Calc.java", 10, "NO_COVERAGE", "removed negation", false),
                new Mutant("demo/Calc.java", 4, "KILLED", "negated conditional", true)),
                CoverageReports.mutants(report("mutations.xml", PIT)));
    }

    @Test
    @DisplayName("a report that is not XML says so rather than reading as no lines")
    void aBrokenReportFails() throws IOException {
        Path broken = report("jacoco.xml", "<report");
        assertThrows(IOException.class, () -> CoverageReports.lines(broken));
    }

    @Test
    @DisplayName("a line with no instructions is not code; a line with one covered is run")
    void onlyLinesWithInstructionsAreCode() throws IOException {
        Path jacoco = report("lines.xml", "<report><package name=\"demo\"><sourcefile name=\"A.java\">"
                + "<line nr=\"1\" mi=\"0\" ci=\"0\" mb=\"0\" cb=\"0\"/><line nr=\"2\" mi=\"3\" ci=\"1\"/>"
                + "<line nr=\"x\" mi=\"1\"/></sourcefile></package></report>");
        assertEquals(Map.of(2, new CoveredLine(true, 0, 0), 0, new CoveredLine(false, 0, 0)),
                CoverageReports.lines(jacoco).get("demo/A.java"), "a number that is none reads as 0");
    }

    @Test
    @DisplayName("a mutant of a class in no package, or with fields missing, is read with what is there")
    void aMutantWithLittleIsReadAsItIs() throws IOException {
        Path pit = report("bare.xml", "<mutations><mutation status='SURVIVED'><sourceFile>Main.java</sourceFile>"
                + "<mutatedClass>Main</mutatedClass></mutation></mutations>");
        assertEquals(List.of(new Mutant("Main.java", 0, "SURVIVED", "", false)), CoverageReports.mutants(pit));
    }

    @Test
    @DisplayName("surefire's system properties, and only surefire's, comments and spaces between them left out")
    void onlySurefiresPropertiesAreRead() throws IOException {
        Path pom = report("effective.xml", "<projects><project><build><plugins>"
                + "<plugin><artifactId>maven-failsafe-plugin</artifactId><configuration><systemPropertyVariables>"
                + "<failsafe.only>x</failsafe.only></systemPropertyVariables></configuration></plugin>"
                + "<plugin><artifactId>maven-surefire-plugin</artifactId><configuration>\n  <systemPropertyVariables>"
                + "\n    <!-- a note --><one> 1 </one>\n    <two>${late}</two></systemPropertyVariables><other>y</other>"
                + "</configuration></plugin></plugins></build></project></projects>");
        assertEquals(Map.of("one", "1"), CoverageReports.surefireProperties(pom));
    }

    @Test
    @DisplayName("a report naming an external entity reads no file of the machine: the entity stays empty")
    void externalEntitiesAreNotRead() throws IOException {
        Path secret = Files.writeString(folder.resolve("secret.txt"), "the secret");
        Path pit = report("entity.xml", "<?xml version=\"1.0\"?><!DOCTYPE mutations [<!ENTITY x SYSTEM \""
                + secret.toUri() + "\">]><mutations><mutation><description>&x;</description></mutation></mutations>");
        assertEquals("", CoverageReports.mutants(pit).get(0).description());
    }

    @Test
    @DisplayName("a broken report fails without the parser printing [Fatal Error] where a hook shows it")
    void aBrokenReportPrintsNothing() throws IOException {
        Path broken = report("broken.xml", "<report");
        PrintStream original = System.err;
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        System.setErr(new PrintStream(printed, true, StandardCharsets.UTF_8));
        try {
            assertThrows(IOException.class, () -> CoverageReports.lines(broken));
        } finally {
            System.setErr(original);
        }
        assertEquals("", printed.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("it is never instantiated: every member is static")
    void itIsAUtilityClass() throws ReflectiveOperationException {
        Constructor<CoverageReports> constructor = CoverageReports.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException refused = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(refused.getCause() instanceof UnsupportedOperationException);
    }
}
