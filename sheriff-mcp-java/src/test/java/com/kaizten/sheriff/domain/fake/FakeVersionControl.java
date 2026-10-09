package com.kaizten.sheriff.domain.fake;

import com.kaizten.sheriff.domain.port.VersionControl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A VersionControl that reports a fixed set of modified files. */
public final class FakeVersionControl implements VersionControl {

    private static final String BRANCH_SUFFIX = "/fake";

    private final List<String> modified;
    private final Map<String, String> renamedFrom;
    private final List<String> commits = new ArrayList<>();
    private boolean branchCreated;

    /** A repository where nothing was modified. */
    public FakeVersionControl() {
        this(List.of());
    }

    /**
     * @param modified what modifiedFiles() should report
     */
    public FakeVersionControl(List<String> modified) {
        this(modified, Map.of());
    }

    /**
     * @param modified what modifiedFiles() should report
     * @param renamedFrom what renamedFrom() should report: new name to old
     */
    public FakeVersionControl(List<String> modified, Map<String, String> renamedFrom) {
        this.modified = modified;
        this.renamedFrom = renamedFrom;
    }

    @Override
    public boolean hasUncommittedChanges() {
        return false;
    }

    @Override
    public String createWorkingBranch(String prefix) {
        branchCreated = true;
        return prefix + BRANCH_SUFFIX;
    }

    @Override
    public boolean commitIfChanges(String message) {
        commits.add(message);
        return true;
    }

    @Override
    public List<String> modifiedFiles() {
        return modified;
    }

    @Override
    public Map<String, String> renamedFrom() {
        return renamedFrom;
    }

    /**
     * The commit messages this run produced.
     *
     * @return those messages, in order
     */
    public List<String> commits() {
        return commits;
    }

    /**
     * Whether the loop asked for a dedicated branch.
     *
     * @return true when it did
     */
    public boolean branchCreated() {
        return branchCreated;
    }
}
