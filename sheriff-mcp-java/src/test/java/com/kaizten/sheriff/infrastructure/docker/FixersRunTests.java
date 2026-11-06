package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which fixers a {@code fix} ran, as its output says.
 */
class FixersRunTests {

    @Test
    @DisplayName("each fixer Sheriff selected, once, in order, and not the lines about findings or fixed files")
    void theFixersSelectedAreRead() {
        String output = """
                Fix summary: errors=3, files=1, different errors=3, available fixers=3
                Selected error: JavaIfStatementBracesMissing | hasScript=true
                Successfully fixed error 'JavaIfStatementBracesMissing' in file '/data/app/A.java' with output:
                Selected finding (ERROR): JavaIfStatementBracesMissing | hasScript=true
                  Selected error: MultipleEmptyLines | hasScript=true
                Selected error: JavaIfStatementBracesMissing | hasScript=true
                """;

        assertEquals(List.of("JavaIfStatementBracesMissing", "MultipleEmptyLines"), List.copyOf(FixersRun.in(output)));
    }

    @Test
    @DisplayName("an output that names no fixer, or none at all, ran none")
    void noOutputRanNone() {
        assertEquals(Set.of(), FixersRun.in(""));
        assertTrue(FixersRun.in("Fixing process completed.\nErrors fixed: 0\n").isEmpty());
    }
}
