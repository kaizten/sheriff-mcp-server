package com.kaizten.sheriff.infrastructure.git;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the git adapter, driven through the process seam. */
class GitVersionControlTests {

    private static final Path REPOSITORY = Path.of("/repo");
    private static final String STATUS = " M calculadora-java/src/main/java/calculator/Calculator.java\0"
            + "?? new/File.java\0"
            + "R  new/Name.java\0old/Name.java\0";

    private static GitVersionControl git(FakeProcessRunner runner) {
        return new GitVersionControl(runner, REPOSITORY, "20260911-101500");
    }

    private static ProcessOutcome ok(String output) {
        return ProcessOutcome.completed(0, output, "");
    }

    @Test
    void readsModifiedAndUntrackedFiles() {
        List<String> files = git(FakeProcessRunner.always(ok(STATUS))).modifiedFiles();
        assertTrue(files.contains("calculadora-java/src/main/java/calculator/Calculator.java"));
        assertTrue(files.contains("new/File.java"));
    }

    @Test
    @DisplayName("a rename reports the name that exists now, which is what the scope check judges")
    void aRenameReportsTheNewName() {
        List<String> files = git(FakeProcessRunner.always(ok(STATUS))).modifiedFiles();
        assertTrue(files.contains("new/Name.java"));
        assertFalse(files.contains("old/Name.java"));
    }

    @Test
    @DisplayName("the old name of a rename is recoverable, so the scope check can still recognize it")
    void renamedFromMapsTheNewNameBackToTheOld() {
        Map<String, String> renames = git(FakeProcessRunner.always(ok(STATUS))).renamedFrom();
        assertEquals("old/Name.java", renames.get("new/Name.java"));
    }

    @Test
    @DisplayName("a plain edit or a new file is never mistaken for a rename")
    void renamedFromIgnoresNonRenameLines() {
        assertEquals(1, git(FakeProcessRunner.always(ok(STATUS))).renamedFrom().size());
    }

    @Test
    @DisplayName("a copy adds a file nobody allowed, so it is not a rename of the original")
    void aCopyIsNotARename() {
        GitVersionControl git = git(FakeProcessRunner.always(ok("C  copy/A.java\0orig/A.java\0 M b/B.java\0")));
        assertEquals(List.of("copy/A.java", "b/B.java"), git.modifiedFiles());
        assertEquals(Map.of(), git.renamedFrom());
    }

    @Test
    @DisplayName("a name with an accent comes back as itself, not as octal escapes")
    void namesAreReadVerbatim() {
        List<String> files = git(FakeProcessRunner.always(ok(" M app/Configuración.java\0"))).modifiedFiles();
        assertEquals(List.of("app/Configuración.java"), files);
    }

    @Test
    void readsStatusWithoutQuotingAndWithEveryUntrackedFile() {
        FakeProcessRunner runner = FakeProcessRunner.always(ok(""));
        git(runner).modifiedFiles();
        assertEquals(List.of("git", "status", "--porcelain", "-z", "--untracked-files=all"), runner.command());
    }

    @Test
    void aCleanTreeHasNoModifiedFiles() {
        assertEquals(List.of(), git(FakeProcessRunner.always(ok(""))).modifiedFiles());
    }

    @Test
    void detectsADirtyTree() {
        assertTrue(git(FakeProcessRunner.always(ok(STATUS))).hasUncommittedChanges());
        assertFalse(git(FakeProcessRunner.always(ok(""))).hasUncommittedChanges());
    }

    @Test
    void createsABranchNamedAfterThePrefixAndTheRun() {
        FakeProcessRunner runner = FakeProcessRunner.always(ok(""));
        assertEquals("sheriff-agent/20260911-101500", git(runner).createWorkingBranch("sheriff-agent"));
        assertEquals(List.of("git", "checkout", "-b", "sheriff-agent/20260911-101500"),
                runner.commands().get(runner.commands().size() - 1));
    }

