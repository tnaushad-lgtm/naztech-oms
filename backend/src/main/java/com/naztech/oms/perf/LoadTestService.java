package com.naztech.oms.perf;

import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Throughput harness: places orders through the real {@link OrderService#place} path — pre-trade
 * risk, matching or FIX routing, persistence, audit, SSE — at a rate you choose, and reports what
 * the machine actually delivered.
 *
 * <p>It exists to answer one question honestly: <em>how many orders per second does this
 * deployment do, and what is holding it back?</em> Run it here, then run the same thing on the UAT
 * server; the numbers move, the method doesn't.
 *
 * <h2>Why the pacing works the way it does</h2>
 * Each order is assigned a wall-clock slot ({@code start + n / targetPerSec}). Submitters wait for
 * their slot and then place. If the system cannot keep up, slots fall behind and the achieved rate
 * comes out below target — which is the finding. A naive "sleep between orders" loop would instead
 * stretch the run and report the target back to you, hiding exactly what you are trying to measure.
 *
 * <h2>Why {@code strategy} matters</h2>
 * Buying power is decremented on every fill and is never returned, so a filling run empties the
 * account and then every later order is a cheap "insufficient buying power" reject — which would
 * inflate the rate while measuring nothing. {@code RESTING} orders are priced away from the market,
 * never fill, and so can be sustained indefinitely. Filling runs should set {@code primeAccount}.
 */
@Service
public class LoadTestService {

    private static final Logger log = LoggerFactory.getLogger(LoadTestService.class);

    /** Guard rails: a runaway harness on a shared box is worse than no harness. */
    static final int MAX_TARGET_PER_SEC = 20_000;
    static final int MAX_DURATION_SEC = 300;
    private static final int MAX_ERROR_SAMPLES = 5;
    private static final BigDecimal PRIMED_BUYING_POWER = new BigDecimal("100000000000.00");   // 100bn

    private final OrderService orders;
    private final SecurityRepo securityRepo;
    private final ClientAccountRepo accountRepo;
    private final MarketDataRepo marketRepo;

    @Value("${exchange.mode:simulator}")
    private String exchangeMode;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int dbPoolSize;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Run current = new Run();

    public LoadTestService(OrderService orders, SecurityRepo securityRepo,
                           ClientAccountRepo accountRepo, MarketDataRepo marketRepo) {
        this.orders = orders;
        this.securityRepo = securityRepo;
        this.accountRepo = accountRepo;
        this.marketRepo = marketRepo;
    }

    /** Everything one run accumulates. Replaced wholesale at the start of the next run. */
    private static final class Run {
        volatile LoadTest.Request req;
        volatile String phase = "IDLE";
        volatile String note = "";
        volatile long startNanos;
        volatile long endNanos;
        final LongAdder submitted = new LongAdder();
        final LongAdder accepted = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder errors = new LongAdder();
        final Map<String, LongAdder> rejectReasons = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();
        volatile long[] latencies = new long[0];      // nanos, filled up to latencyCount
        final AtomicInteger latencyCount = new AtomicInteger();
        volatile Thread driver;
    }

    public synchronized void start(LoadTest.Request raw) {
        if (running.get()) {
            throw new IllegalStateException("A load test is already running");
        }
        LoadTest.Request req = validate(raw);

        Security sec = securityRepo.findById(req.securityId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown security " + req.securityId()));
        ClientAccount acc = accountRepo.findById(req.accountId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown account " + req.accountId()));

        Run run = new Run();
        run.req = req;
        run.phase = "STARTING";
        run.latencies = new long[capacity(req)];
        current = run;
        running.set(true);

        if (req.primeAccount()) {
            acc.setBuyingPower(PRIMED_BUYING_POWER);
            accountRepo.save(acc);
            run.note = "Buying power primed to " + PRIMED_BUYING_POWER.toPlainString() + " on account " + acc.getId();
        } else if (!"RESTING".equalsIgnoreCase(req.strategy())) {
            run.note = "Not primed: fills will drain buying power (" + acc.getBuyingPower().toPlainString()
                    + ") and later orders will be rejected.";
        }

        BigDecimal price = priceFor(sec, req);
        Thread driver = new Thread(() -> drive(run, req, price), "loadtest-driver");
        driver.setDaemon(true);
        run.driver = driver;
        driver.start();
    }

    public void stop() {
        Run run = current;
        run.phase = "STOPPING";
        Thread d = run.driver;
        if (d != null) {
            d.interrupt();
        }
    }

    public LoadTest.Status status() {
        Run r = current;
        LoadTest.Request req = r.req;
        boolean live = running.get();

        long elapsedNanos = r.startNanos == 0 ? 0
                : (live ? System.nanoTime() : r.endNanos) - r.startNanos;
        double elapsedSec = elapsedNanos / 1e9;

        long submitted = r.submitted.sum();
        double achieved = elapsedSec <= 0 ? 0 : submitted / elapsedSec;

        long[] lat = Arrays.copyOf(r.latencies, Math.min(r.latencyCount.get(), r.latencies.length));
        Arrays.sort(lat);

        return new LoadTest.Status(
                live,
                r.phase,
                req == null ? 0 : req.targetPerSec(),
                req == null ? 0 : req.durationSec(),
                round(elapsedSec, 2),
                submitted,
                r.accepted.sum(),
                r.rejected.sum(),
                r.errors.sum(),
                round(achieved, 1),
                pct(lat, 50), pct(lat, 95), pct(lat, 99), pct(lat, 100),
                r.rejectReasons.entrySet().stream()
                        .sorted(Comparator.comparingLong((Map.Entry<String, LongAdder> e) -> e.getValue().sum()).reversed())
                        .limit(6)
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().sum(),
                                (a, b) -> a, LinkedHashMap::new)),
                List.copyOf(r.errorSamples),
                "dse-cert".equalsIgnoreCase(exchangeMode) || "dse-prod".equalsIgnoreCase(exchangeMode)
                        ? "FIX (" + exchangeMode + ")" : "in-process simulator",
                req == null ? 0 : req.threads(),
                dbPoolSize,
                r.note);
    }

    // ---------------------------------------------------------------- the run

    private void drive(Run run, LoadTest.Request req, BigDecimal price) {
        int threads = req.threads();
        CountDownLatch done = new CountDownLatch(threads);
        AtomicLong slot = new AtomicLong();

        long total = (long) req.targetPerSec() * req.durationSec();
        double nanosPerOrder = 1_000_000_000.0 / req.targetPerSec();

        run.startNanos = System.nanoTime();
        run.endNanos = run.startNanos;
        run.phase = "RUNNING";
        log.info("Load test starting: {} orders/sec for {}s ({} orders) on {} thread(s), strategy={}",
                req.targetPerSec(), req.durationSec(), total, threads, req.strategy());

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        long n = slot.getAndIncrement();
                        if (n >= total) {
                            return;
                        }
                        // Wait for this order's slot on the wall clock. Falling behind here is the
                        // signal we are looking for, so we never skip or reschedule a slot.
                        long due = run.startNanos + (long) (n * nanosPerOrder);
                        long wait = due - System.nanoTime();
                        if (wait > 0) {
                            sleepNanos(wait);
                        }
                        placeOne(run, req, price);
                    }
                } finally {
                    done.countDown();
                }
            }, "loadtest-" + i);
            t.setDaemon(true);
            t.start();
        }

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            run.endNanos = System.nanoTime();
            run.phase = "DONE";
            running.set(false);
            log.info("Load test finished: submitted={} accepted={} rejected={} errors={} in {}s",
                    run.submitted.sum(), run.accepted.sum(), run.rejected.sum(), run.errors.sum(),
                    round((run.endNanos - run.startNanos) / 1e9, 2));
        }
    }

    private void placeOne(Run run, LoadTest.Request req, BigDecimal price) {
        OrderRequest order = new OrderRequest(
                req.accountId(), req.securityId(), req.side(),
                "MARKET".equalsIgnoreCase(req.strategy()) ? "MARKET" : "LIMIT",
                "NORMAL", "DAY", null,
                price, null, req.quantity(), req.dealerId());

        long t0 = System.nanoTime();
        try {
            OrderService.PlaceResult r = orders.place(order, "loadtest");
            record(run, System.nanoTime() - t0);
            if (r.risk().pass()) {
                run.accepted.increment();
            } else {
                run.rejected.increment();
                run.rejectReasons.computeIfAbsent(r.risk().reason(), k -> new LongAdder()).increment();
            }
        } catch (Exception e) {
            record(run, System.nanoTime() - t0);
            run.errors.increment();
            if (run.errorSamples.size() < MAX_ERROR_SAMPLES) {
                run.errorSamples.add(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        } finally {
            run.submitted.increment();
        }
    }

    private void record(Run run, long nanos) {
        int i = run.latencyCount.getAndIncrement();
        long[] lat = run.latencies;
        if (i < lat.length) {
            lat[i] = nanos;
        }
    }

    // ---------------------------------------------------------------- helpers

    private LoadTest.Request validate(LoadTest.Request r) {
        int target = clamp(r.targetPerSec(), 1, MAX_TARGET_PER_SEC);
        int duration = clamp(r.durationSec(), 1, MAX_DURATION_SEC);
        long qty = r.quantity() <= 0 ? 100 : r.quantity();
        String side = r.side() == null || r.side().isBlank() ? "BUY" : r.side().toUpperCase();
        String strategy = r.strategy() == null || r.strategy().isBlank() ? "RESTING" : r.strategy().toUpperCase();
        if (!List.of("RESTING", "CROSSING", "MARKET").contains(strategy)) {
            throw new IllegalArgumentException("strategy must be RESTING, CROSSING or MARKET");
        }
        // More submitters than the DB pool can serve only queues work up; a few beyond it is enough
        // to keep the pool saturated.
        int threads = r.threads() > 0
                ? Math.min(r.threads(), 128)
                : Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 4), 32);
        return new LoadTest.Request(target, duration, r.accountId(), r.securityId(), r.dealerId(),
                qty, side, strategy, r.primeAccount(), threads);
    }

    /** RESTING orders are priced far from the market so they never cross; the others are marketable. */
    private BigDecimal priceFor(Security sec, LoadTest.Request req) {
        BigDecimal ltp = marketRepo.findById(sec.getId())
                .map(MarketData::getLtp)
                .filter(p -> p != null && p.signum() > 0)
                .orElse(new BigDecimal("100.00"));

        if ("MARKET".equalsIgnoreCase(req.strategy())) {
            return null;                                   // market orders carry no price
        }
        boolean buy = "BUY".equals(req.side());
        BigDecimal factor = "RESTING".equalsIgnoreCase(req.strategy())
                ? (buy ? new BigDecimal("0.50") : new BigDecimal("1.50"))    // far from the market: rests
                : (buy ? new BigDecimal("1.05") : new BigDecimal("0.95"));   // through it: fills
        return ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** Room for the whole run plus slack, so latency recording never allocates on the hot path. */
    private static int capacity(LoadTest.Request r) {
        long n = (long) r.targetPerSec() * r.durationSec();
        return (int) Math.min(n + 1024, 5_000_000);
    }

    private static void sleepNanos(long nanos) {
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double pct(long[] sortedNanos, int p) {
        if (sortedNanos.length == 0) {
            return 0;
        }
        int i = p >= 100 ? sortedNanos.length - 1
                : (int) Math.min(sortedNanos.length - 1L, Math.round(p / 100.0 * sortedNanos.length) );
        return round(sortedNanos[Math.max(i, 0)] / 1_000_000.0, 2);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }
}
