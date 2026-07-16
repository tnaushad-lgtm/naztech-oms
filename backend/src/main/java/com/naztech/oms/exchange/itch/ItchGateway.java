package com.naztech.oms.exchange.itch;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.exchange.config.ItchProperties;
import com.naztech.oms.marketstore.HotStore;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.MarketDataGateway;
import com.naztech.oms.service.MarketDataService;
import com.naztech.oms.service.StreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live DSE ITCH market-data source (active when {@code itch.enabled=true}). It runs the
 * {@link ItchSimulator}, pushes every message through the real binary {@link ItchCodec} (so the
 * exact wire path is exercised), rebuilds a per-security {@link ItchOrderBook}, and drives the OMS
 * market picture — last-traded price, tape and indices — through the existing {@link MarketDataService}.
 * The order-book depth the UI shows now comes from ITCH.
 *
 * <p>In production the {@link ItchSimulator} is swapped for a SoupBinTCP/MoldUDP64 reader of the real
 * DSE feed (Phase 4b) — this class and the whole OMS above it are unchanged.
 */
@Component
@ConditionalOnProperty(prefix = "itch", name = "enabled", havingValue = "true")
public class ItchGateway implements MarketDataGateway {

    private static final Logger log = LoggerFactory.getLogger(ItchGateway.class);
    private static final int PRICE_DECIMALS = 2;
    private static final int INDEX_DECIMALS = 2;   // DSE index level is scaled by 100 on the [Z] feed
    private static final int DEPTH_LEVELS = 10;   // what a ladder shows; enough for the terminal

    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;
    private final MarketDataService marketData;
    private final StreamService stream;
    private final ItchProperties props;
    private final HotStore hot;

    private final Map<Long, ItchOrderBook> books = new ConcurrentHashMap<>();   // securityId → book
    private final Map<Long, Long> orderToSecurity = new ConcurrentHashMap<>();  // orderNumber → securityId
    private final Map<Long, String> symbols = new ConcurrentHashMap<>();        // securityId → symbol
    private final List<Long> indexIds = new ArrayList<>();
    private final Object lock = new Object();
    private final Random rnd = new Random();
    private ItchSource source;
    private volatile boolean ready = false;
    /** False while the market is halted: the books stand, but no new messages are pulled. */
    private volatile boolean feedLive = false;

    public ItchGateway(SecurityRepo securityRepo, MarketDataRepo marketRepo, MarketDataService marketData,
                       StreamService stream, ItchProperties props, HotStore hot) {
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.marketData = marketData;
        this.stream = stream;
        this.props = props;
        this.hot = hot;
    }

