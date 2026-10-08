package com.kaizten.sheriff.infrastructure.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The two file operations the API backend offers the model, and the rules
 * about what it may touch.
 *
 * <p>This is the one capability the CLI backend structurally cannot have.
 * Claude Code brings its own editing tools, so a file-scope restriction there
 * is advice in a prompt, checked afterwards through version control. Here the
 * write tool belongs to this agent, so an out-of-scope write can be refused
 * before it happens rather than detected after.
 *
 * <p>Two refusals, not one. A write outside the pass's scope is the expected
 * case and the model is told why, so it can correct itself. A path that escapes
 * the repository altogether is refused too — the Python original never checked
 * that, and a tool that can be talked into writing anywhere on the filesystem
 * is a different class of problem from one that edits the wrong source file.
 */
public final class FileTools {

    private static final String OUT_OF_SCOPE =
            "Refused: '%s' is not one of the files this pass may modify. Allowed: %s";
    private static final String OUTSIDE_REPOSITORY = "Refused: '%s' is outside the repository.";
    private static final String UNREADABLE = "Could not read '%s': %s";
    private static final String UNWRITABLE = "Could not write '%s': %s";
    private static final String WRITTEN = "Wrote %d characters to '%s'.";
    private static final String NO_CONTENT =
            "Refused: the write to '%s' carried no 'content', so nothing was written. Send the file's "
            + "complete new contents in 'content'.";
    private static final String SEPARATOR = ", ";

    private final Path repositoryRoot;
    private final Set<String> allowedFiles;
    private final List<String> written = new ArrayList<>();

    /**
     * Wires the tools to one pass.
     *
     * @param repositoryRoot the repository the model may work in
     * @param allowedFiles the only files it may modify, or {@code null} for the
     *     unrestricted repair pass
     */
    public FileTools(Path repositoryRoot, Set<String> allowedFiles) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.allowedFiles = allowedFiles;
    }

    /**
     * Reads a file for the model.
     *
     * <p>Reading is not restricted to the pass's scope: understanding a caller
     * before renaming the method it calls is exactly the kind of thing that
     * stops a fix from breaking the build.
     *
     * @param relativePath the path, relative to the repository root
     * @return the file's contents, or why they could not be read
     */
    public String readFile(String relativePath) {
        Path target = resolve(relativePath);
        if (target == null) {
            return String.format(OUTSIDE_REPOSITORY, relativePath);
        }
        try {
            return Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return String.format(UNREADABLE, relativePath, exception.getMessage());
        }
    }

    /**
     * Writes a file for the model, if it is allowed to.
     *
     * <p>A call with no content at all is refused rather than read as "empty
     * the file". That is what a reply cut off mid-call, or a model that forgot
     * the argument, looks like from here, and taking it literally overwrote
     * a source file with nothing.
     *
     * @param relativePath the path, relative to the repository root
     * @param content what to write, or {@code null} when the call carried none
     * @return what happened, phrased for the model to read and act on
     */
    public String writeFile(String relativePath, String content) {
        Path target = resolve(relativePath);
        if (target == null) {
            return String.format(OUTSIDE_REPOSITORY, relativePath);
        }
        if (!isAllowed(relativePath)) {
            return String.format(OUT_OF_SCOPE, relativePath, String.join(SEPARATOR, allowedFiles));
        }
        if (content == null) {
            return String.format(NO_CONTENT, relativePath);
        }
        try {
            Files.writeString(target, content, StandardCharsets.UTF_8);
            written.add(relativePath);
            return String.format(WRITTEN, content.length(), relativePath);
        } catch (IOException exception) {
            return String.format(UNWRITABLE, relativePath, exception.getMessage());
        }
    }

    /**
     * Which files the model actually changed.
     *
     * @return those paths, in the order they were written
     */
    public List<String> written() {
        return List.copyOf(written);
    }

    /**
     * Whether a path is within what this pass may modify.
     *
     * @param relativePath the path the model asked to write
     * @return {@code true} when the write may proceed
     */
    boolean isAllowed(String relativePath) {
        return allowedFiles == null || allowedFiles.contains(relativePath);
    }

    /**
     * Turns a path from the model into a real one, refusing anything that
     * leaves the repository — including by way of {@code ..} or a symlink.
     *
     * @param relativePath the path the model supplied
     * @return the resolved path, or {@code null} when it escapes
     */
    Path resolve(String relativePath) {
        Path candidate = repositoryRoot.resolve(relativePath).normalize();
        return candidate.startsWith(repositoryRoot) ? candidate : null;
    }
}
