package com.kaizten.sheriff.infrastructure.git;

import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The methods a change added to a component's code that no test it changed
 * calls.
 *
 * <p>Sheriff clean and the tests green did not mean the new code worked: asked
 * for {@code Pet.getLastVisitDate()}, two models of three wrote one that
 * throws on a visit with no date, and nothing tested it. Whether a new method
 * has a test is not something to leave to a model's judgement, so it is
 * checked: a method in the main sources that the last commit does not have,
 * and that is not private, must be called from a test file that changed too.
 *
 * <p>Java only, read from the text rather than parsed, which is enough to find
 * a declaration's name; a constructor, whose "type" would be a modifier, is
 * not a method here. What cannot be checked (no repository, no git) checks
 * nothing, as the hooks do when they cannot run.
 */
public final class UntestedMethods {

    private static final String DECLARATION = "(?m)^[ \\t]*((?:@\\w+(?:\\([^)]*\\))?\\s+)*)"
            + "((?:(?:public|protected|private|static|final|synchronized|abstract|default|native|strictfp)\\s+)*)"
            + "(?:<[^>]+>\\s+)?([\\w.$<>\\[\\],?]+(?:\\s*<[^>]*>)?(?:\\[\\])*)\\s+(\\w+)\\s*\\(";
    private static final Pattern METHOD = Pattern.compile(DECLARATION);
    private static final String RETURN = "return";
    private static final String NEW = "new";
    private static final String THROW = "throw";
    private static final String ELSE = "else";
    private static final String CASE = "case";
    private static final String YIELD = "yield";
    private static final String ASSERT = "assert";
    private static final String PRIVATE = "private";
    private static final String PUBLIC = "public";
    private static final String PROTECTED = "protected";
    private static final String STATIC = "static";
    private static final String FINAL = "final";
    private static final String SYNCHRONIZED = "synchronized";
    private static final String ABSTRACT = "abstract";
    private static final String DEFAULT = "default";
    private static final Set<String> NOT_A_TYPE = Set.of(RETURN, NEW, THROW, ELSE, CASE, YIELD, ASSERT, PRIVATE,
            PUBLIC, PROTECTED, STATIC, FINAL, SYNCHRONIZED, ABSTRACT, DEFAULT);
    private static final String JAVA = ".java";
    private static final String MAIN_SOURCES = "/src/main/";
    private static final String TEST_SOURCES = "/src/test/";
    private static final String SLASH = "/";
    private static final String CALL = "%s(";
    private static final String REFERENCE = "::%s";
    private static final String ENTRY = "%s: %s";
    private static final String NO_BRANCH_STAMP = "";
    private static final char BODY = '{';
    private static final char NO_BODY = ';';
    private static final int MODIFIERS_GROUP = 2;
    private static final int TYPE_GROUP = 3;
    private static final int NAME_GROUP = 4;
    private static final int NOT_FOUND = -1;

    private final ProcessRunner processes;

    /**
     * Wires the check.
     *
     * @param processes how to run git
     */
    public UntestedMethods(ProcessRunner processes) {
        this.processes = processes;
    }

    /**
     * The new methods of a component that no changed test calls.
     *
     * @param mount the directory Sheriff mounts
     * @param component the component, a folder of it
     * @return each one as {@code path: name}, the path relative to the
     *     component, empty when there is none or nothing could be checked
     */
    public List<String> in(Path mount, String component) {
        Path root = mount.resolve(component);
        try {
            GitVersionControl git = new GitVersionControl(processes, mount, root, NO_BRANCH_STAMP);
            List<Path> changed = git.changedPaths().stream().filter(path -> path.startsWith(root)).toList();
            List<String> tests = new ArrayList<>();
            for (Path file : changed) {
                if (isSource(root, file, TEST_SOURCES)) {
                    tests.add(Files.readString(file));
                }
            }
            List<String> untested = new ArrayList<>();
            for (Path file : changed) {
                if (isSource(root, file, MAIN_SOURCES)) {
                    untested.addAll(untestedIn(git, root, file, tests));
                }
            }
            return untested;
        } catch (IOException | IllegalStateException exception) {
            return List.of();
        }
    }

    /**
     * The new methods of one source file that no changed test calls.
     *
     * @param git what reads the last commit
     * @param root the component's folder
     * @param file the source file
     * @param tests the changed tests' sources
     * @return each one as {@code path: name}
     * @throws IOException when the file cannot be read
     */
    private static List<String> untestedIn(GitVersionControl git, Path root, Path file, List<String> tests)
            throws IOException {
        Set<String> added = declared(Files.readString(file));
        added.removeAll(declared(git.committedContent(file)));
        List<String> untested = new ArrayList<>();
        for (String name : added) {
            if (tests.stream().noneMatch(test -> calls(test, name))) {
                untested.add(String.format(ENTRY, Platform.slashes(root.relativize(file)), name));
            }
        }
        return untested;
    }

    /**
     * Whether a file is a Java source under one of a component's source
     * folders, and still there.
     *
     * @param root the component's folder
     * @param file the file
     * @param folder {@code /src/main/} or {@code /src/test/}
     * @return {@code true} for such a file
     */
    private static boolean isSource(Path root, Path file, String folder) {
        String relative = SLASH + Platform.slashes(root.relativize(file));
        return relative.contains(folder) && relative.endsWith(JAVA) && Files.isRegularFile(file);
    }

    /**
     * The names of the methods with a body, not private, that a source
     * declares.
     *
     * @param source the source
     * @return their names
     */
    static Set<String> declared(String source) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = METHOD.matcher(source);
        while (matcher.find()) {
            String modifiers = matcher.group(MODIFIERS_GROUP);
            boolean method = !modifiers.contains(PRIVATE) && !NOT_A_TYPE.contains(matcher.group(TYPE_GROUP))
                    && hasBody(source, matcher.end());
            if (method) {
                names.add(matcher.group(NAME_GROUP));
            }
        }
        return names;
    }

    /**
     * Whether the declaration that starts before an offset has a body: its
     * first brace comes before its first semicolon.
     *
     * @param source the source
     * @param from an offset inside the declaration's parameters
     * @return {@code true} for a method with a body
     */
    private static boolean hasBody(String source, int from) {
        int brace = source.indexOf(BODY, from);
        int semicolon = source.indexOf(NO_BODY, from);
        return brace != NOT_FOUND && (semicolon == NOT_FOUND || brace < semicolon);
    }

    /**
     * Whether a test calls a method, or refers to it.
     *
     * @param test the test's source
     * @param name the method's name
     * @return {@code true} when it does
     */
    private static boolean calls(String test, String name) {
        return test.contains(String.format(CALL, name)) || test.contains(String.format(REFERENCE, name));
    }
}
