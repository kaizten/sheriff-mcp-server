package com.kaizten.sheriff.infrastructure.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What of a failed build's output an answer shows.
 */
class BuildOutputTests {

    /**
     * The shape of what {@code ./mvnw -q test} printed for PetClinic with
     * checkstyle 14.3.0 under Java 17: the cause on its second line, a stack
     * trace, the goal that failed, sixty jars of a class path, and Maven's
     * closing lines. The two causes are copied from that run.
     */
    private static final String PETCLINIC = "[WARNING] Error injecting: "
            + "org.apache.maven.plugins.checkstyle.CheckstyleViolationCheckMojo\n"
            + "java.lang.UnsupportedClassVersionError: com/puppycrawl/tools/checkstyle/api/CheckstyleException has "
            + "been compiled by a more recent version of the Java Runtime (class file version 65.0), this version of "
            + "the Java Runtime only recognizes class file versions up to 61.0\n"
            + IntStream.range(0, 60).mapToObj(index -> "    at java.lang.ClassLoader.defineClass" + index
                    + " (ClassLoader.java:1017)").collect(Collectors.joining("\n")) + "\n"
            + "[ERROR] Failed to execute goal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check "
            + "(nohttp-checkstyle-validation) on project spring-petclinic: Execution nohttp-checkstyle-validation of "
            + "goal org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check failed: An API incompatibility was "
            + "encountered while executing org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0:check: "
            + "java.lang.UnsupportedClassVersionError: com/puppycrawl/tools/checkstyle/api/CheckstyleException has "
            + "been compiled by a more recent version of the Java Runtime\n"
            + IntStream.range(0, 60).mapToObj(index -> "[ERROR] urls[" + index + "] = file:/home/ana/.m2/repository/"
                    + "org/codehaus/plexus/plexus-utils/4.0.1/plexus-utils-4.0.1.jar").collect(Collectors.joining("\n"))
            + "\n[ERROR] -> [Help 1]\n[ERROR] \n"
            + "[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/PluginContainerException\n";

    @Test
    @DisplayName("an output that fits is shown whole, without the blank lines around it")
    void anOutputThatFitsIsWhole() {
        assertEquals("BUILD FAILURE", BuildOutput.excerpt("\n BUILD FAILURE \n", 100));
    }

    @Test
    @DisplayName("PetClinic's checkstyle failure starts with its cause, then the goal, then the end, within the size")
    void theCauseComesFirst() {
        String shown = BuildOutput.excerpt(PETCLINIC, 2000);

        assertTrue(shown.length() <= 2000, String.valueOf(shown.length()));
        assertTrue(shown.startsWith("java.lang.UnsupportedClassVersionError: com/puppycrawl/tools/checkstyle"), shown);
        assertTrue(shown.contains("class file version 65.0"), shown);
        assertTrue(shown.contains("\n[ERROR] Failed to execute goal org.apache.maven.plugins:maven-checkstyle-plugin"),
                shown);
        assertTrue(shown.endsWith("display/MAVEN/PluginContainerException"), shown);
        assertTrue(shown.contains("[...]\n[ERROR] urls["), "the end starts at the start of a line: " + shown);
        assertFalse(shown.contains("ClassLoader.defineClass"), "a stack frame says nothing a model needs");
    }

    @Test
    @DisplayName("with no line that says why, the end alone, as many characters as fit")
    void withNoCauseTheEndAlone() {
        String output = "start" + "x".repeat(1499) + "END";

        assertEquals("x".repeat(1497) + "END", BuildOutput.excerpt(output, 1500));
    }

    @Test
    @DisplayName("each cause once and cut, compiler and test failures, and the line after Gradle's reason")
    void causesAreFoundOnceAndCut() {
        String output = String.join("\n",
                "[ERROR] /work/app/src/main/java/demo/Pet.java:[12,5] cannot find symbol",
                "[ERROR] Failures: ",
                "[ERROR]   PetTests.lastVisit:30 expected: <2020-01-01> but was: <null>",
                "[ERROR] Tests run: 84, Failures: 1, Errors: 0, Skipped: 0",
                "Caused by: java.lang.IllegalStateException: " + "y".repeat(500),
                "Caused by: java.lang.IllegalStateException: " + "y".repeat(500),
                "* What went wrong:",
                "Execution failed for task ':test'.",
                "  ● Pet › last visit",
                "OwnerTests > findsOwner() FAILED",
                "noise line\n".repeat(400));

        String shown = BuildOutput.excerpt(output, 3000);

        assertTrue(shown.startsWith("[ERROR] /work/app/src/main/java/demo/Pet.java:[12,5] cannot find symbol\n"
                + "[ERROR] Failures:\n[ERROR]   PetTests.lastVisit:30"), shown);
        assertTrue(shown.contains("\nExecution failed for task ':test'.\n"), shown);
        assertTrue(shown.contains("\n  ● Pet › last visit\nOwnerTests > findsOwner() FAILED\n"), shown);
        String cause = "Caused by: java.lang.IllegalStateException: ";
        assertEquals(shown.indexOf(cause), shown.lastIndexOf(cause), "the same cause twice is shown once");
        assertTrue(shown.contains(cause + "y".repeat(400 - cause.length()) + "\n"), "a cause is cut to 400");
    }

