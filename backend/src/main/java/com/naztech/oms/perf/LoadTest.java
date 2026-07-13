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
     * <p>Every field beyond {@code threads} defaults to off, so a request that sets none of them
     * behaves exactly as the harness did before they existed: one account, one stock, one quantity,
     * paced to a target.
     *
     * @param targetPerSec  orders/second to aim for — the harness paces to this and reports what it
     *                      actually achieved, so a machine that cannot keep up shows a shortfall
     *                      rather than silently stretching the run. Ignored when {@code maxLoad}.
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
     *
     * @param maxLoad          drop the pacer and submit as fast as the box allows. The achieved rate
     *                         is then the ceiling of this deployment, not a target it was asked to hit.
     * @param randomSecurity   pick a random tradable instrument per order instead of one stock. A
     *                         single-stock run serialises on one book and one market-data row; that is
     *                         a lock-contention benchmark, not a throughput one.
     * @param randomAccount    pick a random client account per order — multiple client contexts, so
     *                         buying-power rows are not all the same row.
     * @param randomDealer     spread orders across the dealer/trader pool, so trader-scope limits and
     *                         the audit trail see more than one actor.
     * @param randomQty        quantity uniform in [{@code qtyMin}, {@code qtyMax}], rounded down to a
     *                         whole lot (an off-lot quantity would just be rejected by the lot check).
     * @param randomSide       BUY or SELL at random. SELLs need holdings, so most runs want BUY.
     * @param randomPrice      jitter the limit price by ±{@code priceJitterPct}% around the strategy's
     *                         price, so orders land on different book levels rather than one.
     * @param bypassValidation skip pre-trade risk entirely — the raw write-path ceiling. Requires
     *                         {@code app.loadtest.enabled=true}; see {@code OrderService.place}.
     *
     * @param rejectPct      of every 100 orders, how many the exchange should reject outright
     * @param partialPct     how many should fill part-way and stop
     * @param partialFillPct how much of such an order fills (25 / 50 / 75)
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
            int threads,

            boolean maxLoad,
            boolean randomSecurity,
            boolean randomAccount,
            boolean randomDealer,
            boolean randomQty,
            long qtyMin,
            long qtyMax,
            boolean randomSide,
            boolean randomPrice,
            double priceJitterPct,
            boolean bypassValidation,

            int rejectPct,
            int partialPct,
            int partialFillPct) {
    }

    /**
     * Live progress while a run is in flight, and the final report once it has finished.
     *
     * @param generated   orders the generator built and handed to a submitter
     * @param submitted   orders {@code place()} has returned from (accepted, rejected or errored)
     * @param accepted    passed pre-trade risk and were routed
     * @param rejected    refused by pre-trade risk — {@code rejectReasons} says why
     * @param errors      threw: a bug, a deadlock, a pool timeout. Not the same as a rejection.
     * @param queueDepth  {@code generated - submitted}: orders inside the OMS right now. It grows
     *                    when the OMS is slower than the generator, which is the thing being measured.
     * @param backlog     slots that came due and nobody has picked up yet — how far the <em>pacer</em>
     *                    has fallen behind the wall clock. Zero on a healthy paced run; meaningless
     *                    under {@code maxLoad}, which has no schedule to fall behind.
     * @param executions      inbound ExecutionReports the OMS applied (FIX) or the simulator produced
     * @param partialFills    of those, how many left an order PARTIAL
     * @param fullFills        …FILLED
     * @param exchangeRejects  …REJECTED <em>by the venue</em> — a different thing from a risk rejection
     * @param cancels          …CANCELLED
     */
    public record Status(
            boolean running,
            String phase,
            int targetPerSec,
            int durationSec,
            double elapsedSec,
            long generated,
            long submitted,
            long accepted,
            long rejected,
            long errors,
            long queueDepth,
            long backlog,
            double achievedPerSec,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs,

            long executions,
            long partialFills,
            long fullFills,
            long exchangeRejects,
            long cancels,

            /** What the JVM and the box were doing while it ran: cpuPct, heapUsedMb, heapMaxMb, threads, cores. */
            Map<String, Object> resources,

            Map<String, Long> rejectReasons,
            List<String> errorSamples,
            String routedVia,
            int threads,
            int dbPoolSize,
            /**
             * Mean ms per order inside {@code place()}, by phase, plus COMMIT — which is derived
             * (total latency minus the phases), because the transaction commits after the method
             * returns and so no timer inside it can see the flush and fsync.
             */
            Map<String, Double> phaseMs,
            String note) {
    }
}
