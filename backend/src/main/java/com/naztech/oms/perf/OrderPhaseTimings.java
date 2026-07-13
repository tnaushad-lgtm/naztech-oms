package com.naztech.oms.perf;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Splits the cost of {@code OrderService.place} into its phases, so a throughput run can say where
 * the milliseconds actually go instead of leaving us to guess.
 *
 * <p>Under load this OMS shows a flat throughput ceiling with latency rising in step with the
 * thread count — the signature of one serialized step. Knowing whether that step is the risk
 * queries, the writes, the FIX send or the commit is the difference between fixing it and
 * rearranging things around it.
 *
 * <p>Always on: {@code System.nanoTime()} plus a {@link LongAdder} costs tens of nanoseconds against
 * an order that takes tens of milliseconds. Counters are reset at the start of each run.
 */
@Component
public class OrderPhaseTimings {

    public enum Phase {
        /** Loading the security and the client account. */
        LOOKUP,
        /** Pre-trade risk: kill-switch, limits, buying power, wash-trade guard. */
        RISK,
        /** Writing the order + its audit rows. */
        PERSIST,
        /** Handing off to the matching engine, or sending the NewOrderSingle over FIX. */
        ROUTE,
        /** Trailing audit + re-read + view mapping. */
        FINALISE
    }

    private final Map<Phase, LongAdder> nanos = new LinkedHashMap<>();
    private final Map<Phase, LongAdder> counts = new LinkedHashMap<>();

    public OrderPhaseTimings() {
        for (Phase p : Phase.values()) {
            nanos.put(p, new LongAdder());
            counts.put(p, new LongAdder());
        }
    }

    public void record(Phase phase, long startNanos) {
        nanos.get(phase).add(System.nanoTime() - startNanos);
        counts.get(phase).increment();
    }

    public void reset() {
        nanos.values().forEach(LongAdder::reset);
        counts.values().forEach(LongAdder::reset);
    }

    /** Mean milliseconds per order, per phase. Phases with no samples are omitted. */
    public Map<String, Double> meanMillis() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Phase p : Phase.values()) {
            long n = counts.get(p).sum();
            if (n > 0) {
                out.put(p.name(), Math.round(nanos.get(p).sum() / (double) n / 1_000_000.0 * 100) / 100.0);
            }
        }
        return out;
    }
}