    /**
     * Opens the trading day: broadcasts the ITCH day-start sequence, then brings the feed up.
     *
     * <p>The feed no longer starts itself at boot — {@code MarketSessionService} owns the session, and
     * a market-data feed running while the market is closed is exactly the fiction this control was
     * built to remove. Called on Start Market, and on startup if the persisted session says the
     * market was open.
     *
     * <p>The day-start messages go through the same {@link #wire} codec round-trip as live traffic,
     * so a client rebuilding its world from the feed sees the real binary sequence: session start,
     * tick tables, companies, the instrument directory, then "this book is trading".
     */
    public void openMarket() {
        synchronized (lock) {
            if (ready) {
                log.debug("ITCH: already open");
                return;
            }
            // A real venue (SoupBinTCP / MoldUDP64 — nFIX or DSE) sends its own day-start sequence and
            // owns the books. We must NOT invent our own directory on top of it: two directories for the
            // same order-book ids is how a feed handler ends up showing phantom liquidity nobody placed.
            // We still pre-create an empty book and remember the ticker for each order-book id, so the
            // depth the venue streams maps straight onto the instrument the dealer is looking at — which
            // works precisely because our security.id IS the order-book id (the master is built from the
            // feed). The simulator, by contrast, is our own venue, so there we do broadcast and seed.
            boolean liveVenue = isLiveTransport();

            List<Security> listed = new ArrayList<>();
            List<ItchSimulator.Instrument> instruments = new ArrayList<>();
            for (Security s : securityRepo.findAll()) {
                if (!"ACTIVE".equals(s.getStatus())) continue;
                if ("INDEX".equals(s.getAssetClass())) { indexIds.add(s.getId()); continue; }
                symbols.put(s.getId(), s.getSymbol());
                books.put(s.getId(), new ItchOrderBook());
                listed.add(s);
                long ref = refPriceRaw(s.getId());
                long tick = tickRaw(s);
                instruments.add(new ItchSimulator.Instrument(s.getId(), s.getSymbol(), s.getName(), ref, PRICE_DECIMALS, tick));
            }
            if (instruments.isEmpty()) { log.warn("ITCH: no active equities to map"); return; }

            long ts = System.currentTimeMillis() / 1000;
            int broadcast = 0;
            if (!liveVenue) {
                // The morning broadcast: system event -> tick tables -> companies -> instrument directory
                // -> trading action. Only for our own simulator; a real venue does this itself.
                for (Itch.Msg m : ItchDayBroadcast.open(listed, ts, PRICE_DECIMALS)) {
                    route(wire(m));
                    broadcast++;
                }
            }

            try {
                source = buildSource(instruments);
            } catch (IOException e) {
                if (liveVenue) {
                    // A live feed that will not connect must fail loudly, not silently pretend to be a
                    // market by falling back to the simulator — a fabricated book is worse than no book.
                    log.error("ITCH: could not connect to the live feed ({}) — market data is DOWN", e.toString());
                    return;
                }
                log.error("ITCH: could not open configured source ({}) — falling back to simulator", e.toString());
                source = new SimulatorSource(instruments, props.getSeed(), props.getBurst());
            }
            for (Itch.Msg m : source.open()) route(wire(m));   // empty for a live feed; seeds the sim
            ready = true;
            feedLive = true;
            log.info("ITCH MARKET OPEN — {} ({} instruments mapped, {} indices), source='{}', transport='{}'",
                    liveVenue ? "consuming the live venue feed" : ("broadcast " + broadcast + " day-start messages"),
                    instruments.size(), indexIds.size(), source.name(), props.getTransport());
        }
    }

    /** Closes the trading day: end-of-day ITCH messages, feed down, books cleared. */
    public void closeMarket() {
        synchronized (lock) {
            if (ready) {
                long ts = System.currentTimeMillis() / 1000;
                List<Security> listed = securityRepo.findAll().stream()
                        .filter(s -> "ACTIVE".equals(s.getStatus()) && !"INDEX".equals(s.getAssetClass()))
                        .toList();
                for (Itch.Msg m : ItchDayBroadcast.close(listed, ts)) {
                    route(wire(m));
                }
            }
            ready = false;
            feedLive = false;
            if (source != null) {
                try { source.close(); } catch (IOException e) { log.debug("ITCH source close: {}", e.toString()); }
                source = null;
            }
            books.clear();
            orderToSecurity.clear();
            symbols.clear();
            indexIds.clear();
            log.info("ITCH MARKET CLOSED — feed stopped, books cleared");
        }
    }

    /** Mid-session halt: the feed stays connected, the books stand, but nothing new flows. */
    public void haltFeed(boolean halted) {
        synchronized (lock) {
            if (ready) {
                long ts = System.currentTimeMillis() / 1000;
                List<Security> listed = securityRepo.findAll().stream()
                        .filter(s -> "ACTIVE".equals(s.getStatus()) && !"INDEX".equals(s.getAssetClass()))
                        .toList();
                for (Itch.Msg m : ItchDayBroadcast.tradingAction(listed, ts, halted ? 'H' : 'T')) {
                    route(wire(m));
                }
            }
            feedLive = !halted;
            log.info("ITCH feed {}", halted ? "HALTED" : "RESUMED");
        }
    }

