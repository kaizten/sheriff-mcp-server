package com.kaizten.sheriff.domain.fake;

import com.kaizten.sheriff.domain.port.CodeAnalyzer;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** A CodeAnalyzer that returns a scripted sequence of results. */
public final class FakeAnalyzer implements CodeAnalyzer {

    private final Deque<AnalysisResult> results;

    /**
     * @param results what successive calls to analyze() should return
     */
    public FakeAnalyzer(List<AnalysisResult> results) {
        this.results = new ArrayDeque<>(results);
    }

    @Override
    public AnalysisResult analyze() {
        return results.removeFirst();
    }

    /**
     * A result with n identical errors in one file.
     *
     * @param total how many errors
     * @param file the file they are in, empty for a finding with none — which
     *     is how the real adapter represents it, since Sheriff omits the
     *     field rather than sending a null
     * @return the analysis result
     */
    public static AnalysisResult result(int total, String file) {
        List<SheriffFinding> findings = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            findings.add(finding(file));
        }
        return AnalysisResult.of(findings);
    }

    /**
     * One error per named file, in order. A name may repeat to put more than
     * one error in the same file.
     *
     * @param files the file names
     * @return the analysis result
     */
    public static AnalysisResult multiFileResult(String... files) {
        List<SheriffFinding> findings = new ArrayList<>();
        for (String file : files) {
            findings.add(finding(file));
        }
        return AnalysisResult.of(findings);
    }

    private static SheriffFinding finding(String file) {
        return new SheriffFinding(file, "x", "y", "", SheriffFinding.ERROR, Map.of());
    }
}
