package com.simex.twin.application.engine;

import com.simex.twin.application.service.TelemetryAssembler;
import com.simex.twin.domain.scenario.ScenarioRunner;
import java.time.Instant;

/** Una corrida: su id, cuándo empezó (pared), su máquina y un contador de {@code seq} por vista. */
public final class RunContext implements TelemetryAssembler.SeqCounters {

    private final String runId;
    private final long seed;
    private final Instant startedAt;
    private final ScenarioRunner runner;
    private long publicSeq;
    private long groundTruthSeq;
    private boolean ended;

    RunContext(String runId, long seed, Instant startedAt, ScenarioRunner runner) {
        this.runId = runId;
        this.seed = seed;
        this.startedAt = startedAt;
        this.runner = runner;
    }

    public String runId() {
        return runId;
    }

    public long seed() {
        return seed;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public ScenarioRunner runner() {
        return runner;
    }

    @Override
    public long nextPublic() {
        return ++publicSeq;
    }

    @Override
    public long nextGroundTruth() {
        return ++groundTruthSeq;
    }

    boolean ended() {
        return ended;
    }

    void end() {
        ended = true;
    }
}
