package com.kaizten.sheriff.domain.port;

import java.util.List;
import java.util.Map;

/**
 * The fix loop's safety net: branch, commit, and above all inspect what
 * actually changed on disk after a fixer ran.
 */
public interface VersionControl {

    /**
     * Whether the working tree has changes the agent did not make.
     *
     * @return {@code true} when the tree is dirty
     */
    boolean hasUncommittedChanges();

    /**
     * Creates the dedicated branch a run works on.
     *
     * @param prefix the branch name prefix
     * @return the name of the branch that was created
     */
    String createWorkingBranch(String prefix);

    /**
     * Commits whatever the last pass changed.
     *
     * @param message the commit message
     * @return {@code true} when there was something to commit
     */
    boolean commitIfChanges(String message);

    /**
     * Which files have been modified since the last commit. This is how the
     * loop verifies that a fixer respected the scope it was given.
     *
     * @return the repository-relative paths of the modified files
     */
    List<String> modifiedFiles();

    /**
     * Files among {@link #modifiedFiles()} that are there under a new name,
     * mapped back to the name they had before.
     *
     * <p>A clean rename is a legitimate way to satisfy a rule that names a
     * file itself the problem, and it is still the same finding Sheriff
     * flagged under the old name — the scope check needs this to recognize
     * that, rather than reading the new name as an unrelated file nobody
     * authorized.
     *
     * @return the new name mapped to the old one, for renamed files only
     */
    Map<String, String> renamedFrom();
}
