package com.naztech.oms.perf;

import com.naztech.oms.api.Dtos.OrderRequest;
import com.naztech.oms.entity.AppUser;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.AppUserRepo;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * Throughput harness: places orders through the real {@link OrderService#place} path — pre-trade
 * risk, matching or FIX routing, persistence, audit, SSE — and reports what the machine actually
 * delivered, all the way through to the executions that came back.
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
 * {@code maxLoad} removes the schedule altogether and simply asks for the ceiling.
 *
 * <h2>Why randomising matters</h2>
 * Ten thousand orders for the same stock, from the same account, at the same price is not a market —
 * it is one row of {@code market_data}, one buying-power row and one book, and the number you get
 * back is mostly a measure of how well the box handles contention on three rows. Randomising the
 * instrument, the client and the quantity spreads the writes the way real flow does, and the rate
 * usually goes <em>up</em> as a result. That is not the harness flattering itself; it is the
 * single-row version having been pessimistic.
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
    private final AppUserRepo userRepo;
    private final OrderPhaseTimings timings;
    private final ExecutionStats execStats;

    @Value("${exchange.mode:simulator}")
    private String exchangeMode;

    @Value("${spring.datasource.hikari.maximum-pool-size:10}")
    private int dbPoolSize;

    /** The local exchange's control endpoint — where the outcome mix is set. Blank against a real venue. */
    @Value("${market.venue-control-url:http://127.0.0.1:15001}")
    private String venueControlUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(500)).build();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Run current = new Run();

    public LoadTestService(OrderService orders, SecurityRepo securityRepo, ClientAccountRepo accountRepo,
                           MarketDataRepo marketRepo, AppUserRepo userRepo, OrderPhaseTimings timings,
                           ExecutionStats execStats) {
        this.orders = orders;
        this.securityRepo = securityRepo;
        this.accountRepo = accountRepo;
        this.marketRepo = marketRepo;
        this.userRepo = userRepo;
        this.timings = timings;
        this.execStats = execStats;
    }

    /**
     * The pool of instruments, clients and dealers a randomised run draws from, resolved once at the
     * start. Reading them per order would make the harness a benchmark of {@code SELECT * FROM security}.
     */
    private record Pool(List<Long> securityIds, List<BigDecimal> refPrices, List<Integer> lotSizes,
                        List<Long> accountIds, List<Long> dealerIds) {

        int instruments() {
            return securityIds.size();
        }
    }

    /** Everything one run accumulates. Replaced wholesale at the start of the next run. */
    private static final class Run {
        volatile LoadTest.Request req;
        volatile Pool pool;
        volatile String phase = "IDLE";
        volatile String note = "";
        volatile long startNanos;
        volatile long endNanos;
        final LongAdder generated = new LongAdder();
        final LongAdder submitted = new LongAdder();
        final LongAdder accepted = new LongAdder();
        final LongAdder rejected = new LongAdder();
        final LongAdder errors = new LongAdder();
        final Map<String, LongAdder> rejectReasons = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<String> errorSamples = new ConcurrentLinkedQueue<>();
        volatile long[] latencies = new long[0];      // nanos, filled up to latencyCount
        final AtomicInteger latencyCount = new AtomicInteger();
        final AtomicLong slot = new AtomicLong();     // next order index the pacer will hand out
        volatile Thread driver;
    }

    public synchronized void start(LoadTest.Request raw) {
        if (running.get()) {
            throw new IllegalStateException("A load test is already running");
        }
        LoadTest.Request req = validate(raw);
        Pool pool = buildPool(req);

        Run run = new Run();
        run.req = req;
        run.pool = pool;
        run.phase = "STARTING";
        run.latencies = new long[capacity(req)];
        current = run;
        timings.reset();                       // the breakdown must describe THIS run, not the last one
        execStats.reset();
        running.set(true);

        run.note = prime(req, pool) + configureVenue(req);

        Thread driver = new Thread(() -> drive(run, req, pool), "loadtest-driver");
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

        long generated = r.generated.sum();
        long submitted = r.submitted.sum();
        double achieved = elapsedSec <= 0 ? 0 : submitted / elapsedSec;

        // How far the pacer has fallen behind the wall clock: slots that came due and nobody took.
        // Under maxLoad there is no schedule, so there is nothing to be behind.
        long backlog = 0;
        if (live && req != null && !req.maxLoad() && req.targetPerSec() > 0) {
            long dueByNow = (long) (elapsedSec * req.targetPerSec());
            backlog = Math.max(0, Math.min(dueByNow, total(req)) - r.slot.get());
        }

        long[] lat = Arrays.copyOf(r.latencies, Math.min(r.latencyCount.get(), r.latencies.length));
        Arrays.sort(lat);

        // Mean end-to-end minus the phases = what happens outside place(): the transaction commit
        // (flush + fsync) and the proxy. Nothing inside the method can time that, and on a hot path
        // it is exactly the sort of thing that turns out to be the bottleneck.
        Map<String, Double> phases = new LinkedHashMap<>(timings.meanMillis());
        if (!phases.isEmpty() && lat.length > 0) {
            double meanTotalMs = Arrays.stream(lat).average().orElse(0) / 1_000_000.0;
            double inMethod = phases.values().stream().mapToDouble(Double::doubleValue).sum();
            phases.put("COMMIT", Math.max(0, round(meanTotalMs - inMethod, 2)));
            phases.put("TOTAL", round(meanTotalMs, 2));
        }

        Map<String, Object> ex = execStats.snapshot();

        return new LoadTest.Status(
                live,
                r.phase,
                req == null ? 0 : req.targetPerSec(),
                req == null ? 0 : req.durationSec(),
                round(elapsedSec, 2),
                generated,
                submitted,
                r.accepted.sum(),
                r.rejected.sum(),
                r.errors.sum(),
                Math.max(0, generated - submitted),
                backlog,
                round(achieved, 1),
                pct(lat, 50), pct(lat, 95), pct(lat, 99), pct(lat, 100),

                (long) ex.get("executions"),
                (long) ex.get("partialFills"),
                (long) ex.get("fullFills"),
                (long) ex.get("exchangeRejects"),
                (long) ex.get("cancels"),
                ExecutionStats.resources(),

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
                phases,
                r.note);
    }

    // ---------------------------------------------------------------- the run

    private void drive(Run run, LoadTest.Request req, Pool pool) {
        int threads = req.threads();
        CountDownLatch done = new CountDownLatch(threads);

        long total = total(req);
        double nanosPerOrder = req.maxLoad() ? 0 : 1_000_000_000.0 / req.targetPerSec();
        long deadline = System.nanoTime() + req.durationSec() * 1_000_000_000L;

        run.startNanos = System.nanoTime();
        run.endNanos = run.startNanos;
        run.phase = "RUNNING";
        log.info("Load test starting: {} for {}s on {} thread(s), strategy={}, {} instrument(s), "
                        + "{} account(s), risk={}",
                req.maxLoad() ? "MAX LOAD (unpaced)" : req.targetPerSec() + " orders/sec",
                req.durationSec(), threads, req.strategy(), pool.instruments(), pool.accountIds().size(),
                req.bypassValidation() ? "BYPASSED" : "on");

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        long n = run.slot.getAndIncrement();
                        if (n >= total) {
                            return;
                        }
                        if (req.maxLoad()) {
                            // No schedule: go as fast as the box allows, and stop on the clock. Without
                            // the deadline an unpaced run would keep going until the slot count ran out,
                            // which on a fast box is a different duration than the one that was asked for.
                            if (System.nanoTime() >= deadline) {
                                return;
                            }
                        } else {
                            // Wait for this order's slot on the wall clock. Falling behind here is the
                            // signal we are looking for, so we never skip or reschedule a slot.
                            long wait = run.startNanos + (long) (n * nanosPerOrder) - System.nanoTime();
                            if (wait > 0) {
                                sleepNanos(wait);
                            }
                        }
                        placeOne(run, req, pool);
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

    /**
     * Build one order. Everything the request asked to randomise is drawn here — instrument, client,
     * dealer, side, quantity, price — from the pool resolved at the start of the run.
     */
    private void placeOne(Run run, LoadTest.Request req, Pool pool) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();

        int idx = req.randomSecurity() ? rnd.nextInt(pool.instruments()) : 0;
        Long securityId = pool.securityIds().get(idx);
        Long accountId = pick(pool.accountIds(), req.randomAccount(), rnd);
        Long dealerId = pool.dealerIds().isEmpty() ? req.dealerId()
                : pick(pool.dealerIds(), req.randomDealer(), rnd);

        String side = req.randomSide() ? (rnd.nextBoolean() ? "BUY" : "SELL") : req.side();
        int lot = Math.max(1, pool.lotSizes().get(idx));
        long qty = req.randomQty()
                ? Math.max(lot, (rnd.nextLong(req.qtyMin(), req.qtyMax() + 1) / lot) * lot)   // whole lots only
                : req.quantity();

        BigDecimal price = priceFor(pool.refPrices().get(idx), side, req);
        if (price != null && req.randomPrice() && req.priceJitterPct() > 0) {
            double j = 1 + (rnd.nextDouble() * 2 - 1) * req.priceJitterPct() / 100.0;
            price = price.multiply(BigDecimal.valueOf(j)).setScale(2, RoundingMode.HALF_UP);
            if (price.signum() <= 0) {
                price = new BigDecimal("0.10");
            }
        }

        OrderRequest order = new OrderRequest(
                accountId, securityId, side,
                "MARKET".equalsIgnoreCase(req.strategy()) ? "MARKET" : "LIMIT",
                "NORMAL", "DAY", null,
                price, null, qty, dealerId);

        run.generated.increment();
        long t0 = System.nanoTime();
        try {
            OrderService.PlaceResult r = orders.place(order, "loadtest", req.bypassValidation());
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

    private static Long pick(List<Long> from, boolean random, ThreadLocalRandom rnd) {
        if (from.isEmpty()) {
            return null;
        }
        return from.get(random ? rnd.nextInt(from.size()) : 0);
    }

    private void record(Run run, long nanos) {
        int i = run.latencyCount.getAndIncrement();
        long[] lat = run.latencies;
        if (i < lat.length) {
            lat[i] = nanos;
        }
    }

    // ---------------------------------------------------------------- setup

    /**
     * Resolve the instruments, clients and dealers this run will draw from — once, up front. An order
     * that names one security and one account still gets a pool; it is just a pool of one.
     */
    private Pool buildPool(LoadTest.Request req) {
        List<Security> securities;
        if (req.randomSecurity()) {
            securities = securityRepo.findAll().stream()
                    .filter(s -> "ACTIVE".equals(s.getStatus()))
                    .filter(s -> !"INDEX".equals(s.getAssetClass()))     // indices are not tradable
                    .toList();
            if (securities.isEmpty()) {
                throw new IllegalArgumentException("No tradable securities to randomise over");
            }
        } else {
            Security s = securityRepo.findById(req.securityId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown security " + req.securityId()));
            securities = List.of(s);
        }

        List<Long> securityIds = new ArrayList<>(securities.size());
        List<BigDecimal> refPrices = new ArrayList<>(securities.size());
        List<Integer> lotSizes = new ArrayList<>(securities.size());
        Map<Long, BigDecimal> ltps = marketRepo.findAll().stream()
                .filter(m -> m.getLtp() != null && m.getLtp().signum() > 0)
                .collect(Collectors.toMap(MarketData::getSecurityId, MarketData::getLtp, (a, b) -> a));
        for (Security s : securities) {
            securityIds.add(s.getId());
            refPrices.add(ltps.getOrDefault(s.getId(), new BigDecimal("100.00")));
            lotSizes.add(s.getLotSize() == null ? 1 : s.getLotSize());
        }

        List<Long> accountIds;
        if (req.randomAccount()) {
            accountIds = accountRepo.findAll().stream()
                    .filter(a -> a.getStatus() == null || "ACTIVE".equals(a.getStatus()))
                    .map(ClientAccount::getId)
                    .toList();
            if (accountIds.isEmpty()) {
                throw new IllegalArgumentException("No active client accounts to randomise over");
            }
        } else {
            ClientAccount acc = accountRepo.findById(req.accountId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown account " + req.accountId()));
            accountIds = List.of(acc.getId());
        }

        List<Long> dealerIds = List.of();
        if (req.randomDealer()) {
            dealerIds = userRepo.findAll().stream()
                    .filter(u -> "DEALER".equalsIgnoreCase(u.getRole()) || "TRADER".equalsIgnoreCase(u.getRole()))
                    .filter(u -> u.getStatus() == null || "ACTIVE".equals(u.getStatus()))
                    .map(AppUser::getId)
                    .toList();
        } else if (req.dealerId() != null) {
            dealerIds = List.of(req.dealerId());
        }

        return new Pool(securityIds, refPrices, lotSizes, accountIds, dealerIds);
    }

    /** Top up buying power so a filling run measures throughput and not the moment the money ran out. */
    private String prime(LoadTest.Request req, Pool pool) {
        if (!req.primeAccount()) {
            return "RESTING".equalsIgnoreCase(req.strategy()) ? ""
                    : "Not primed: fills will drain buying power and later orders will be rejected. ";
        }
        List<ClientAccount> accounts = accountRepo.findAllById(pool.accountIds());
        for (ClientAccount a : accounts) {
            a.setBuyingPower(PRIMED_BUYING_POWER);
        }
        accountRepo.saveAll(accounts);
        return "Buying power primed on " + accounts.size() + " account(s). ";
    }

    /**
     * Tell the venue how to treat this run's orders. Only the local exchange listens; a real one has
     * its own opinions about what to reject, and would not take instruction from us.
     */
    private String configureVenue(LoadTest.Request req) {
        int reject = req.rejectPct();
        int partial = req.partialPct();
        if (venueControlUrl == null || venueControlUrl.isBlank()) {
            return "";
        }
        boolean fix = "dse-cert".equalsIgnoreCase(exchangeMode) || "dse-prod".equalsIgnoreCase(exchangeMode);
        if (!fix) {
            return reject + partial > 0
                    ? "Outcome mix ignored: it is set at the exchange, and this run routes to the in-process simulator. "
                    : "";
        }
        try {
            HttpRequest post = HttpRequest.newBuilder()
                    .uri(URI.create(venueControlUrl + "/config?rejectPct=" + reject
                            + "&partialPct=" + partial + "&partialFillPct=" + req.partialFillPct()))
                    .timeout(Duration.ofMillis(800))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            http.send(post, HttpResponse.BodyHandlers.discarding());
            return "Exchange set to reject " + reject + "%, partially fill " + partial
                    + "% (at " + req.partialFillPct() + "%). ";
        } catch (Exception e) {
            log.warn("Could not set the outcome mix at {}: {}", venueControlUrl, e.toString());
            return "Could not reach the exchange to set the outcome mix — it will fill everything. ";
        }
    }

    // ---------------------------------------------------------------- helpers

    private LoadTest.Request validate(LoadTest.Request r) {
        int target = r.maxLoad() ? MAX_TARGET_PER_SEC : clamp(r.targetPerSec(), 1, MAX_TARGET_PER_SEC);
        int duration = clamp(r.durationSec(), 1, MAX_DURATION_SEC);
        long qty = r.quantity() <= 0 ? 100 : r.quantity();
        String side = r.side() == null || r.side().isBlank() ? "BUY" : r.side().toUpperCase();
        String strategy = r.strategy() == null || r.strategy().isBlank() ? "RESTING" : r.strategy().toUpperCase();
        if (!List.of("RESTING", "CROSSING", "MARKET").contains(strategy)) {
            throw new IllegalArgumentException("strategy must be RESTING, CROSSING or MARKET");
        }
        if (!r.randomSecurity() && r.securityId() == null) {
            throw new IllegalArgumentException("securityId is required unless randomSecurity is set");
        }
        if (!r.randomAccount() && r.accountId() == null) {
            throw new IllegalArgumentException("accountId is required unless randomAccount is set");
        }

        long qtyMin = r.qtyMin() > 0 ? r.qtyMin() : qty;
        long qtyMax = r.qtyMax() > qtyMin ? r.qtyMax() : qtyMin;

        // More submitters than the DB pool can serve only queues work up; a few beyond it is enough
        // to keep the pool saturated.
        int threads = r.threads() > 0
                ? Math.min(r.threads(), 128)
                : Math.min(Math.max(Runtime.getRuntime().availableProcessors(), 4), 32);

        return new LoadTest.Request(target, duration, r.accountId(), r.securityId(), r.dealerId(),
                qty, side, strategy, r.primeAccount(), threads,
                r.maxLoad(), r.randomSecurity(), r.randomAccount(), r.randomDealer(),
                r.randomQty(), qtyMin, qtyMax, r.randomSide(),
                r.randomPrice(), r.priceJitterPct() <= 0 ? 1.0 : Math.min(r.priceJitterPct(), 50),
                r.bypassValidation(),
                clamp(r.rejectPct(), 0, 100), clamp(r.partialPct(), 0, 100),
                r.partialFillPct() <= 0 ? 50 : clamp(r.partialFillPct(), 1, 99));
    }

    /** RESTING orders are priced far from the market so they never cross; the others are marketable. */
    private BigDecimal priceFor(BigDecimal ltp, String side, LoadTest.Request req) {
        if ("MARKET".equalsIgnoreCase(req.strategy())) {
            return null;                                   // market orders carry no price
        }
        boolean buy = "BUY".equals(side);
        BigDecimal factor = "RESTING".equalsIgnoreCase(req.strategy())
                ? (buy ? new BigDecimal("0.50") : new BigDecimal("1.50"))    // far from the market: rests
                : (buy ? new BigDecimal("1.05") : new BigDecimal("0.95"));   // through it: fills
        return ltp.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * How many orders the run may produce. An unpaced run has no rate to multiply by, so it is
     * bounded by the clock instead and given a generous slot ceiling to run against.
     */
    private static long total(LoadTest.Request r) {
        return r.maxLoad()
                ? (long) MAX_TARGET_PER_SEC * r.durationSec()
                : (long) r.targetPerSec() * r.durationSec();
    }

    /** Room for the whole run plus slack, so latency recording never allocates on the hot path. */
    private static int capacity(LoadTest.Request r) {
        return (int) Math.min(total(r) + 1024, 5_000_000);
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
                : (int) Math.min(sortedNanos.length - 1L, Math.round(p / 100.0 * sortedNanos.length));
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
