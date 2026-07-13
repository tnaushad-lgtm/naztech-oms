package com.naztech.oms.perf;

import java.util.List;
import java.util.Map;

/** Request/response shapes for the throughput harness. */
public final class LoadTest {

    private LoadTest() {
    }

    /**
     * One run of the harness.
     *
     * @param targetPerSec  orders/second to aim for — the harness paces to this and reports what it
     *                      actually achieved, so a machine that cannot keep up shows a shortfall
     *                      rather than silently stretching the run
     * @param durationSec   how long to sustain it
     * @param strategy      {@code RESTING} = limit orders priced away from the market: they never
     *                      fill, so buying power is never consumed and the run is unbounded — this
     *                      measures the order-entry path (risk + persist + route).
     *                      {@code CROSSING} = marketable limits, and {@code MARKET} = market orders:
     *                      both fill, exercising trades, portfolio and the market-data mark as well,
     *                      but each fill permanently drains the account's buying power.
     * @param primeAccount  top the account's buying power up before the run. Without this a filling
     *                      run ends as soon as the account is dry, and every later order is a cheap
     *                      risk reject — which would flatter the achieved rate into meaninglessness.
     * @param threads       concurrent submitters. 0 = pick from the core count.
     */
    public record Request(
            int targetPerSec,
            int durationSec,
            Long accountId,
            Long securityId,
            Long dealerId,
            long quantity,
            String side,
            String strategy,
            boolean primeAccount,
            int threads) {
    }

    /** Live progress while a run is in flight, and the final report once it has finished. */
    public record Status(
            boolean running,
            String phase,
            int targetPerSec,
            int durationSec,
            double elapsedSec,
            long submitted,
            long accepted,
            long rejected,
            long errors,
            double achievedPerSec,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs,
            Map<String, Long> rejectReasons,
            List<String> errorSamples,
            String routedVia,
            int threads,
            int dbPoolSize,
            String note) {
    }
}