    /**
     * Select the message source from config. A replay capture wins if one is configured; otherwise the
     * transport decides — a real SoupBinTCP session, a real MoldUDP64 multicast feed, or the simulator.
     * Tee to a recorder when {@code itch.record=true}.
     *
     * <p>This method is the <em>only</em> thing that knows where ITCH bytes come from. Everything above
     * it — the books, market data, depth, the terminal — is identical whether the feed is DSE's or a
     * simulation, which is the point of the {@link ItchSource} seam and the reason switching to the
     * real exchange is a config change rather than a project.
     *
     * <p>A live transport that cannot connect throws, and the caller falls back to the simulator with a
     * loud log line. It must never fall back silently: a feed producing nothing looks exactly like a
     * market with no trades in it, and those are very different situations.
     */
    private ItchSource buildSource(List<ItchSimulator.Instrument> instruments) throws IOException {
        ItchSource base;
        if (props.isReplay() && props.getReplayFile() != null && !props.getReplayFile().isBlank()) {
            base = new FileReplaySource(Path.of(props.getReplayFile()), props.getBurst());
        } else {
            base = switch (String.valueOf(props.getTransport()).toLowerCase()) {
                case "soupbintcp" -> new SoupBinTcpSource(props.getHost(), props.getPort(),
                        props.getUsername(), props.getPassword(), props.getSession());
                case "moldudp64" -> new MoldUdp64Source(props.getGroup(), props.getPort(),
                        props.getSession(), props.getRewindHost(), props.getRewindPort(),
                        props.getNetworkInterface());
                default -> new SimulatorSource(instruments, props.getSeed(), props.getBurst());
            };
        }
        if (props.isRecord() && props.getRecordFile() != null && !props.getRecordFile().isBlank()) {
            base = new RecordingSource(base, new ItchSessionWriter(Path.of(props.getRecordFile())));
        }
        return base;
    }

    /**
     * How the live feed is doing: gaps seen, gaps recovered, messages given up on.
     *
     * <p>{@code lost > 0} is the number that matters. It does not mean "the feed hiccupped" — gaps
     * happen and are recovered — it means we asked for messages and never got them, so every book built
     * since is suspect and wants a fresh snapshot. The simulator and a replay cannot lose anything, and
     * report nothing.
     */
    /** Milliseconds since the live feed last sent anything (data or heartbeat). -1 if not a live feed. */
    public long feedIdleMs() {
        ItchSource s = source;
        if (s instanceof SoupBinTcpSource soup) {
            return soup.msSinceLastPacket();
        }
        if (s instanceof MoldUdp64Source) {
            return 0;   // multicast has no per-source idle clock here; treat as live while the source runs
        }
        return -1;
    }

    public ItchSequencer.Health feedHealth() {
        ItchSource s = source;
        if (s instanceof SoupBinTcpSource soup) {
            return soup.health();
        }
        if (s instanceof MoldUdp64Source mold) {
            return mold.health();
        }
        return null;
    }

    @EventListener(ContextClosedEvent.class)
    public void shutdown() {
        synchronized (lock) {
            ready = false;
            feedLive = false;
            if (source != null) {
                try { source.close(); } catch (IOException e) { log.debug("ITCH source close: {}", e.toString()); }
                source = null;
            }
        }
    }

    /** Is the feed up? Reported on the connectivity screen. */
    public boolean isLive() {
        return ready && feedLive;
    }

    /** Stream continuous ITCH market activity — only while the market is actually open. */
    @Scheduled(fixedDelayString = "${itch.tick-ms:1200}")
    public void tick() {
        if (!ready || !feedLive || source == null) return;
        synchronized (lock) {
            // If the venue restarted (its ITCH sequence rolled back), throw away the stale books before
            // consuming the replay — otherwise resting orders from the previous session linger as
            // phantom liquidity next to the fresh book. The replay then rebuilds each book from scratch.
            if (source instanceof SoupBinTcpSource soup && soup.consumeSessionReset()) {
                log.warn("ITCH: venue restarted — clearing {} books to rebuild from the replay", books.size());
                for (ItchOrderBook b : books.values()) b.clear();
                orderToSecurity.clear();
            }
            for (Itch.Msg m : source.poll()) route(wire(m));
            // Only nudge indices for our own simulator. A live venue sends real index values ([Z]);
            // faking drift on top of them would fight the real feed and mislead the desk.
            if (!isLiveTransport()) driftIndices();
            if (props.isValidate()) validateBooks();
            snapshotDepth();
        }
        stream.publish("market", Map.of("type", "itch", "ts", 0));
    }

    /**
     * Publish each book's ladder to the hot store, so depth can be served without this JVM's book —
     * by a second OMS instance, or by anything else that needs it. Today the book lives in a HashMap
     * here and nowhere else: restart the process and the depth is simply gone.
     */
    private void snapshotDepth() {
        if (!hot.isLive()) {
            return;
        }
        for (Long securityId : books.keySet()) {
            ItchOrderBook b = books.get(securityId);
            if (b == null) continue;
            hot.putDepth(securityId, b.depth(symbols.getOrDefault(securityId, "?"), ltp(securityId),
                    PRICE_DECIMALS, DEPTH_LEVELS));
        }
    }

