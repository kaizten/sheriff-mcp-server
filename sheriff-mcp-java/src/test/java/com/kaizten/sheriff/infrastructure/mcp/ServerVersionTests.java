package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version and commit a build records, which tell two jars of one
 * version apart.
 */
class ServerVersionTests {

    @Test
    @DisplayName("the commit is git's, abbreviated, or unknown for a build with no repository")
    void theCommitIsAnAbbreviatedHashOrUnknown() {
        String commit = ServerVersion.commit();
        assertTrue(commit.equals("unknown") || commit.matches("[0-9a-f]{7,}"), commit);
    }

    @Test
    @DisplayName("the version is described with its commit when there is one, and alone when there is none")
    void theDescriptionNamesTheCommitWhenThereIsOne() {
        String commit = ServerVersion.commit();
        String expected = commit.equals("unknown") ? ServerVersion.current()
                : ServerVersion.current() + " (" + commit + ")";
        assertEquals(expected, ServerVersion.described());
    }
}
