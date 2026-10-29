package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The findings Sheriff gets wrong.
 */
class KnownFalsePositivesTests {

    private static final String SOURCE = """
            package demo;

            /**
             * A thing.
             */
            public class Thing {
            \tprivate int tabbed;
                private static final String USER_NAME = "user.name";
                private final String Shouting = "x";
                private static final String TEXT = \"""
                      indented by six
                    \""";

              private int misplaced;
            }
            """;

    @TempDir
    private Path mount;

    private static SheriffFinding finding(String file, String code) {
        return finding(file, code, "description");
    }

    private static SheriffFinding finding(String file, String code, String description) {
        return new SheriffFinding(file, description, "how", code, SheriffFinding.ERROR, Map.of());
    }

    private static SheriffFinding indentation(int line) {
        return finding("app/src/Thing.java", "FourSpaceIndentation",
                "Invalid indentation in Java file 'Thing.java' at line " + line + ".");
    }

    private static SheriffFinding variableName(int line) {
        return finding("app/src/Thing.java", "JavaVariableName",
                "Invalid variable name 'X' at line " + line + ": must not start with a capital letter");
    }

    private void writeSource() throws IOException {
        Files.createDirectories(mount.resolve("app/src"));
        Files.writeString(mount.resolve("app/src/Thing.java"), SOURCE);
    }

    @Test
    @DisplayName("package-info.java and module-info.java are names Java requires, but the rule still holds elsewhere")
    void dropsTheFileNamesJavaRequires() {
        SheriffFinding unsorted = finding("app/src/A.java", "SortedImport");
        SheriffFinding snakeCase = finding("app/src/my_class.java", "FilenamePascalCase");
        assertEquals(List.of(unsorted, snakeCase), KnownFalsePositives.without(List.of(unsorted,
                finding("app/src/package-info.java", "FilenamePascalCase"),
                finding("app/src/module-info.java", "FilenamePascalCase"), snakeCase), mount));
    }

    @Test
    @DisplayName("a comment's continuation and a text block's content are not indentation, a tab and a stray line are")
    void dropsTheIndentationOfCommentsAndTextBlocks() throws IOException {
        writeSource();
        assertEquals(List.of(indentation(7), indentation(14)), KnownFalsePositives.without(
                List.of(indentation(4), indentation(5), indentation(7), indentation(11), indentation(14)), mount));
    }

    @Test
    @DisplayName("a constant is named as Java names constants, a field is not")
    void dropsTheNamesOfConstants() throws IOException {
        writeSource();
        assertEquals(List.of(variableName(9)),
                KnownFalsePositives.without(List.of(variableName(8), variableName(9)), mount));
    }

    @Test
    @DisplayName("an import used as an annotation is used, one nothing names is not")
    void dropsTheImportsOfAnnotations() throws IOException {
        Files.createDirectories(mount.resolve("app/src"));
        Files.writeString(mount.resolve("app/src/Goal.java"), """
                package demo;

                import java.util.List;
                import org.apache.maven.plugins.annotations.Mojo;

                @Mojo(name = "check")
                public class Goal {
                }
                """);
        SheriffFinding annotation = finding("app/src/Goal.java", "UnusedImport",
                "Unused import 'org.apache.maven.plugins.annotations.Mojo' at line 4");
        SheriffFinding unused = finding("app/src/Goal.java", "UnusedImport", "Unused import 'java.util.List' at line 3");
        assertEquals(List.of(unused), KnownFalsePositives.without(List.of(annotation, unused), mount));
    }

    @Test
    @DisplayName("a finding whose line cannot be read counts")
    void keepsWhatItCannotRead() throws IOException {
        writeSource();
        SheriffFinding missing = finding("app/src/Gone.java", "FourSpaceIndentation", "at line 4.");
        SheriffFinding noLine = finding("app/src/Thing.java", "JavaVariableName", "Invalid variable name");
        SheriffFinding pastTheEnd = indentation(400);
        assertEquals(List.of(missing, noLine, pastTheEnd),
                KnownFalsePositives.without(List.of(missing, noLine, pastTheEnd), mount));
    }
}