    @Test
    @DisplayName("a dirty tree stops the run before it starts, and says what is dirty")
    void refusesToStartOnADirtyTree() {
        FakeProcessRunner runner = FakeProcessRunner.always(ok("?? demo-prep.sh\0"));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> git(runner).createWorkingBranch("sheriff-agent"));
        assertTrue(refused.getMessage().contains("demo-prep.sh"));
        assertTrue(refused.getMessage().contains("git stash"));
    }

    @Test
    @DisplayName("Sheriff's state left by an analysis cut short is not work to commit, and used to block every run")
    void sheriffsOwnStateDoesNotMakeTheTreeDirty() {
        FakeProcessRunner runner = FakeProcessRunner.always(
                ok("?? sheriff_errors.json\0?? sheriff_summary.json\0?? sheriff_tracked_files.json\0"));
        assertEquals("sheriff-agent/20260911-101500", git(runner).createWorkingBranch("sheriff-agent"));
    }

    @Test
    @DisplayName("a state file someone committed is theirs: its deletion would be committed under the agent's name")
    void aTrackedStateFileStillCounts() {
        FakeProcessRunner runner = FakeProcessRunner.always(ok(" M sheriff_errors.json\0"));
        assertThrows(IllegalStateException.class, () -> git(runner).createWorkingBranch("sheriff-agent"));
    }

    @Test
    void aFileOfTheSameNameInsideTheProjectStillCounts() {
        FakeProcessRunner runner = FakeProcessRunner.always(ok("?? app/sheriff_errors.json\0"));
        assertThrows(IllegalStateException.class, () -> git(runner).createWorkingBranch("sheriff-agent"));
    }

    @Test
    @DisplayName("without this guard the scope check blames the fixer for what was already there")
    void aDirtyTreeNeverReachesTheCheckout() {
        FakeProcessRunner runner = FakeProcessRunner.always(ok(" M A.java\0"));
        assertThrows(IllegalStateException.class, () -> git(runner).createWorkingBranch("sheriff-agent"));
        assertFalse(runner.commands().stream().anyMatch(command -> command.contains("checkout")));
    }

    @Test
    @DisplayName("a branch git refused to create stops the run, instead of committing to the current one")
    void aFailedCheckoutIsAnError() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(
                ok(""), ProcessOutcome.completed(128, "", "fatal: a branch named 'x' already exists")));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> git(runner).createWorkingBranch("sheriff-agent"));
        assertTrue(refused.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("nothing to commit is not a failure to commit")
    void commitsOnlyWhenThereIsSomethingToCommit() {
        FakeProcessRunner clean = FakeProcessRunner.always(ok(""));
        assertFalse(git(clean).commitIfChanges("nothing here"));
        assertEquals(1, clean.commands().size());
    }

    @Test
    void commitsWhenTheTreeIsDirty() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ok(STATUS), ok(""), ok("")));
        assertTrue(git(runner).commitIfChanges("sheriff-agent: iteration 1"));
        assertEquals(List.of("git", "add", "-A"), runner.commands().get(1));
        assertEquals(List.of("git", "commit", "-m", "sheriff-agent: iteration 1"), runner.commands().get(2));
    }

    @Test
    @DisplayName("a rejected commit stops the run: the next pass would be judged against a stale baseline")
    void aRejectedCommitIsAnError() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ok(STATUS), ok(""),
                ProcessOutcome.completed(128, "", "Author identity unknown")));
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> git(runner).commitIfChanges("sheriff-agent: iteration 1"));
        assertTrue(refused.getMessage().contains("Author identity unknown"));
    }

    @DisabledOnOs(value = OS.WINDOWS,
            disabledReason = "these stand in for git with POSIX paths; RealGit covers Windows with a real git")
    @Nested
    @DisplayName("when the mount and the repository are different directories")
    class Discovery {

        @Test
        @DisplayName("a repository that is itself the module: paths come back the way Sheriff names them")
        void theRepositoryIsTheComponent() {
            FakeProcessRunner runner = new FakeProcessRunner(List.of(
                    ok("/work/app\n"), ok(" M src/A.java\0R  src/New.java\0src/Old.java\0")));
            GitVersionControl git = new GitVersionControl(runner, Path.of("/work"), Path.of("/work/app"), "stamp");
            assertEquals(List.of("app/src/A.java", "app/src/New.java"), git.modifiedFiles());
            assertEquals(Path.of("/work/app"), runner.directory());
        }

        @Test
        void renamesAreTranslatedToo() {
            FakeProcessRunner runner = new FakeProcessRunner(List.of(
                    ok("/work/app\n"), ok("R  src/New.java\0src/Old.java\0")));
            GitVersionControl git = new GitVersionControl(runner, Path.of("/work"), Path.of("/work/app"), "stamp");
            assertEquals(Map.of("app/src/New.java", "app/src/Old.java"), git.renamedFrom());
        }

        @Test
        @DisplayName("in a larger repository, a file outside the mount can never pass for an allowed one")
        void aFileOutsideTheMountStaysOutside() {
            FakeProcessRunner runner = new FakeProcessRunner(List.of(
                    ok("/big\n"), ok(" M sub/app/A.java\0 M other/B.java\0")));
            GitVersionControl git = new GitVersionControl(
                    runner, Path.of("/big/sub"), Path.of("/big/sub/app"), "stamp");
            assertEquals(List.of("app/A.java", "../other/B.java"), git.modifiedFiles());
        }

        @Test
        @DisplayName("no repository at all is said out loud, with the way to run without one")
        void noRepositoryIsAnError() {
            FakeProcessRunner runner = FakeProcessRunner.always(
                    ProcessOutcome.completed(128, "", "fatal: not a git repository"));
            GitVersionControl git = new GitVersionControl(runner, Path.of("/work"), Path.of("/work/app"), "stamp");
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> git.createWorkingBranch("sheriff-agent"));
            assertTrue(refused.getMessage().contains("not inside a git repository"));
            assertTrue(refused.getMessage().contains("SHERIFF_AGENT_GIT_SAFETY=0"));
        }

        @Test
        void changedPathsAreAbsolute() {
            FakeProcessRunner runner = new FakeProcessRunner(List.of(ok("/work/app\n"), ok("?? src/B.java\0")));
            GitVersionControl git = new GitVersionControl(runner, Path.of("/work"), Path.of("/work/app"), "stamp");
            assertEquals(List.of(Path.of("/work/app/src/B.java")), git.changedPaths());
        }
    }

    @Nested
    @DisplayName("against a real git, when there is one")
    class RealGit {

        @TempDir
        private Path mount;

        private void git(Path directory, String... arguments) throws IOException, InterruptedException {
            List<String> command = new java.util.ArrayList<>(List.of("git"));
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            assertEquals(0, process.waitFor(), String.join(" ", command));
        }

        private boolean gitIsInstalled() {
            try {
                return new ProcessBuilder("git", "--version").start().waitFor() == 0;
            } catch (IOException | InterruptedException exception) {
                return false;
            }
        }

        @Test
        @DisplayName("a module that is its own repository: branch, scope and commit all work through the parent")
        void theRepositoryIsTheComponent() throws IOException, InterruptedException {
            assumeTrue(gitIsInstalled(), "git is not installed");
            Path app = Files.createDirectories(mount.resolve("app"));
            Files.createDirectories(app.resolve("src"));
            Files.writeString(app.resolve("src/A.java"), "class A {}");
            Files.writeString(app.resolve("src/Old.java"), "class Old {}");
            git(app, "init", "-q");
            git(app, "config", "user.email", "agent@example.com");
            git(app, "config", "user.name", "agent");
            git(app, "add", "-A");
            git(app, "commit", "-q", "-m", "seed");
            GitVersionControl git = new GitVersionControl(new SystemProcessRunner(), mount, app, "stamp");
            assertEquals("sheriff-agent/stamp", git.createWorkingBranch("sheriff-agent"));
            Files.writeString(app.resolve("src/A.java"), "class A { }");
            git(app, "mv", "src/Old.java", "src/New.java");
            assertEquals(Map.of("app/src/New.java", "app/src/Old.java"), git.renamedFrom());
            assertTrue(git.modifiedFiles().contains("app/src/A.java"));
            assertTrue(git.commitIfChanges("sheriff-agent: iteration 1"));
            assertFalse(git.hasUncommittedChanges());
        }

        @Test
        void aDirectoryOutsideAnyRepositoryIsRefused() {
            assumeTrue(gitIsInstalled(), "git is not installed");
            GitVersionControl git = new GitVersionControl(new SystemProcessRunner(), mount, mount, "stamp");
            assertThrows(IllegalStateException.class, () -> git.createWorkingBranch("sheriff-agent"));
        }
    }
}
