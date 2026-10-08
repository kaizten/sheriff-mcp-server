package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Counts source files under a directory, by language, within a budget.
 *
 * <p>The budget is the point. The server works a project out from the
 * directory a client starts it in, and a client that does not start servers
 * in the project hands it the home directory or the filesystem root. Walked
 * in full, those take seconds or minutes on every call that names no
 * component. So the walk skips what is never source (dependencies, build
 * output, hidden folders), stops at a fixed depth and a fixed number of
 * entries, and stops at the first match when a yes or no is all that is
 * asked.
 */
final class SourceCounter extends SimpleFileVisitor<Path> {

    private static final int ENTRY_BUDGET = 50000;
    private static final int MAXIMUM_DEPTH = 24;
    private static final int NONE = 0;
    private static final int ONE = 1;
    private static final String ANY_SOURCE = "any";
    private static final String HIDDEN_PREFIX = ".";
    private static final String NODE_MODULES = "node_modules";
    private static final String MAVEN_OUTPUT = "target";
    private static final String GRADLE_OUTPUT = "build";
    private static final String BUNDLE_OUTPUT = "dist";
    private static final String IDE_OUTPUT = "out";
    private static final String VENDORED = "vendor";
    private static final String COVERAGE_REPORTS = "coverage";
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            NODE_MODULES, MAVEN_OUTPUT, GRADLE_OUTPUT, BUNDLE_OUTPUT, IDE_OUTPUT, VENDORED, COVERAGE_REPORTS);

    private final Path root;
    private final Map<String, List<String>> groups;
    private final boolean stopAtFirst;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private int visited;

    /**
     * Prepares one walk.
     *
     * @param root the directory to walk
     * @param groups each language's name and the extensions that count for it
     * @param stopAtFirst whether one matching file is enough
     */
    private SourceCounter(Path root, Map<String, List<String>> groups, boolean stopAtFirst) {
        this.root = root;
        this.groups = groups;
        this.stopAtFirst = stopAtFirst;
        for (String group : groups.keySet()) {
            counts.put(group, NONE);
        }
    }

    /**
     * How many files under a directory each group claims.
     *
     * @param root the directory to walk
     * @param groups each language's name and its extensions
     * @return the count per group, partial when the budget ran out
     */
    static Map<String, Integer> count(Path root, Map<String, List<String>> groups) {
        return walk(root, groups, false).counts;
    }

    /**
     * Whether a directory holds any file of the given extensions.
     *
     * @param root the directory to walk
     * @param suffixes the extensions to look for
     * @return {@code true} at the first one found
     */
    static boolean holdsAny(Path root, List<String> suffixes) {
        return walk(root, Map.of(ANY_SOURCE, suffixes), true).counts.get(ANY_SOURCE) > NONE;
    }

    /**
     * Whether a directory is worth looking inside at all.
     *
     * @param directory the directory
     * @return {@code false} for hidden folders, build output and dependencies
     */
    static boolean isSearched(Path directory) {
        Path name = directory.getFileName();
        if (name == null) {
            return true;
        }
        String text = name.toString();
        return !text.startsWith(HIDDEN_PREFIX) && !SKIPPED_DIRECTORIES.contains(text);
    }

    /**
     * Runs one walk.
     *
     * @param root the directory to walk
     * @param groups each language's name and its extensions
     * @param stopAtFirst whether one matching file is enough
     * @return the finished counter
     */
    private static SourceCounter walk(Path root, Map<String, List<String>> groups, boolean stopAtFirst) {
        SourceCounter counter = new SourceCounter(root, groups, stopAtFirst);
        if (!Files.isDirectory(root)) {
            return counter;
        }
        try {
            Files.walkFileTree(root, Set.<FileVisitOption>of(), MAXIMUM_DEPTH, counter);
        } catch (IOException exception) {
            return counter;
        }
        return counter;
    }

    /**
     * Skips the folders that are never source, and stops when the budget is
     * spent.
     *
     * @param directory the folder about to be entered
     * @param attributes its attributes
     * @return whether to go in
     */
    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
        if (!directory.equals(root) && !isSearched(directory)) {
            return FileVisitResult.SKIP_SUBTREE;
        }
        return spent() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    /**
     * Counts one file towards every group whose extension it has.
     *
     * @param file the file
     * @param attributes its attributes
     * @return whether to go on
     */
    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
        visited++;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean matched = false;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getValue().stream().anyMatch(name::endsWith)) {
                counts.merge(group.getKey(), ONE, Integer::sum);
                matched = true;
            }
        }
        if (matched && stopAtFirst) {
            return FileVisitResult.TERMINATE;
        }
        return spent() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
    }

    /**
     * A file that cannot be read is not a reason to stop.
     *
     * @param file the file
     * @param exception why it could not be read
     * @return always carry on
     */
    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) {
        return FileVisitResult.CONTINUE;
    }

    /**
     * Whether the walk has used its budget.
     *
     * @return {@code true} once it has
     */
    private boolean spent() {
        return visited >= ENTRY_BUDGET;
    }
}
