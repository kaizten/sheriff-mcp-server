package com.kaizten.sheriff.infrastructure.mock;

import com.kaizten.sheriff.domain.port.CodeAnalyzer;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * An analyzer that needs neither Docker nor the Sheriff image: a line carrying
 * a marker counts as one error.
 *
 * <p>Its detection is fake and makes no claim otherwise. What it exercises is
 * everything around the analyzer — the loop's decisions, the batching, the
 * scope check, the commits — which is the part worth being able to run on a
 * machine that has no Docker, or in a test that should not take forty seconds.
 */
public final class MockSheriffAnalyzer implements CodeAnalyzer {

    private static final String DESCRIPTION = "Mock marker found at line %d.";
    private static final String HOW_TO_SOLVE = "Remove the '%s' marker from line %d.";
    private static final String REFERENCE_CODE = "MockMarker";
    private static final String NOTHING_TO_ANALYZE = "The mock has nothing to analyze at %s";

    private final Path repositoryRoot;
    private final Path target;
    private final String marker;

    /**
     * Wires the mock to a directory to scan.
     *
     * @param repositoryRoot the repository findings are reported relative to
     * @param target the directory to scan, inside that repository
     * @param marker the text that counts as one error
     */
    public MockSheriffAnalyzer(Path repositoryRoot, Path target, String marker) {
        this.repositoryRoot = repositoryRoot;
        this.target = target;
        this.marker = marker;
    }

    /**
     * Scans the target directory and reports one error per marked line.
     *
     * @return the findings, or a failure when there is nothing to scan
     */
    @Override
    public AnalysisResult analyze() {
        if (!Files.isDirectory(target)) {
            return AnalysisResult.failure(String.format(NOTHING_TO_ANALYZE, target));
        }
        try (Stream<Path> files = Files.walk(target)) {
            List<SheriffFinding> findings = new ArrayList<>();
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                findings.addAll(findingsIn(file));
            }
            return AnalysisResult.of(findings);
        } catch (IOException exception) {
            return AnalysisResult.failure(exception.getMessage());
        }
    }

    /**
     * The findings of one file, one per line carrying the marker.
     *
     * @param file the file to read
     * @return its findings, empty when the file carries no marker
     * @throws IOException when the file cannot be read
     */
    private List<SheriffFinding> findingsIn(Path file) throws IOException {
        List<SheriffFinding> findings = new ArrayList<>();
        List<String> lines = Files.readAllLines(file);
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(marker)) {
                int line = index + 1;
                findings.add(new SheriffFinding(
                        repositoryRoot.relativize(file).toString(),
                        String.format(DESCRIPTION, line),
                        String.format(HOW_TO_SOLVE, marker, line),
                        REFERENCE_CODE,
                        SheriffFinding.ERROR,
                        Map.of()));
            }
        }
        return findings;
    }
}
