package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.PassMetrics;

/**
 * Where the fix loop leaves what each of its passes did, measured, so runs
 * can be compared: which rules Sheriff's fixers repair and which the model
 * does, how long each takes and what it costs.
 */
@FunctionalInterface
public interface RunMetrics {

    /**
     * Keeps one pass, once the analysis after it has measured it.
     *
     * @param pass what the pass did
     */
    void record(PassMetrics pass);
}
