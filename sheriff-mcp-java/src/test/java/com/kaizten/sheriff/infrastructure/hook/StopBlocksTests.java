package com.kaizten.sheriff.infrastructure.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Stop hook's record of blocks in a row, read back as it was written.
 */
class StopBlocksTests {

    @TempDir
    Path folder;

    private void record(String session, String text) throws IOException, NoSuchAlgorithmException {
        String name = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(session.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(folder.resolve(name), text);
    }

    @Test
    @DisplayName("stalls in a row are counted, and a block with a change puts the count back to none")
    void stallsAreCounted() {
        StopBlocks blocks = new StopBlocks(folder);
        blocks.recordBlock("s", "a", true);
        blocks.recordBlock("s", "a", true);
        assertEquals(2, blocks.stalls("s"));
        blocks.recordBlock("s", "b", false);
        assertEquals(0, blocks.stalls("s"));
        assertEquals(3, blocks.count("s"));
    }

    @Test
    @DisplayName("a record written before stalls were counted reads as one, and one that is no number as none")
    void olderRecordsAreRead() throws IOException, NoSuchAlgorithmException {
        StopBlocks blocks = new StopBlocks(folder);
        record("old", "1\na\nstalled");
        record("odd", "1\na\nsome");
        assertEquals(1, blocks.stalls("old"));
        assertEquals(0, blocks.stalls("odd"));
        assertEquals(0, blocks.stalls(""), "a session the payload does not name has no record");
    }
}