    /** Opt-in ({@code itch.validate=true}): surface any order-book invariant violations from the live feed. */
    private void validateBooks() {
        for (Map.Entry<Long, ItchOrderBook> e : books.entrySet()) {
            ItchBookInvariants.Report r = ItchBookInvariants.check(e.getValue(), PRICE_DECIMALS, 10);
            if (!r.ok()) log.warn("ITCH book invariant [{}]: {}", symbols.getOrDefault(e.getKey(), "?"), r.violations());
        }
    }

    // ------------------------------------------------------------------ MarketDataGateway
    @Override
    public Depth depth(Long securityId, int levels) {
        synchronized (lock) {
            ItchOrderBook b = books.get(securityId);
            String sym = symbols.getOrDefault(securityId, "?");
            BigDecimal ltp = ltp(securityId);
            if (b == null) return new Depth(sym, ltp, List.of(), List.of());
            return b.depth(sym, ltp, PRICE_DECIMALS, levels);
        }
    }

    @Override
    public String source() { return "itch"; }

    // ------------------------------------------------------------------ routing
    /** A real exchange transport (nFIX / DSE), as opposed to our in-process simulator or a replay file. */
    private boolean isLiveTransport() {
        String t = props.getTransport();
        return !props.isReplay() && ("soupbintcp".equalsIgnoreCase(t) || "moldudp64".equalsIgnoreCase(t));
    }

    /** Round-trip through the binary codec, exactly as a real client decodes the wire. */
    private Itch.Msg wire(Itch.Msg m) { return ItchCodec.decode(ItchCodec.encode(m)); }

    private void route(Itch.Msg m) {
        switch (m.type()) {
            case 'A' -> { Itch.AddOrder a = (Itch.AddOrder) m;
                if (!Itch.isReferencePrice(a)) { orderToSecurity.put(a.orderNumber(), a.orderbook()); book(a.orderbook()).apply(a); } }
            case 'F' -> { Itch.AddOrderParticipant f = (Itch.AddOrderParticipant) m;
                orderToSecurity.put(f.orderNumber(), f.orderbook()); book(f.orderbook()).apply(f); }
            case 'E' -> { Itch.OrderExecuted e = (Itch.OrderExecuted) m; Long sid = orderToSecurity.get(e.orderNumber());
                if (sid != null) book(sid).apply(e); }
            case 'C' -> { Itch.OrderExecutedWithPrice c = (Itch.OrderExecutedWithPrice) m; Long sid = orderToSecurity.get(c.orderNumber());
                if (sid != null) book(sid).apply(c); }
            case 'D' -> { Itch.OrderDelete d = (Itch.OrderDelete) m; Long sid = orderToSecurity.remove(d.orderNumber());
                if (sid != null) book(sid).apply(d); }
            case 'U' -> { Itch.OrderReplace u = (Itch.OrderReplace) m; Long sid = orderToSecurity.remove(u.origOrderNumber());
                if (sid != null) { book(sid).apply(u); orderToSecurity.put(u.newOrderNumber(), sid); } }
            case 'Q' -> onTrade((Itch.Trade) m);
            case 'Z' -> onIndex((Itch.IndexValue) m);
            default -> { /* T/S/R/H/O/I/N — not book-affecting here */ }
        }
    }

    /**
     * A live index value from the venue (DSEX = book 9001). The index feed carries the level scaled by
     * 100, so we shift it and write it onto the index instrument — which is why the Index Board was
     * empty until now: the venue was sending [Z] all along and we were dropping it, and there was no
     * index security to receive it. Both are fixed: the DSEX row exists (id = the index book id) and
     * this consumes the feed.
     */
    private void onIndex(Itch.IndexValue z) {
        BigDecimal value = BigDecimal.valueOf(z.value()).movePointLeft(INDEX_DECIMALS);
        marketData.applyIndex(z.indexOrderbook(), value);
        stream.publish("indices", Map.of("ts", 0));
    }

