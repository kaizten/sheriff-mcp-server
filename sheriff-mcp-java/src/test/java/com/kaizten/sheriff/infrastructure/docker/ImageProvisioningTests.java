package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Sheriff's image: asking whether it is here and current, and what the entry
 * points do when it is not. Docker and Docker Hub are both stood in for, so
 * none of this needs either.
 */
class ImageProvisioningTests {

    private static final String IMAGE = "kaizten/sheriff:latest";
    private static final String HERE = "[\"kaizten/sheriff@sha256:aaa\"]";
    private static final ProcessOutcome MISSING = ProcessOutcome.completed(1, "", "Error: No such image");
    private static final ProcessOutcome PULLED = ProcessOutcome.completed(0, "Status: Downloaded", "");

    /**
     * A Docker that answers inspect and pull as told, and records what it was
     * asked.
     */
    private static final class FakeDocker implements ProcessRunner {

        private final List<ProcessOutcome> inspections;
        private final ProcessOutcome pull;
        private final List<List<String>> commands = new ArrayList<>();
        private final CountDownLatch pullRelease;

        FakeDocker(List<ProcessOutcome> inspections, ProcessOutcome pull, CountDownLatch pullRelease) {
            this.inspections = new ArrayList<>(inspections);
            this.pull = pull;
            this.pullRelease = pullRelease;
        }

        @Override
        public ProcessOutcome run(List<String> command, java.nio.file.Path workingDirectory,
                Map<String, String> environment, java.time.Duration timeout) {
            commands.add(command);
            if (command.contains("pull")) {
                try {
                    pullRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return pull;
            }
            return inspections.size() > 1 ? inspections.remove(0) : inspections.get(0);
        }

        boolean pulled() {
            return commands.stream().anyMatch(command -> command.contains("pull"));
        }
    }

    private static FakeDocker docker(ProcessOutcome inspection, ProcessOutcome pull) {
        return new FakeDocker(List.of(inspection), pull, new CountDownLatch(0));
    }

    private static ProcessOutcome here() {
        return ProcessOutcome.completed(0, HERE, "");
    }

    @Nested
    @DisplayName("asking about the image")
    class Asking {

        @Test
        @DisplayName("Docker Hub is asked by repository and tag, and official images live under library/")
        void theHubUrlOfAnImage() {
            assertEquals(Optional.of("https://hub.docker.com/v2/repositories/kaizten/sheriff/tags/latest"),
                    SheriffImage.hubUrl("kaizten/sheriff:latest"));
            assertEquals(Optional.of("https://hub.docker.com/v2/repositories/kaizten/sheriff/tags/latest"),
                    SheriffImage.hubUrl("kaizten/sheriff"));
            assertEquals(Optional.of("https://hub.docker.com/v2/repositories/library/ubuntu/tags/24.04"),
                    SheriffImage.hubUrl("ubuntu:24.04"));
        }

        @Test
        @DisplayName("another registry, or an image pinned to a digest, is never called outdated")
        void imagesDockerHubCannotSpeakFor() {
            assertEquals(Optional.empty(), SheriffImage.hubUrl("ghcr.io/kaizten/sheriff:latest"));
            assertEquals(Optional.empty(), SheriffImage.hubUrl("localhost:5000/sheriff"));
            assertEquals(Optional.empty(), SheriffImage.hubUrl("kaizten/sheriff@sha256:aaa"));
        }

        @Test
        @DisplayName("outdated only when both digests are known and differ")
        void outdatedNeedsBothDigests() {
            assertTrue(new SheriffImage(docker(here(), PULLED), IMAGE, image -> Optional.of("sha256:bbb")).outdated());
            assertFalse(new SheriffImage(docker(here(), PULLED), IMAGE, image -> Optional.of("sha256:aaa")).outdated());
            assertFalse(new SheriffImage(docker(here(), PULLED), IMAGE, image -> Optional.empty()).outdated());
            assertFalse(new SheriffImage(docker(MISSING, PULLED), IMAGE, image -> Optional.of("sha256:b")).outdated());
        }
    }

    @Nested
    @DisplayName("making sure of it")
    class Provisioning {

        private final ByteArrayOutputStream logged = new ByteArrayOutputStream();
        private final PrintStream log = new PrintStream(logged, true, StandardCharsets.UTF_8);

