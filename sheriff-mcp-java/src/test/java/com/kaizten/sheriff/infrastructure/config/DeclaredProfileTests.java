package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a project declares its profile, and which declaration wins.
 */
class DeclaredProfileTests {

    @TempDir
    Path project;

    private static String pom(String properties) {
        return "<project><modelVersion>4.0.0</modelVersion><properties>" + properties + "</properties></project>";
    }

    @Test
    @DisplayName("the sheriff.profile property of a pom.xml, the one the Maven plugin takes")
    void readsThePomProperty() throws IOException {
        Files.writeString(project.resolve("pom.xml"), pom("<sheriff.profile> JAVA_HEXAGONAL </sheriff.profile>"));
        assertEquals(Optional.of("JAVA_HEXAGONAL"), DeclaredProfile.in(List.of(project)));
    }

    @Test
    @DisplayName("profile in .sheriff.properties, for a project that does not build with Maven")
    void readsThePropertiesFile() throws IOException {
        Files.writeString(project.resolve(".sheriff.properties"), "# rules\nprofile=TYPESCRIPT_HEXAGONAL\n");
        assertEquals(Optional.of("TYPESCRIPT_HEXAGONAL"), DeclaredProfile.in(List.of(project)));
    }

    @Test
    @DisplayName("a property inside a Maven build profile is not the project's: it applies only when that is active")
    void ignoresBuildProfiles() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project><profiles><profile><properties>"
                + "<sheriff.profile>JAVA_DDD</sheriff.profile></properties></profile></profiles></project>");
        assertEquals(Optional.empty(), DeclaredProfile.in(List.of(project)));
    }

    @Test
    @DisplayName("a Maven module inherits from the pom.xml above it, before any file; one that is not inherits nothing")
    void nearestFirstAndPomsBeforeFiles() throws IOException {
        Path module = Files.createDirectories(project.resolve("module"));
        Files.writeString(project.resolve("pom.xml"), pom("<sheriff.profile>JAVA_HEXAGONAL</sheriff.profile>"));
        Files.writeString(module.resolve(".sheriff.properties"), "profile=JAVA_DDD\n");
        assertEquals(Optional.of("JAVA_DDD"), DeclaredProfile.in(List.of(module, project)));
        Files.writeString(module.resolve("pom.xml"), pom(""));
        assertEquals(Optional.of("JAVA_HEXAGONAL"), DeclaredProfile.in(List.of(module, project)));
        Files.writeString(module.resolve("pom.xml"), pom("<sheriff.profile>JAVA</sheriff.profile>"));
        assertEquals(Optional.of("JAVA"), DeclaredProfile.in(List.of(module, project)));
    }

    @Test
    @DisplayName("nothing usable is nothing declared: blank, a Maven expression, a broken pom, a document type")
    void unusableDeclarationsAreIgnored() throws IOException {
        Path blank = Files.createDirectories(project.resolve("blank"));
        Files.writeString(blank.resolve("pom.xml"), pom("<sheriff.profile>  </sheriff.profile>"));
        Path expression = Files.createDirectories(project.resolve("expression"));
        Files.writeString(expression.resolve("pom.xml"), pom("<sheriff.profile>${arch}</sheriff.profile>"));
        Path broken = Files.createDirectories(project.resolve("broken"));
        Files.writeString(broken.resolve("pom.xml"), "<project><properties>");
        Path doctype = Files.createDirectories(project.resolve("doctype"));
        Files.writeString(doctype.resolve("pom.xml"), "<!DOCTYPE project [<!ENTITY x SYSTEM \"file:///etc/hostname\">]>"
                + pom("<sheriff.profile>&x;</sheriff.profile>"));
        assertEquals(Optional.empty(), DeclaredProfile.in(List.of(blank, expression, broken, doctype)));
    }

    @Test
    @DisplayName("a broken pom says nothing on stderr: the parser's [Fatal Error] read as a failed hook or install")
    void brokenPomsAreSilent() throws IOException {
        Files.writeString(project.resolve("pom.xml"), "<project><properties>");
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            assertEquals(Optional.empty(), DeclaredProfile.in(List.of(project)));
        } finally {
            System.setErr(original);
        }
        assertEquals("", captured.toString(StandardCharsets.UTF_8));
    }
}