    private void onTrade(Itch.Trade q) {
        if (Itch.isClosePrice(q)) return;
        Long sid = q.orderbook();
        ItchOrderBook b = books.get(sid);
        if (b == null) return;
        BigDecimal px = scale(q.execPrice());
        BigDecimal bestBid = b.bestBidRaw() < 0 ? null : scale(b.bestBidRaw());
        BigDecimal bestAsk = b.bestAskRaw() < 0 ? null : scale(b.bestAskRaw());
        marketData.applyTrade(sid, px, q.execQty(), bestBid, bestAsk);
        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("securityId", sid);
        tick.put("symbol", symbols.getOrDefault(sid, "?"));
        tick.put("price", px);
        tick.put("qty", q.execQty());
        tick.put("side", "");
        tick.put("ts", String.valueOf(q.ts()));
        stream.publish("trade", tick);
    }

    /**
     * Nudge index values so the index board stays alive under the ITCH feed.
     *
     * <p><b>This used to be an unbounded upward ratchet.</b> The step was
     * {@code (nextDouble() - 0.48) * 0.0015}, and {@code nextDouble()} averages 0.5 — so the mean step
     * was <em>positive</em>, about +0.003% per tick. A tick every 1.2 seconds compounds that to roughly
     * +9% an hour, and the result was written straight back to MySQL, which is the part that made it
     * fatal: nothing reset it, so each run resumed from the inflated number and pushed on. Left open
     * over a few days, DSEX had climbed from ~5,200 to <b>52 million</b>, and the AI advisor — quite
     * correctly — read that figure out to the dealer.
     *
     * <p>Two things fix it, and both are what a real index actually does:
     * <ul>
     *   <li>the step is <b>centred</b> ({@code nextDouble() - 0.5}), so it is a walk and not a climb;</li>
     *   <li>it is <b>anchored to yesterday's close</b> — pulled gently back toward YCP and hard-clamped
     *       to ±10% of it. A real index does not wander 8× away from where it opened, and an anchor is
     *       the only thing that stops a random walk from eventually doing exactly that.</li>
     * </ul>
     */
    private void driftIndices() {
        boolean any = false;
        for (Long id : indexIds) {
            MarketData m = marketRepo.findById(id).orElse(null);
            if (m == null || m.getLtp() == null || m.getLtp().signum() == 0) continue;

            BigDecimal ltp = m.getLtp();
            BigDecimal anchor = m.getYcp() != null && m.getYcp().signum() > 0 ? m.getYcp() : ltp;

            // A centred step, plus a light pull back toward the anchor. The pull is what makes this a
            // market that oscillates rather than one that escapes.
            double step = (rnd.nextDouble() - 0.5) * 0.0015;
            double gap = anchor.subtract(ltp).doubleValue() / anchor.doubleValue();   // >0 when below YCP
            double pull = gap * 0.02;

            BigDecimal v = ltp.multiply(BigDecimal.valueOf(1 + step + pull)).setScale(2, RoundingMode.HALF_UP);

            // And a hard band, because a slow leak over a long-running session is exactly how the last
            // one got away: a bound that is never tested is a bound that is not there.
            BigDecimal floor = anchor.multiply(BigDecimal.valueOf(0.90));
            BigDecimal ceil = anchor.multiply(BigDecimal.valueOf(1.10));
            if (v.compareTo(floor) < 0) v = floor;
            if (v.compareTo(ceil) > 0) v = ceil;

            marketData.applyIndex(id, v);
            any = true;
        }
        if (any) stream.publish("indices", Map.of("ts", 0));
    }

    // ------------------------------------------------------------------ helpers
    private ItchOrderBook book(long securityId) { return books.computeIfAbsent(securityId, k -> new ItchOrderBook()); }

    private BigDecimal scale(long rawPrice) { return BigDecimal.valueOf(rawPrice).movePointLeft(PRICE_DECIMALS); }

    private BigDecimal ltp(Long securityId) {
        MarketData m = marketRepo.findById(securityId).orElse(null);
        return m == null || m.getLtp() == null ? BigDecimal.ZERO : m.getLtp();
    }

    private long refPriceRaw(Long securityId) {
        BigDecimal ltp = ltp(securityId);
        long raw = ltp.signum() > 0 ? ltp.movePointRight(PRICE_DECIMALS).longValue() : 10_000; // default 100.00
        return Math.max(raw, 100);
    }

    private long tickRaw(Security s) {
        BigDecimal t = s.getTickSize();
        long raw = (t == null || t.signum() == 0) ? 10 : t.movePointRight(PRICE_DECIMALS).longValue();
        return Math.max(raw, 1);
    }
}
