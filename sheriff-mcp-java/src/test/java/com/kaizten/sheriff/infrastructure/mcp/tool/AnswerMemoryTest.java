package com.kaizten.sheriff.infrastructure.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a session remembers between the answers it gives.
 */
class AnswerMemoryTest {

    @Test
    @DisplayName("the files a repair changed are kept per component until they are forgotten with none")
    void theFilesARepairChangedAreKept() {
        AnswerMemory memory = new AnswerMemory();
        assertEquals(List.of(), memory.repairChanged("app"), "nothing before any repair");

        List<String> changed = new ArrayList<>(List.of("app/pom.xml"));
        memory.repairChanged("app", changed);
        changed.add("app/A.java");

        assertEquals(List.of("app/pom.xml"), memory.repairChanged("app"), "a copy, not the caller's list");
        assertEquals(List.of(), memory.repairChanged("other"));
        memory.repairChanged("app", List.of());
        assertEquals(List.of(), memory.repairChanged("app"));
    }

    @Test
    @DisplayName("a list of files with a null in it is refused, not kept")
    void aNullFileIsRefused() {
        AnswerMemory memory = new AnswerMemory();
        List<String> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> memory.repairChanged("app", withNull));
        assertEquals(List.of(), memory.repairChanged("app"));
    }
}
