package com.kaizten.sheriff.infrastructure.build;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompileCheckTests {

    private static final String FORMAT_STOPS = """
            [ERROR] Failed to execute goal io.spring.javaformat:spring-javaformat-maven-plugin:0.0.47:validate \
            (default) on project spring-petclinic: Formatting violations found in the following files:
            [ERROR]  * /x/Owner.java
            """;

    private static final String COMPILER_SAYS = """
            [ERROR] COMPILATION ERROR :
            [ERROR] %1$s/app/src/main/java/demo/Owner.java:[60,26] cannot find symbol
            [ERROR] %1$s/app/src/main/java/demo/Owner.java:[60,40] an enum annotation value must be an enum constant
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile \
            (default-compile) on project app: Compilation failure: Compilation failure:
            [ERROR] %1$s/app/src/main/java/demo/Owner.java:[60,26] cannot find symbol
            [ERROR]   symbol:   variable CascadeType
            [ERROR]   location: class demo.Owner
            [ERROR] %1$s/app/src/main/java/demo/Owner.java:[141,57] cannot find symbol
            [ERROR]   symbol:   variable Objects
            [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.14.1:compile \
            (default-compile) on project app: Compilation failure: Compilation failure:
            [ERROR] %1$s/app/src/main/java/demo/Owner.java:[60,26] cannot find symbol
            [ERROR]   symbol:   variable CascadeType
            """;

    @TempDir
    Path mount;

    /**
     * Answers each command with the next outcome, and keeps the commands.
     */
    private static final class Queued implements ProcessRunner {

        private final Deque<ProcessOutcome> outcomes;
        private final List<String> commands = new ArrayList<>();

        Queued(ProcessOutcome... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
                Duration timeout) {
            commands.add(command.get(command.size() - 1));
            return outcomes.removeFirst();
        }
    }

    private CompileCheck check(Queued processes) {
        return new CompileCheck(processes, mount,
                (component, arguments) -> Optional.of("cd " + component + " && mvn " + String.join(" ", arguments)),
                Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("a build that compiles compiles, with test-compile and nothing else")
    void compiles() {
        Queued processes = new Queued(ProcessOutcome.completed(0, "", ""));

        CompileOutcome outcome = check(processes).check("app").orElseThrow();

        assertTrue(outcome.compiles());
        assertEquals(List.of("cd app && mvn -B -q test-compile"), processes.commands);
        assertEquals(Optional.empty(), outcome.bypassed());
    }

    @Test
    @DisplayName("the compiler's errors are each read once, relative to the mount, with the symbol")
    void readsTheCompilersErrors() {
        Queued processes = new Queued(ProcessOutcome.completed(1, String.format(COMPILER_SAYS, mount), ""));

        CompileOutcome outcome = check(processes).check("app").orElseThrow();

        assertFalse(outcome.compiles());
        assertEquals(List.of(
                new CompileError("app/src/main/java/demo/Owner.java", 60,
                        "an enum annotation value must be an enum constant"),
                new CompileError("app/src/main/java/demo/Owner.java", 60,
                        "cannot find symbol, symbol: variable CascadeType"),
                new CompileError("app/src/main/java/demo/Owner.java", 141,
                        "cannot find symbol, symbol: variable Objects")), outcome.errors());
    }

    @Test
    @DisplayName("one message on other lines or files is other errors; a file outside the mount keeps its path")
    void errorsAreKeptApart() {
        String output = """
                [ERROR] /elsewhere/B.java:[3,1] cannot find symbol
                [ERROR] %1$s/app/A.java:[4,1] cannot find symbol
                [ERROR]   location: class A
                [ERROR]   symbol:   variable X
                [ERROR] %1$s/app/A.java:[5,1] cannot find symbol
                [ERROR] %1$s/app/C.java:[4,1] cannot find symbol""".formatted(mount);

        assertEquals(List.of(new CompileError("/elsewhere/B.java", 3, "cannot find symbol"),
                new CompileError("app/A.java", 4, "cannot find symbol, symbol: variable X"),
                new CompileError("app/A.java", 5, "cannot find symbol"),
                new CompileError("app/C.java", 4, "cannot find symbol")), CompileCheck.errors(output, mount));
    }

    @Test
    @DisplayName("a path Java cannot read is kept as the compiler wrote it")
    void anUnreadablePathIsKept() {
        assertEquals(List.of(new CompileError("bad\u0000.java", 1, "x")),
                CompileCheck.errors("[ERROR] bad\u0000.java:[1,1] x", mount));
    }

    @Test
    @DisplayName("a check that stops the build before compiling is left out by compiling with the compiler alone")
    void compilesPastACheckBeforeCompiling() {
        Queued processes = new Queued(ProcessOutcome.completed(1, FORMAT_STOPS, ""),
                ProcessOutcome.completed(1, String.format(COMPILER_SAYS, mount), ""));

        CompileOutcome outcome = check(processes).check("app").orElseThrow();

        assertFalse(outcome.compiles());
        assertEquals(List.of("cd app && mvn -B -q test-compile",
                "cd app && mvn -B -q compiler:compile compiler:testCompile"), processes.commands);
        assertEquals(Optional.of("spring-javaformat-maven-plugin:validate"), outcome.bypassed());
        assertEquals("cd app && mvn -B -q compiler:compile compiler:testCompile", outcome.command());
    }

    @Test
    @DisplayName("code that compiles past the check says which check it left out")
    void compilesPastACheck() {
        Queued processes = new Queued(ProcessOutcome.completed(1, FORMAT_STOPS, ""),
                ProcessOutcome.completed(0, "", ""));

        CompileOutcome outcome = check(processes).check("app").orElseThrow();

        assertTrue(outcome.compiles());
        assertEquals(Optional.of("spring-javaformat-maven-plugin:validate"), outcome.bypassed());
    }

    @Test
    @DisplayName("a failure that is no error of the compiler's, or a build that did not run, tells nothing")
    void tellsNothingItDoesNotKnow() {
        assertEquals(Optional.empty(), check(new Queued(ProcessOutcome.completed(1, "Could not resolve", "")))
                .check("app"));
        assertEquals(Optional.empty(), check(new Queued(ProcessOutcome.unavailable("no mvn"))).check("app"));
        assertEquals(Optional.empty(), check(new Queued(ProcessOutcome.completed(1, FORMAT_STOPS, ""),
                ProcessOutcome.completed(1, "Could not resolve", ""))).check("app"));
    }

    @Test
    @DisplayName("a component that does not build with Maven is not compiled")
    void notMaven() {
        Queued processes = new Queued();

        assertEquals(Optional.empty(), new CompileCheck(processes, mount, (component, arguments) -> Optional.empty(),
                Duration.ofMinutes(1)).check("app"));
        assertEquals(List.of(), processes.commands);
    }

    @Test
    @DisplayName("the goal that stopped a build is named unless it is the compiler's")
    void namesTheStoppingGoal() {
        assertEquals(Optional.of("spring-javaformat-maven-plugin:validate"), CompileCheck.stoppingGoal(FORMAT_STOPS));
        assertEquals(Optional.empty(), CompileCheck.stoppingGoal(String.format(COMPILER_SAYS, mount)));
        assertEquals(Optional.empty(), CompileCheck.stoppingGoal("BUILD SUCCESS"));
    }
}
