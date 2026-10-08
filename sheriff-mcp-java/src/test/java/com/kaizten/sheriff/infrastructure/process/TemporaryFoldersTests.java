package com.kaizten.sheriff.infrastructure.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The folders the hooks and the locks keep their files in: one per user, so
 * no user can take another's, and pruned, so they do not grow for good.
 */
class TemporaryFoldersTests {

    @TempDir
    private Path folder;

    @Test
    @DisplayName("each user gets a folder of their own, named so any user name makes a valid one")
    void eachUserGetsAFolderOfTheirOwn() {
        Path temporary = Path.of(System.getProperty("java.io.tmpdir"));

        assertEquals(temporary.resolve("sheriff-gate-ana"), TemporaryFolders.forUser("sheriff-gate", "ana"));
        assertEquals(temporary.resolve("sheriff-gate-DOMAIN_ana_l_pez"),
                TemporaryFolders.forUser("sheriff-gate", "DOMAIN\\ana l/pez"));
        assertEquals(temporary.resolve("sheriff-gate-user"), TemporaryFolders.forUser("sheriff-gate", " "));
        assertEquals(temporary.resolve("sheriff-gate-user"), TemporaryFolders.forUser("sheriff-gate", null));
        assertEquals(TemporaryFolders.forUser("sheriff-locks", System.getProperty("user.name")),
                TemporaryFolders.forUser("sheriff-locks"));
    }

    @Test
    @DisplayName("pruning removes the files older than their lifetime, and nothing else")
    void pruningRemovesOnlyWhatIsOld() throws Exception {
        Path old = Files.writeString(folder.resolve("old"), "");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(8))));
        Path recent = Files.writeString(folder.resolve("recent"), "");
        Path nested = Files.createDirectories(folder.resolve("nested"));
        Files.setLastModifiedTime(nested, FileTime.from(Instant.now().minus(Duration.ofDays(30))));

        TemporaryFolders.prune(folder, TemporaryFolders.MARKER_LIFETIME);

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(recent));
        assertTrue(Files.exists(nested), "only files are pruned, never a folder");
    }

    @Test
    @DisplayName("a folder that is not there yet is nothing to prune, not a failure")
    void pruningAMissingFolderDoesNothing() {
        TemporaryFolders.prune(folder.resolve("missing"), TemporaryFolders.MARKER_LIFETIME);

        assertFalse(Files.exists(folder.resolve("missing")));
    }
}
