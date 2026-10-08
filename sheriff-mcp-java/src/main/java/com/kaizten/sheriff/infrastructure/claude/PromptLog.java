package com.kaizten.sheriff.infrastructure.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Saves every prompt sent to a fixer, and what came back, to its own file.
 *
 * <p>A pure audit trail with no effect on behaviour: when a run does something
 * surprising, the question is always "what was it actually asked?", and the
 * answer should not depend on scrollback. Writing it is best effort — losing
 * the log is not a reason to fail a fix that worked.
 */
public final class PromptLog {

    private static final String STAMP_PATTERN = "yyyyMMdd-HHmmss-SSSSSS";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern(STAMP_PATTERN);
    private static final String FILE_FORMAT = "%s_%s.txt";
    private static final String CONTENT_FORMAT = "=== PROMPT ===%n%s%n%n=== RESPONSE ===%n%s%n";

    private final Path directory;

    /**
     * Wires the log to a directory.
     *
     * @param directory where to write, or {@code null} to disable logging
     */
    public PromptLog(Path directory) {
        this.directory = directory;
    }

    /**
     * Records one exchange.
     *
     * @param label what to call it, such as "iteration-3" or "repair"
     * @param prompt what was sent
     * @param response what came back
     * @return the file written, or {@code null} when logging is off or failed
     */
    public Path record(String label, String prompt, String response) {
        if (directory == null) {
            return null;
        }
        Path file = directory.resolve(String.format(FILE_FORMAT, LocalDateTime.now().format(STAMP), label));
        try {
            Files.createDirectories(directory);
            Files.writeString(file, String.format(CONTENT_FORMAT, prompt, response), StandardCharsets.UTF_8);
            return file;
        } catch (IOException exception) {
            return null;
        }
    }
}
