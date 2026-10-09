package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The findings Sheriff gets wrong.
 */
class KnownFalsePositivesTests {

    private static SheriffFinding finding(String file, String code) {
        return new SheriffFinding(file, "description", "how", code, SheriffFinding.ERROR, Map.of());
    }

    @Test
    @DisplayName("package-info.java and module-info.java are names Java requires, but the rule still holds elsewhere")
    void dropsTheFileNamesJavaRequires() {
        SheriffFinding unsorted = finding("app/src/A.java", "SortedImport");
        SheriffFinding snakeCase = finding("app/src/my_class.java", "FilenamePascalCase");
        assertEquals(List.of(unsorted, snakeCase), KnownFalsePositives.without(List.of(unsorted,
                finding("app/src/package-info.java", "FilenamePascalCase"),
                finding("app/src/module-info.java", "FilenamePascalCase"), snakeCase)));
    }
}