        private String log() {
            return logged.toString(StandardCharsets.UTF_8);
        }

        @Test
        @DisplayName("a missing image is pulled, with its own time limit, and is then ready")
        void aMissingImageIsPulled() {
            FakeDocker docker = docker(MISSING, PULLED);
            ImageProvisioning image = new ImageProvisioning(
                    new SheriffImage(docker, IMAGE, name -> Optional.empty()), "check", log);
            assertTrue(image.now());
            assertTrue(docker.pulled());
            assertEquals(Optional.empty(), image.blocker());
            assertTrue(log().contains("pulling it"), log());
        }

        @Test
        @DisplayName("a pull that fails says why, in Docker's own words")
        void aFailedPullSaysWhy() {
            ImageProvisioning image = new ImageProvisioning(new SheriffImage(
                    docker(MISSING, ProcessOutcome.completed(1, "", "net/http: TLS handshake timeout")), IMAGE,
                    name -> Optional.empty()), "check", log);
            assertFalse(image.now());
            assertTrue(image.blocker().orElse("").contains("TLS handshake timeout"), image.blocker().toString());
        }

        @Test
        @DisplayName("while the pull runs, the tools can say it is being downloaded")
        void whilePullingItSaysSo() throws Exception {
            CountDownLatch release = new CountDownLatch(1);
            FakeDocker docker = new FakeDocker(List.of(MISSING), PULLED, release);
            ImageProvisioning image = new ImageProvisioning(
                    new SheriffImage(docker, IMAGE, name -> Optional.empty()), "check", log).inBackground();
            for (int wait = 0; wait < 50 && image.blocker().isEmpty(); wait++) {
                Thread.sleep(20);
            }
            assertTrue(image.blocker().orElse("").contains("being downloaded"), image.blocker().toString());
            release.countDown();
            image.ready().get(5, TimeUnit.SECONDS);
            assertEquals(Optional.empty(), image.blocker());
        }

        @Test
        @DisplayName("an outdated image is reported, not replaced: new rules should not arrive unasked")
        void anOutdatedImageIsReported() {
            FakeDocker docker = docker(here(), PULLED);
            ImageProvisioning image = new ImageProvisioning(
                    new SheriffImage(docker, IMAGE, name -> Optional.of("sha256:bbb")), "check", log);
            assertTrue(image.now());
            assertFalse(docker.pulled());
            assertTrue(image.notice().orElse("").contains("docker pull kaizten/sheriff:latest"));
        }

        @Test
        @DisplayName("the way to have it updated on start is the installer's option, which reaches Codex too")
        void anOutdatedImageNamesTheInstallersOption() {
            ImageProvisioning image = new ImageProvisioning(
                    new SheriffImage(docker(here(), PULLED), IMAGE, name -> Optional.of("sha256:bbb")), "check", log);
            image.now();
            assertTrue(image.notice().orElse("").contains("install with --pull-always"), image.notice().toString());
        }

        @Test
        @DisplayName("with SHERIFF_PULL=always an outdated image is replaced instead")
        void alwaysReplacesAnOutdatedImage() {
            FakeDocker docker = docker(here(), PULLED);
            ImageProvisioning image = new ImageProvisioning(
                    new SheriffImage(docker, IMAGE, name -> Optional.of("sha256:bbb")), "always", log);
            assertTrue(image.now());
            assertTrue(docker.pulled());
            assertEquals(Optional.empty(), image.notice());
        }

        @Test
        @DisplayName("with SHERIFF_PULL=never Docker Hub is not even asked")
        void neverDoesNotAskTheNetwork() {
            List<String> asked = new ArrayList<>();
            ImageProvisioning image = new ImageProvisioning(new SheriffImage(docker(here(), PULLED), IMAGE, name -> {
                asked.add(name);
                return Optional.of("sha256:bbb");
            }), "never", log);
            assertTrue(image.now());
            assertEquals(List.of(), asked);
            assertEquals(Optional.empty(), image.notice());
        }

        @Test
        void thePolicyComesFromTheEnvironment() {
            assertEquals("check", ImageProvisioning.policyIn(Map.of()));
            assertEquals("always", ImageProvisioning.policyIn(Map.of("SHERIFF_PULL", " ALWAYS ")));
        }
    }
}
