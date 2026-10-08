package com.kaizten.sheriff.infrastructure.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * the workspace altogether is refused too, for reading as much as for writing
 * — the Python original never checked that, and a tool that can be talked
 * into writing anywhere on the filesystem is a different class of problem
 * from one that edits the wrong source file.
 *
 * <p>The workspace is the component, not the repository the paths are
 * relative to. For a project that is a single module, that repository is the
 * folder above it, which holds the user's other projects too: the model
 * could read any of them, {@code .env} files included, and send them to its
 * provider, and the repair pass, which has no scope, could write into them
 * where no {@code git status} of the component would ever show it. The other
 * backends were already kept to the component. A path is checked where it
 * really leads, so a symbolic link inside the component that points out of
 * it is refused like {@code ..} is.
 */
public final class FileTools {

    private static final String OUT_OF_SCOPE =
            "Refused: '%s' is not one of the files this pass may modify. Allowed: %s";
    private static final String OUTSIDE_REPOSITORY = "Refused: '%s' is outside %s, the folder this pass works in.";
    private static final String UNREADABLE = "Could not read '%s': %s";
    private static final String UNWRITABLE = "Could not write '%s': %s";
    private static final String WRITTEN = "Wrote %d characters to '%s'.";
    private static final String NO_CONTENT =
            "Refused: the write to '%s' carried no 'content', so nothing was written. Send the file's "
            + "complete new contents in 'content'.";
    private static final String SEPARATOR = ", ";
    private static final String REPOSITORY_NAME = "the repository";
    private static final String QUOTED = "'%s'";

    private final Path repositoryRoot;
    private final Path workspace;
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
        this(repositoryRoot, repositoryRoot, allowedFiles);
    }

    /**
     * Wires the tools to one pass, kept to one folder of the repository.
     *
     * @param repositoryRoot the directory the model's paths are relative to,
     *     the one Sheriff mounts
     * @param workspace the only folder the model may read or write in: the
     *     component
     * @param allowedFiles the only files it may modify, or {@code null} for
     *     the unrestricted repair pass
     */
    public FileTools(Path repositoryRoot, Path workspace, Set<String> allowedFiles) {
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.workspace = workspace.toAbsolutePath().normalize();
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
            return String.format(OUTSIDE_REPOSITORY, relativePath, workspaceName());
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
            return String.format(OUTSIDE_REPOSITORY, relativePath, workspaceName());
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
     * leaves the workspace — by way of {@code ..}, an absolute path, or a
     * symbolic link that leads out of it.
     *
     * @param relativePath the path the model supplied
     * @return the resolved path, or {@code null} when it escapes
     */
    Path resolve(String relativePath) {
        Path candidate = repositoryRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(workspace)) {
            return null;
        }
        Optional<Path> leadsTo = realPathOf(candidate);
        Optional<Path> realWorkspace = realPathOf(workspace);
        if (leadsTo.isEmpty() || realWorkspace.isEmpty() || !leadsTo.get().startsWith(realWorkspace.get())) {
            return null;
        }
        return candidate;
    }

    /**
     * Where a path really leads: its deepest part that exists, with every
     * link in it followed, and the rest as written.
     *
     * <p>A path that does not exist yet is a file about to be written; what
     * matters is where its folder leads. A link that leads nowhere is refused
     * rather than guessed at, since writing through it would create its
     * target, wherever that is.
     *
     * @param path a normalized, absolute path
     * @return where it leads, or empty when that cannot be told
     */
    private static Optional<Path> realPathOf(Path path) {
        Path existing = path;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(existing.toRealPath().resolve(existing.relativize(path)));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * The workspace, as a refusal names it to the model: relative to the
     * repository its paths are written against.
     *
     * @return that name
     */
    private String workspaceName() {
        Path relative = repositoryRoot.relativize(workspace);
        return relative.toString().isEmpty() ? REPOSITORY_NAME : String.format(QUOTED, relative);
    }
}
