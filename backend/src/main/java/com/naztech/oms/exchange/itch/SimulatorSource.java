package com.naztech.oms.exchange.itch;

import java.util.List;

/**
 * {@link ItchSource} backed by the in-house {@link ItchSimulator}. Deterministic for a given seed:
 * two SimulatorSources built with the same seed emit an identical message sequence, which makes the
 * whole market-data pipeline reproducible in tests and demos.
 */
public final class SimulatorSource implements ItchSource {

    private final ItchSimulator sim;
    private final int burst;

    public SimulatorSource(List<ItchSimulator.Instrument> instruments, long seed, int burst) {
        this.sim = new ItchSimulator(instruments, seed);
        this.burst = Math.max(1, burst);
    }

    @Override public List<Itch.Msg> open() { return sim.openingSequence(); }

    @Override public List<Itch.Msg> poll() { return sim.burst(burst); }

    @Override public String name() { return "simulator"; }
}
