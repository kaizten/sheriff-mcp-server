package com.kaizten.sheriff.domain.fake;

import com.kaizten.sheriff.domain.port.TestRunner;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A TestRunner that answers as configured, or from a scripted sequence. */
public final class FakeTestRunner implements TestRunner {

    private final Deque<VerificationResult> scripted;
    private final VerificationResult fixed;

    /** A runner whose suite always passes. */
    public FakeTestRunner() {
        this(true);
    }

    /**
     * @param ok whether the suite passes, every time
     */
    public FakeTestRunner(boolean ok) {
        this.fixed = new VerificationResult(ok, ok ? "" : "tests failed");
        this.scripted = null;
    }

    /**
     * @param results what successive runs should return -- the repair path
     *     needs a runner that fails once and then passes
     */
    public FakeTestRunner(List<VerificationResult> results) {
        this.scripted = new ArrayDeque<>(results);
        this.fixed = null;
    }

    /**
     * A runner that answers a script and then always the same.
     *
     * @param results the first answers, in order
     * @param after the answer once they run out
     */
    private FakeTestRunner(List<VerificationResult> results, VerificationResult after) {
        this.scripted = new ArrayDeque<>(results);
        this.fixed = after;
    }

    /**
     * Tests that pass before a run changes anything and fail ever after, as
     * when the run broke them.
     *
     * @return that runner
     */
    public static FakeTestRunner brokenByTheRun() {
        return new FakeTestRunner(List.of(VerificationResult.passed("")), new VerificationResult(false, "tests failed"));
    }

    @Override
    public VerificationResult run() {
        return scripted == null || scripted.isEmpty() && fixed != null ? fixed : scripted.removeFirst();
    }
}
