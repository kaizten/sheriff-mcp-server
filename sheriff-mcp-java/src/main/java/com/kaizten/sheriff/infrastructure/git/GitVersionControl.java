package com.kaizten.sheriff.infrastructure.git;

import com.kaizten.sheriff.domain.port.VersionControl;
import com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The loop's safety net, on top of the {@code git} command line.
 *
 * <p>The important methods here are not the branching or the committing, they
 * are {@link #modifiedFiles()} and {@link #renamedFrom()}: together they are
 * how the loop finds out whether a fixer respected the scope it was given,
 * without mistaking a clean rename of an allowed file for an unrelated one
 * appearing out of nowhere. Everything else exists so that a run can be
 * inspected, or thrown away, one iteration at a time.
 *
 * <p>Two directories matter here, and they are often not the same one.
 * Sheriff analyzes a subdirectory of what it mounts and names every file
 * relative to that mount; git names every file relative to the root of the
 * repository. When one repository holds several components the two coincide.
 * When the repository <em>is</em> the component, the mount is its parent,
 * which is no repository at all, and every git command run there used to fail
 * without a word: no branch, no commits, and a scope check that saw nothing
 * change. So git runs from inside the component, finds the repository's root
 * for itself, and every path it reports is translated into the mount's terms
 * before the loop compares it with what Sheriff flagged.
 *
 * <p>A git command that fails is an error, never a quiet {@code false}. The
 * whole point of this class is to be the thing that notices; a branch that
 * was never created, or a commit a hook rejected, must stop the run rather
 * than let the next pass be judged against the wrong baseline.
 */
public final class GitVersionControl implements VersionControl {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String GIT = "git";
    private static final String SHOW = "show";
    private static final String HEAD_REVISION = "HEAD:";
    private static final String NO_CONTENT = "";
    private static final String STATUS = "status";
    private static final String CHECKOUT = "checkout";
    private static final String NEW_BRANCH = "-b";
    private static final String ADD = "add";
    private static final String ALL = "-A";
    private static final String COMMIT = "commit";
    private static final String MESSAGE = "-m";
    private static final String REV_PARSE = "rev-parse";
    private static final String SHOW_TOPLEVEL = "--show-toplevel";
    private static final String STATUS_PORCELAIN = "--porcelain";
    private static final String NUL_TERMINATED = "-z";
    private static final String EVERY_UNTRACKED_FILE = "--untracked-files=all";
    private static final String BRANCH_FORMAT = "%s/%s";
    private static final String ENTRY_SEPARATOR = "\0";
    private static final String NOT_RENAMED = "";
    private static final String UNTRACKED = "?? ";
    private static final char RENAMED = 'R';
    private static final char COPIED = 'C';
    private static final int STATUS_PREFIX_LENGTH = 3;
    private static final int STATUS_CODE_LENGTH = 2;
    private static final int NOT_FOUND = -1;
    private static final int DIRTY_TREE_SHOWN = 20;
    private static final String DIRTY_TREE_HEADING =
            "There are uncommitted changes in the repository:%n".formatted();
    private static final String DIRTY_TREE_ENTRY = "  %s%n";
    private static final String DIRTY_TREE_MORE = "  ... and %d more%n";
    private static final String DIRTY_TREE_ADVICE =
            "Commit or stash that work (git commit or git stash) before launching the agent, "
            + "so its commits don't get mixed up with yours. If those are build artifacts, add "
            + "them to the target repository's .gitignore -- the verification step runs a real "
            + "build and will keep recreating them.";
    private static final String NOT_A_REPOSITORY =
            "Git safety is on, but %s is not inside a git repository (%s). Run the agent on a "
            + "repository, or set SHERIFF_AGENT_GIT_SAFETY=0 to run without a branch, commits or "
            + "a scope check.";
    private static final String GIT_FAILED = "git %s failed in %s: %s";
    private static final String EXIT_CODE = "exit code %d";
    private static final List<String> STATUS_COMMAND =
            List.of(GIT, STATUS, STATUS_PORCELAIN, NUL_TERMINATED, EVERY_UNTRACKED_FILE);
    private static final List<String> STAGE_COMMAND = List.of(GIT, ADD, ALL);
    private static final List<String> TOPLEVEL_COMMAND = List.of(GIT, REV_PARSE, SHOW_TOPLEVEL);

    private final ProcessRunner processes;
    private final Path mountRoot;
    private final Path workingDirectory;
    private final String branchTimestamp;
    private Path repositoryRoot;
    private Path realMountRoot;

    /**
     * Wires version control to a repository whose root is also the directory
     * Sheriff mounts, so paths need no translating.
     *
     * @param processes how to run git
     * @param repositoryRoot the repository to operate on
     * @param branchTimestamp what to suffix a working branch with, so two runs
     *     on the same day do not collide
     */
    public GitVersionControl(ProcessRunner processes, Path repositoryRoot, String branchTimestamp) {
        this.processes = processes;
        this.mountRoot = repositoryRoot;
        this.workingDirectory = repositoryRoot;
        this.branchTimestamp = branchTimestamp;
        this.repositoryRoot = repositoryRoot;
        this.realMountRoot = repositoryRoot;
    }

    /**
     * Wires version control to whatever repository holds a component, found
     * from inside it.
     *
     * @param processes how to run git
     * @param mountRoot the directory Sheriff mounts, which every path this
     *     reports is relative to
     * @param workingDirectory where git runs: the component itself, which is
     *     inside the repository even when the mount is not
     * @param branchTimestamp what to suffix a working branch with
     */
    public GitVersionControl(ProcessRunner processes, Path mountRoot, Path workingDirectory, String branchTimestamp) {
        this.processes = processes;
        this.mountRoot = mountRoot;
        this.workingDirectory = workingDirectory;
        this.branchTimestamp = branchTimestamp;
    }

    /**
     * Whether the working tree has anything the loop did not commit yet.
     *
     * @return {@code true} when something is uncommitted
     */
    @Override
    public boolean hasUncommittedChanges() {
        return !status().isEmpty();
    }

    /**
     * Moves onto a branch of this run's own, so a whole run can be thrown away
     * in one step.
     *
     * <p>Refuses to start on a dirty tree, and the refusal names what is
     * dirty. Without that guard the run starts anyway and the scope check
     * blames the fixer for whatever was already there: an untracked file left
     * beside the repository aborted a real run and threw away work the fixer
     * had correctly done. Half the time it is not the user's work at all but
     * build output the target project does not ignore, often left by this
     * agent's own verification step, so naming the files turns an accusation
     * into something obvious at a glance.
     *
     * @param prefix what to name the branch after
     * @return the branch that was created
     * @throws IllegalStateException when the working tree is not clean, when
     *     there is no repository, or when git refuses to create the branch
     */
    @Override
    public String createWorkingBranch(String prefix) {
        List<String> leftover = untracked().stream().filter(GitVersionControl::isSheriffState).toList();
        List<String> dirty = modifiedFiles().stream().filter(path -> !leftover.contains(path)).toList();
        if (!dirty.isEmpty()) {
            throw new IllegalStateException(dirtyTreeMessage(dirty));
        }
        String branch = String.format(BRANCH_FORMAT, prefix, branchTimestamp);
        required(List.of(GIT, CHECKOUT, NEW_BRANCH, branch), CHECKOUT);
        return branch;
    }

    /**
     * Whether a path git reports is the state Sheriff leaves at the root of
     * what it mounts, which is never anyone's work.
     *
     * <p>An analysis cut short, as when a client stops the server mid-task,
     * leaves those files behind, and every later run refused to start and
     * advised committing them. They need no commit: the run's first analysis
     * clears Sheriff's state before it starts, so they are gone before the
     * first commit of the run.
     *
     * <p>Only untracked ones: a state file someone committed is theirs, and
     * the run's clean-up deleting it would be committed under the agent's
     * name.
     *
     * @param path a path git reports, relative to the mount
     * @return {@code true} for one of Sheriff's state files at the mount's root
     */
    private static boolean isSheriffState(String path) {
        return SheriffDockerAnalyzer.STATE_FILES.contains(path);
    }

    /**
     * Every file git reports as untracked.
     *
     * @return their paths, relative to the mount
     */
    private List<String> untracked() {
        Path root = repositoryRoot();
        String output = required(STATUS_COMMAND, STATUS).standardOutput();
        List<String> untracked = new ArrayList<>();
        for (String field : output.split(ENTRY_SEPARATOR)) {
            if (field.startsWith(UNTRACKED) && field.length() > STATUS_PREFIX_LENGTH) {
                untracked.add(mountRelative(root, field.substring(STATUS_PREFIX_LENGTH)));
            }
        }
        return untracked;
    }

    /**
     * What to say when the tree is not clean.
     *
     * @param dirty everything git reports as changed
     * @return the message, naming the files
     */
    private static String dirtyTreeMessage(List<String> dirty) {
        StringBuilder message = new StringBuilder(DIRTY_TREE_HEADING);
        for (String path : dirty.subList(0, Math.min(dirty.size(), DIRTY_TREE_SHOWN))) {
            message.append(String.format(DIRTY_TREE_ENTRY, path));
        }
        if (dirty.size() > DIRTY_TREE_SHOWN) {
            message.append(String.format(DIRTY_TREE_MORE, dirty.size() - DIRTY_TREE_SHOWN));
        }
        return message.append(DIRTY_TREE_ADVICE).toString();
    }

    /**
     * Commits whatever a pass left behind, so each iteration is inspectable on
     * its own.
     *
     * @param message the commit message
     * @return {@code true} when a commit was made, {@code false} when there
     *     was nothing to commit
     * @throws IllegalStateException when git refuses the commit, for instance
     *     because no identity is configured or a pre-commit hook rejected it;
     *     carrying on would judge the next pass against a stale baseline
     */
    @Override
    public boolean commitIfChanges(String message) {
        if (!hasUncommittedChanges()) {
            return false;
        }
        required(STAGE_COMMAND, ADD);
        required(List.of(GIT, COMMIT, MESSAGE, message), COMMIT);
        return true;
    }

    /**
     * Every file git reports as touched, modified or untracked.
     *
     * @return their paths, relative to the directory Sheriff mounts; a file
     *     outside that directory starts with {@code ..}, which no finding ever
     *     names, so it can never pass for one that was allowed
     */
    @Override
    public List<String> modifiedFiles() {
        return new ArrayList<>(status().keySet());
    }

    /**
     * Files git reports as renamed, mapped back to the name they had before.
     *
     * @return the new name mapped to the old one, both relative to the mount
     */
    @Override
    public Map<String, String> renamedFrom() {
        Map<String, String> renames = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : status().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                renames.put(entry.getKey(), entry.getValue());
            }
        }
        return renames;
    }

    /**
     * Every file git reports as touched, as an absolute path.
     *
     * <p>For the Stop hook, which works out which components a turn touched
     * and needs paths it can place, not names it can compare.
     *
     * @return those paths
     */
    public List<Path> changedPaths() {
        List<Path> paths = new ArrayList<>();
        for (String file : status().keySet()) {
            paths.add(mountRoot.resolve(file).normalize());
        }
        return paths;
    }

    /**
     * A file as the last commit has it.
     *
     * @param file the file, inside the repository
     * @return its content at {@code HEAD}, or the empty string when the
     *     last commit does not hold it, or there is no commit yet
     */
    public String committedContent(Path file) {
        Path root = repositoryRoot();
        Path relative = realPathOf(root).relativize(realPathOf(file));
        ProcessOutcome outcome = git(List.of(GIT, SHOW, HEAD_REVISION + Platform.slashes(relative)));
        return outcome.succeeded() ? outcome.standardOutput() : NO_CONTENT;
    }

    /**
     * What git reports as changed, in the mount's terms.
     *
     * <p>Read with {@code -z}, which neither quotes nor escapes a name: a
     * file with an accent in it would otherwise come back as octal escapes
     * that no finding matches, and be judged out of scope. Untracked files
     * are listed one by one rather than folded into their directory, so a new
     * source file is named as the file it is.
     *
     * @return each changed path mapped to the path it was renamed from, or to
     *     the empty string when it was not renamed
     */
    private Map<String, String> status() {
        Path root = repositoryRoot();
        String output = required(STATUS_COMMAND, STATUS).standardOutput();
        Map<String, String> entries = new LinkedHashMap<>();
        Iterator<String> fields = List.of(output.split(ENTRY_SEPARATOR)).iterator();
        while (fields.hasNext()) {
            String field = fields.next();
            if (field.length() <= STATUS_PREFIX_LENGTH) {
                continue;
            }
            String original = carriesOriginal(field) && fields.hasNext() ? fields.next() : NOT_RENAMED;
            String renamedFrom = isRename(field) ? mountRelative(root, original) : NOT_RENAMED;
            entries.put(mountRelative(root, field.substring(STATUS_PREFIX_LENGTH)), renamedFrom);
        }
        return entries;
    }

    /**
     * Whether a status entry is followed by the path it came from.
     *
     * @param field one entry of {@code status -z}
     * @return {@code true} for a rename or a copy
     */
    private static boolean carriesOriginal(String field) {
        return isRename(field) || hasCode(field, COPIED);
    }

    /**
     * Whether a status entry is a rename. A copy is not: it leaves the
     * original in place and adds a file nobody allowed.
     *
     * @param field one entry of {@code status -z}
     * @return {@code true} for a rename
     */
    private static boolean isRename(String field) {
        return hasCode(field, RENAMED);
    }

    /**
     * Whether either status column of an entry holds a code.
     *
     * @param field one entry of {@code status -z}
     * @param code the status letter to look for
     * @return {@code true} when the index or the worktree column holds it
     */
    private static boolean hasCode(String field, char code) {
        return field.substring(0, STATUS_CODE_LENGTH).indexOf(code) != NOT_FOUND;
    }

    /**
     * Turns a path relative to the repository's root into one relative to the
     * mount.
     *
     * @param root the repository's root
     * @param entry the path as git reported it
     * @return the same file, named the way Sheriff names it
     */
    private String mountRelative(Path root, String entry) {
        return Platform.slashes(realMountRoot.relativize(root.resolve(entry).normalize()));
    }

    /**
     * The root of the repository, found the first time it is needed.
     *
     * @return that root
     * @throws IllegalStateException when the working directory is not inside
     *     a repository at all
     */
    private Path repositoryRoot() {
        if (repositoryRoot == null) {
            ProcessOutcome outcome = git(TOPLEVEL_COMMAND);
            if (!outcome.succeeded()) {
                throw new IllegalStateException(String.format(NOT_A_REPOSITORY, workingDirectory, reasonOf(outcome)));
            }
            repositoryRoot = Path.of(outcome.standardOutput().strip());
            realMountRoot = realPathOf(mountRoot);
        }
        return repositoryRoot;
    }

    /**
     * A directory with its symbolic links resolved, the way git reports the
     * repository's root, so one can be made relative to the other.
     *
     * @param directory the directory to resolve
     * @return its real path, or its absolute one when it cannot be resolved
     */
    private static Path realPathOf(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException exception) {
            return directory.toAbsolutePath().normalize();
        }
    }

    /**
     * Runs a git command that must succeed.
     *
     * @param command the command line
     * @param name what to call it in the failure
     * @return what it produced
     * @throws IllegalStateException when it fails, naming git's own reason
     */
    private ProcessOutcome required(List<String> command, String name) {
        ProcessOutcome outcome = git(command);
        if (!outcome.succeeded()) {
            throw new IllegalStateException(String.format(GIT_FAILED, name, workingDirectory, reasonOf(outcome)));
        }
        return outcome;
    }

    /**
     * Why a git command failed, in git's own words where it gave any.
     *
     * @param outcome what the command produced
     * @return the reason
     */
    private static String reasonOf(ProcessOutcome outcome) {
        if (!outcome.ran()) {
            return outcome.failure();
        }
        String error = outcome.standardError().strip();
        if (!error.isEmpty()) {
            return error;
        }
        String output = outcome.standardOutput().strip();
        return output.isEmpty() ? String.format(EXIT_CODE, outcome.exitCode()) : output;
    }

    /**
     * Runs one git command from inside the component.
     *
     * @param command the command line
     * @return what it produced
     */
    private ProcessOutcome git(List<String> command) {
        return processes.run(command, workingDirectory, Map.of(), TIMEOUT);
    }
}