    @Test
    @DisplayName("the causes take at most half the size, the end the rest")
    void causesTakeAtMostHalf() {
        String output = IntStream.range(0, 50).mapToObj(index -> "java.lang.IllegalStateException: case " + index)
                .collect(Collectors.joining("\n")) + "\n" + "z".repeat(3000);

        String shown = BuildOutput.excerpt(output, 1000);

        assertTrue(shown.indexOf("[...]") <= 500, shown);
        assertTrue(shown.endsWith("z".repeat(400)), shown);
        assertEquals(1000, shown.length(), "an end with no line break in it is cut where the size says");
    }

    @Test
    @DisplayName("the exact excerpt: the cause, the mark, and the end from the first whole line that fits")
    void theExactExcerpt() {
        String output = "java.lang.IllegalStateException: boom\n" + IntStream.rangeClosed(1, 20)
                .mapToObj(index -> String.format("line%02d", index)).collect(Collectors.joining("\n"));

        assertEquals("java.lang.IllegalStateException: boom\n[...]\nline16\nline17\nline18\nline19\nline20",
                BuildOutput.excerpt(output, 80));
        assertEquals("java.lang.IllegalStateException: boom\n[...]\nline16\nline17\nline18\nline19\nline20",
                BuildOutput.excerpt(output, 78), "an end that starts right after a line break keeps that line");
    }

    @Test
    @DisplayName("an output of exactly the size is whole; one character more and its start goes")
    void theSizeIsAnUpperBound() {
        assertEquals("abcde", BuildOutput.excerpt("abcde", 5));
        assertEquals("bcdef", BuildOutput.excerpt("abcdef", 5));
        String withCause = "java.lang.IllegalStateException: x\nend";
        assertEquals(withCause, BuildOutput.excerpt(withCause, withCause.length()), "one that fits is never cut up");
    }

    @Test
    @DisplayName("a cause that fills exactly half the size is shown; one character longer is not")
    void causesFillAtMostHalfExactly() {
        String noise = "\n" + "noise line\n".repeat(30);
        String fits = "java.lang.IllegalStateException: " + "a".repeat(16);
        String tooLong = fits + "a";

        assertTrue(BuildOutput.excerpt(fits + noise, 100).startsWith(fits + "\n[...]\n"), "49 characters and a break");
        assertFalse(BuildOutput.excerpt(tooLong + noise, 100).contains("[...]"), "51 do not fit in 50");
    }

    @Test
    @DisplayName("a cause of 400 characters is whole, one of 401 is cut to 400")
    void causesAreCutPastFourHundred() {
        String noise = "\n" + "noise line\n".repeat(300);
        String whole = "java.lang.IllegalStateException: " + "y".repeat(367);
        String longer = whole + "Z";

        assertTrue(BuildOutput.excerpt(whole + noise, 3000).startsWith(whole + "\n[...]"));
        assertTrue(BuildOutput.excerpt(longer + noise, 3000).startsWith(whole + "\n[...]"));
    }

    @Test
    @DisplayName("Gradle's reason as the very last line is shown, with nothing after it to add")
    void gradlesReasonLast() {
        String output = "noise line\n".repeat(300) + "* What went wrong:";

        assertTrue(BuildOutput.excerpt(output, 1000).startsWith("* What went wrong:\n[...]"));
    }

    @Test
    @DisplayName("the line after Gradle's reason is cut to 400 characters like any cause")
    void gradlesReasonIsCut() {
        String reason = "Execution failed for task ':test' " + "w".repeat(500);
        String output = "* What went wrong:\n" + reason + "\n" + "noise line\n".repeat(300);

        assertTrue(BuildOutput.excerpt(output, 3000).startsWith("* What went wrong:\n" + reason.substring(0, 400)
                + "\n[...]"));
    }

    @Test
    @DisplayName("it is never instantiated: every member is static")
    void itIsAUtilityClass() throws ReflectiveOperationException {
        Constructor<BuildOutput> constructor = BuildOutput.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException refused = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(refused.getCause() instanceof UnsupportedOperationException);
    }
}
