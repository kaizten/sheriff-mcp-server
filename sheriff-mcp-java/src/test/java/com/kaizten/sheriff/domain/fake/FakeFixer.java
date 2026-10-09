package com.kaizten.sheriff.domain.fake;

import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import java.util.ArrayList;
import java.util.List;

/** A CodeFixer that records what it was asked and answers as configured. */
public final class FakeFixer implements CodeFixer {

    private final boolean ok;
    private final String output;
    private final List<FixRequest> requests = new ArrayList<>();

    /** A fixer whose invocations always succeed. */
    public FakeFixer() {
        this(true, "");
    }

    /**
     * @param ok whether the invocation succeeds
     * @param output what the fixer reports
     */
    public FakeFixer(boolean ok, String output) {
        this.ok = ok;
        this.output = output;
    }

    @Override
    public FixResult fix(FixRequest request) {
        requests.add(request);
        return new FixResult(ok, output);
    }

    /**
     * Everything this fixer was asked to do, in order.
     *
     * @return the recorded requests
     */
    public List<FixRequest> requests() {
        return requests;
    }
}
