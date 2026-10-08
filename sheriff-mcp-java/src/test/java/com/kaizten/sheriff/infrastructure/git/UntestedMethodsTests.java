package com.kaizten.sheriff.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The methods a change adds that no changed test calls.
 */
class UntestedMethodsTests {

    private static final String PET = """
            package demo;

            public class Pet {

                private final List<Visit> visits;

                public Pet(List<Visit> visits) {
                    this.visits = visits;
                }

                public List<Visit> getVisits() {
                    return visits;
                }
            %s}
            """;

    private static final String NEW_METHOD = """

                public LocalDate getLastVisitDate() {
                    return visits.isEmpty() ? null : visits.get(0).getDate();
                }

                private int count() {
                    return visits.size();
                }
            """;

    @TempDir
    Path mount;

    private void git(Path directory, String... arguments) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-c", "user.email=t@example.com",
                "-c", "user.name=t"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        process.waitFor();
    }

    private Path committedProject() throws IOException, InterruptedException {
        Path app = mount.resolve("app");
        Path source = Files.createDirectories(app.resolve("src/main/java/demo")).resolve("Pet.java");
        Files.writeString(source, String.format(PET, ""));
        Files.createDirectories(app.resolve("src/test/java/demo"));
        git(app, "init", "-q");
        git(app, "add", "-A");
        git(app, "commit", "-q", "-m", "seed");
        return app;
    }

    @Test
    @DisplayName("a public method no changed test calls is named; private methods and constructors are not")
    void namesAnUntestedNewMethod() throws IOException, InterruptedException {
        Path app = committedProject();
        Files.writeString(app.resolve("src/main/java/demo/Pet.java"), String.format(PET, NEW_METHOD));

        List<String> untested = new UntestedMethods(new SystemProcessRunner()).in(mount, "app");

        assertEquals(List.of("src/main/java/demo/Pet.java: getLastVisitDate"), untested);
    }

    @Test
    @DisplayName("a changed test that calls it is enough")
    void aChangedTestThatCallsItCoversIt() throws IOException, InterruptedException {
        Path app = committedProject();
        Files.writeString(app.resolve("src/main/java/demo/Pet.java"), String.format(PET, NEW_METHOD));
        Files.writeString(app.resolve("src/test/java/demo/PetTests.java"),
                "class PetTests { void t() { new Pet(List.of()).getLastVisitDate(); } }");

        assertEquals(List.of(), new UntestedMethods(new SystemProcessRunner()).in(mount, "app"));
    }

    @Test
    @DisplayName("outside a repository nothing can be checked, and nothing is blocked")
    void checksNothingWithoutARepository() throws IOException {
        Files.writeString(Files.createDirectories(mount.resolve("loose/src/main/java")).resolve("A.java"),
                "class A { public int a() { return 1; } }");

        assertEquals(List.of(), new UntestedMethods(new SystemProcessRunner()).in(mount, "loose"));
    }

    @Test
    void readsDeclarationsNotCallsOrConstructors() {
        assertEquals(Set.of("getVisits"), UntestedMethods.declared(String.format(PET, "")));
        assertEquals(Set.of(), UntestedMethods.declared("interface R { List<Pet> findAll(); }"));
    }
}
