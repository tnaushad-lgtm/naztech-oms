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
    /** Trades seen while draining a tick, persisted after the lock is released — see {@link #tick()}. */
    private final List<PendingTrade> pendingTrades = new ArrayList<>();
    /**
     * Books touched by messages in the current tick. Only these are re-published, so per-tick work is
     * proportional to what the market actually did rather than to how many instruments are listed.
     */
    private final java.util.Set<Long> dirty = new java.util.HashSet<>();
    /**
     * How many of each ITCH message type we have actually consumed.
     *
     * Added because "are we even receiving [F] Add-Order-with-Participant?" turned out to be
     * unanswerable from the outside: the book looked plausible, so a whole message class could have
     * been arriving and being dropped without any symptom except orders that never appeared. A
     * counter per type makes that visible in one call.
     */
    private final Map<Character, java.util.concurrent.atomic.LongAdder> msgCounts = new ConcurrentHashMap<>();

    /**
     * Trade persistence, off the tick thread.
     *
     * Writing trades was already moved out of the book lock, but it still ran ON the scheduled tick,
     * one MySQL round-trip per trade. A reconnect replays a whole day — tens of thousands of trades —
     * so the tick spent minutes inside the database and stopped consuming the feed entirely: 150,000
     * messages backed up behind it, the book went minutes stale, and every page that needed depth
     * queued behind the same thread. A thread dump found it parked in a MySQL socket read with 42% of
     * a core burnt.
     *
     * Now the tick hands trades to this writer and returns immediately. The books and the depth push
     * are already current before this point — persistence is for the tape, last price and volume, and
     * being a second behind on those costs nothing. Bounded and drop-loudly rather than unbounded:
     * the same trade-off QuestDbTickStore already makes, for the same reason.
     */
    private static final int TRADE_QUEUE = 50_000;
    private final java.util.concurrent.BlockingQueue<PendingTrade> tradeQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(TRADE_QUEUE);
    private final java.util.concurrent.atomic.AtomicLong tradesDropped = new java.util.concurrent.atomic.AtomicLong();
    private volatile Thread tradeWriter;

    private void startTradeWriter() {
        if (tradeWriter != null) return;
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    flushTrade(tradeQueue.take());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // One bad trade must not kill the writer and silently stop the tape.
                    log.warn("ITCH: trade persist failed ({}) — continuing", e.toString());
                }
            }
        }, "itch-trade-writer");
        t.setDaemon(true);
        t.start();
        tradeWriter = t;
    }

    /** Snapshot of the per-type message counts, for the connectivity endpoint. */
    public Map<String, Long> messageCounts() {
        Map<String, Long> out = new LinkedHashMap<>();
        msgCounts.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> out.put(String.valueOf(e.getKey()), e.getValue().sum()));
        return out;
    }
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

            // The feed is about to rebuild the entire day: a live venue replays from sequence 1, the
            // simulator generates a fresh day. Either way the day's running totals must start at zero, or
            // a restart re-adds the whole day on top of the last one and volume/turnover inflate. Zero
            // them here, before the first trade lands.
            marketData.resetDayStats(listed.stream().map(Security::getId).toList());

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
            startTradeWriter();
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
    /** The venue's ITCH session id (from Login Accepted), e.g. DSESIM01. Empty if not a live feed. */
    public String feedSession() {
        ItchSource s = source;
        return s instanceof SoupBinTcpSource soup ? soup.sessionName() : "";
    }

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
        List<PendingTrade> flush;
        synchronized (lock) {
            // If the venue restarted (its ITCH sequence rolled back), throw away the stale books before
            // consuming the replay — otherwise resting orders from the previous session linger as
            // phantom liquidity next to the fresh book. The replay then rebuilds each book from scratch.
            if (source instanceof SoupBinTcpSource soup && soup.consumeSessionReset()) {
                /*
                 * The venue restart itself is information the desk wants: after one, every working
                 * order placed before it is an orphan the exchange has forgotten, and "why is my
                 * depth empty" lands on support. The feed announces restarts implicitly — session
                 * name change + sequence rollback — so record it here and let the dashboard say it,
                 * rather than waiting for the venue to add an explicit broadcast.
                 */
                lastVenueRestartAt = java.time.Instant.now();
                lastVenueRestartSession = soup.sessionName();
                venueRestarts.incrementAndGet();
                stream.publish("session", Map.of("venueRestart", lastVenueRestartSession));
                log.warn("ITCH: venue restarted — clearing {} books to rebuild from the replay", books.size());
                for (ItchOrderBook b : books.values()) b.clear();
                orderToSecurity.clear();
                // The venue is about to replay the whole day again — zero the running totals too, or the
                // replay stacks a second day of volume/turnover on top of the first.
                marketData.resetDayStats(new ArrayList<>(books.keySet()));
            }
            for (Itch.Msg m : source.poll()) route(wire(m));   // builds books in-memory, buffers trades
            // Only nudge indices for our own simulator. A live venue sends real index values ([Z]);
            // faking drift on top of them would fight the real feed and mislead the desk.
            if (!isLiveTransport()) driftIndices();
            if (props.isValidate()) validateBooks();
            snapshotDepth();                                   // the book (in-memory) pushed while still under lock
            flush = pendingTrades.isEmpty() ? List.of() : new ArrayList<>(pendingTrades);
            pendingTrades.clear();
        }
        // Persist the trades that built the book OUTSIDE the lock. A reconnect replays the whole day at
        // once — thousands of trades — and doing those DB writes under the lock is what starved depth()
        // and the snapshot push (the "No book / waiting for a push" freeze). The books are already
        // current and pushed above; the last-price/volume/tape catch up here without holding anyone up.
        for (PendingTrade t : flush) {
            if (!tradeQueue.offer(t)) {
                long n = tradesDropped.incrementAndGet();
                if (n % 1000 == 1) {
                    log.warn("ITCH: trade-persist queue full at {} — {} trade(s) not written. The book and depth "
                            + "are unaffected; the tape and day volume will under-report.", TRADE_QUEUE, n);
                }
            }
        }
        stream.publish("market", Map.of("type", "itch", "ts", 0));
    }

    /**
     * Publish each book's ladder to the hot store, so depth can be served without this JVM's book —
     * by a second OMS instance, or by anything else that needs it. Today the book lives in a HashMap
     * here and nowhere else: restart the process and the depth is simply gone.
     */
    /**
     * Push only the books that MOVED this tick.
     *
     * This used to rebuild and publish all 306 books every 1.2s — 306 depth constructions and 306
     * Valkey round-trips per tick, inside the lock that UI depth requests also need. In a normal
     * market a handful of instruments change in any 1.2s window, so the other three hundred were
     * being recomputed and re-sent identically, and every page load queued behind that work. Pages
     * were taking 5-14 seconds.
     */
    private void snapshotDepth() {
        if (!hot.isLive() || dirty.isEmpty()) {
            dirty.clear();
            return;
        }
        for (Long securityId : dirty) {
            ItchOrderBook b = books.get(securityId);
            if (b == null) continue;
            hot.putDepth(securityId, b.depth(symbols.getOrDefault(securityId, "?"), ltp(securityId),
                    PRICE_DECIMALS, DEPTH_LEVELS));
        }
        dirty.clear();
    }

    /** Opt-in ({@code itch.validate=true}): surface any order-book invariant violations from the live feed. */
    /**
     * Check a rotating slice of the universe rather than all of it every tick.
     *
     * Checking 306 books each 1.2s doubled the per-tick work for no extra safety: a crossed book is
     * a persistent state, not a one-frame flicker, so sampling still catches it — just within about
     * twenty seconds instead of one. That is the right trade for a check that runs while holding the
     * lock the UI needs.
     */
    private static final int VALIDATE_PER_TICK = 16;
    private int validateCursor = 0;

    private void validateBooks() {
        if (books.isEmpty()) return;
        List<Long> ids = new ArrayList<>(books.keySet());
        for (int i = 0; i < Math.min(VALIDATE_PER_TICK, ids.size()); i++) {
            Long id = ids.get((validateCursor + i) % ids.size());
            ItchOrderBook b = books.get(id);
            if (b == null) continue;
            ItchBookInvariants.Report r = ItchBookInvariants.check(b, PRICE_DECIMALS, 10);
            if (!r.ok()) log.warn("ITCH book invariant [{}]: {}", symbols.getOrDefault(id, "?"), r.violations());
        }
        validateCursor = (validateCursor + VALIDATE_PER_TICK) % ids.size();
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

    /**
     * A session boundary empties the book — and until this existed, ours never emptied.
     *
     * An exchange does not send an Order Delete for every resting order at the close; it publishes a
     * System Event and the book is simply void from that point. We decoded [S] and then ignored it,
     * so every session's leftovers accumulated. Because a reconnect replays from sequence one, and
     * that history spans several trading sessions, the books were carrying days of phantom liquidity:
     * more levels than the venue shows, inflated size at shared prices, and — once enough stale bids
     * outlived the offers that had traded against them — a CROSSED book, bid above ask, on 273 of 306
     * instruments. A crossed book is not a cosmetic problem: it inverts spread, microprice and
     * imbalance, and it is arithmetically impossible on a real venue, so anything reading it is
     * reading a fiction.
     *
     * Standard ITCH event codes: O start of messages, S start of system hours, Q start of market
     * hours, M end of market hours, E end of system hours, C end of messages. Anything that opens or
     * closes a session invalidates the book, so both ends clear it; the replay that follows rebuilds
     * the current session from its own messages.
     */
    /**
     * The venue's instrument directory — board, share category and sector, live from the feed.
     *
     * nFIX now populates group (MAIN/SME/ATB), listingType (the DSE share category A/B/N/Z/G) and
     * sector in its [R] messages, and this is the venue-owned truth for those columns: the local
     * seed only ever guessed them. The tick thread does nothing but drop the message into a map —
     * the DB write happens on its own thread below, because persisting inline on the tick is
     * exactly the mistake that once froze the whole feed for 42 seconds a page.
     *
     * The map (not a queue) also coalesces: a reconnect replays the directory again, and 300
     * unchanged rows should cost one comparison each, not 300 UPDATEs.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Itch.OrderbookDirectory> pendingDirectory =
            new java.util.concurrent.ConcurrentHashMap<>();
    private Thread dirWriter;

    private void onDirectory(Itch.OrderbookDirectory r) {
        pendingDirectory.put(r.orderbook(), r);
        startDirWriter();
    }

    /**
     * DSE sector names truncated by the 12-byte wire field, expanded back to the official names.
     * "Pharmaceutic" on a screener filter reads as a bug; the venue means Pharmaceuticals & Chemicals.
     */
    private static final Map<String, String> SECTOR_FULL = Map.of(
            "Pharmaceutic", "Pharmaceuticals & Chemicals",
            "Telecommunic", "Telecommunication",
            "Food & Allie", "Food & Allied",
            "Paper & Prin", "Paper & Printing",
            "Services & R", "Services & Real Estate",
            "Miscellaneou", "Miscellaneous");

    private void startDirWriter() {
        if (dirWriter != null) return;
        synchronized (this) {
            if (dirWriter != null) return;
            Thread t = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(1500);              // directory bursts settle; batch them
                        if (pendingDirectory.isEmpty()) continue;
                        List<Long> ids = new ArrayList<>(pendingDirectory.keySet());
                        int changed = 0;
                        for (Long id : ids) {
                            Itch.OrderbookDirectory r = pendingDirectory.remove(id);
                            if (r == null) continue;
                            var sec = securityRepo.findById(id).orElse(null);
                            if (sec == null) continue;   // bonds etc. — not in the venue directory
                            boolean dirty = false;
                            String board = r.group() == null ? "" : r.group().trim();
                            if (!board.isEmpty() && !board.equals(sec.getBoard())) { sec.setBoard(board); dirty = true; }
                            String cat = String.valueOf(r.listingType()).trim();
                            if (!cat.isEmpty() && !cat.equals(sec.getCategory())) { sec.setCategory(cat); dirty = true; }
                            String raw = r.sector() == null ? "" : r.sector().trim();
                            String sector = SECTOR_FULL.getOrDefault(raw, raw);
                            if (!sector.isEmpty() && !sector.equals(sec.getSector())) { sec.setSector(sector); dirty = true; }
                            if (dirty) { securityRepo.save(sec); changed++; }
                        }
                        if (changed > 0) {
                            log.info("ITCH directory: updated board/category/sector on {} securities from the venue", changed);
                            stream.publish("market", Map.of("directory", changed));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // One bad row must not kill the writer and silently freeze the directory.
                        log.warn("ITCH: directory persist failed ({}) — continuing", e.toString());
                    }
                }
            }, "itch-dir-writer");
            t.setDaemon(true);
            t.start();
            dirWriter = t;
        }
    }

    /** When the venue last restarted (ITCH session change + sequence rollback), for the dashboard. */
    private volatile java.time.Instant lastVenueRestartAt;
    private volatile String lastVenueRestartSession = "";
    private final java.util.concurrent.atomic.AtomicInteger venueRestarts = new java.util.concurrent.atomic.AtomicInteger();

    public java.time.Instant lastVenueRestartAt() { return lastVenueRestartAt; }
    public String lastVenueRestartSession() { return lastVenueRestartSession; }
    public int venueRestartCount() { return venueRestarts.get(); }

    private void onSystemEvent(Itch.SystemEvent s) {
        char code = Character.toUpperCase(s.eventCode());
        boolean boundary = code == 'O' || code == 'S' || code == 'C' || code == 'E';
        if (!boundary) {
            log.info("ITCH system event '{}' — no book action", code);
            return;
        }
        int n = books.size();
        for (ItchOrderBook b : books.values()) b.clear();
        orderToSecurity.clear();
        dirty.addAll(books.keySet());   // every book just changed — republish them all once
        log.info("ITCH system event '{}' (session boundary) — cleared {} book(s); the book that follows "
                + "belongs to the new session", code, n);
    }

    private void route(Itch.Msg m) {
        msgCounts.computeIfAbsent(m.type(), k -> new java.util.concurrent.atomic.LongAdder()).increment();
        switch (m.type()) {
            case 'A' -> { Itch.AddOrder a = (Itch.AddOrder) m;
                if (!Itch.isReferencePrice(a)) { orderToSecurity.put(a.orderNumber(), a.orderbook()); book(a.orderbook()).apply(a); dirty.add(a.orderbook()); } }
            case 'F' -> { Itch.AddOrderParticipant f = (Itch.AddOrderParticipant) m;
                orderToSecurity.put(f.orderNumber(), f.orderbook()); book(f.orderbook()).apply(f); dirty.add(f.orderbook()); }
            case 'E' -> { Itch.OrderExecuted e = (Itch.OrderExecuted) m; Long sid = orderToSecurity.get(e.orderNumber());
                if (sid != null) {
                    long rawPx = book(sid).priceOf(e.orderNumber());   // resting price, read before the fill removes it
                    book(sid).apply(e); dirty.add(sid);
                    recordTrade(sid, rawPx, e.execQty(), e.ts());       // [E] prints at the resting order's price
                } }
            case 'C' -> { Itch.OrderExecutedWithPrice c = (Itch.OrderExecutedWithPrice) m; Long sid = orderToSecurity.get(c.orderNumber());
                if (sid != null) {
                    book(sid).apply(c); dirty.add(sid);
                    if (c.printable() != 'N') recordTrade(sid, c.execPrice(), c.execQty(), c.ts());   // [C] carries its own price
                } }
            case 'D' -> { Itch.OrderDelete d = (Itch.OrderDelete) m; Long sid = orderToSecurity.remove(d.orderNumber());
                if (sid != null) { book(sid).apply(d); dirty.add(sid); } }
            case 'U' -> { Itch.OrderReplace u = (Itch.OrderReplace) m; Long sid = orderToSecurity.remove(u.origOrderNumber());
                if (sid != null) { book(sid).apply(u); orderToSecurity.put(u.newOrderNumber(), sid); dirty.add(sid); } }
            case 'Q' -> onTrade((Itch.Trade) m);
            case 'Z' -> onIndex((Itch.IndexValue) m);
            case 'S' -> onSystemEvent((Itch.SystemEvent) m);
            case 'R' -> onDirectory((Itch.OrderbookDirectory) m);
            default -> { /* T/R/H/O/I/N — not book-affecting here */ }
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
        if (q.printable() == 'N') return;                 // a non-printable cross is not a last-sale
        recordTrade(q.orderbook(), q.execPrice(), q.execQty(), q.ts());
    }

    /**
     * A trade printed on the venue — move the last price, the day range, the volume and the tape.
     *
     * <p>This is called for <b>every</b> price-forming event now, not just the cross: an [E] Order-Executed
     * (a continuous match against a resting order, printed at that order's price), a [C]
     * Order-Executed-with-Price, and the [Q] cross/auction Trade. Before, only [Q] reached this path, so
     * through an entire session of ordinary matching the last-traded price never moved — which is exactly
     * the "price is 30 minutes old" symptom: the book was updating, but the last sale was stuck at the
     * last cross. nFIX sends thousands of [E]/[C] to a few hundred [Q], so this is most of the trading.
     */
    private void recordTrade(long sid, long rawPrice, long qty, long ts) {
        if (rawPrice <= 0 || qty <= 0) return;   // [E] on an order we never saw, or a zero-qty print: nothing to record
        ItchOrderBook b = books.get(sid);
        BigDecimal px = scale(rawPrice);
        BigDecimal bestBid = (b == null || b.bestBidRaw() < 0) ? null : scale(b.bestBidRaw());
        BigDecimal bestAsk = (b == null || b.bestAskRaw() < 0) ? null : scale(b.bestAskRaw());
        // Do NOT write to the DB here: recordTrade runs inside the tick lock, and a replay can carry
        // thousands of trades. Persisting each one under the lock is what froze depth serving and the
        // snapshot push during a reconnect. Buffer it; tick() flushes the batch once the lock is free.
        pendingTrades.add(new PendingTrade(sid, px, qty, bestBid, bestAsk, ts));
    }

    /** A trade captured under the lock, persisted afterwards. */
    private record PendingTrade(long sid, BigDecimal px, long qty, BigDecimal bestBid, BigDecimal bestAsk, long ts) {}

    /** Persist one buffered trade and push it to the tape — called after the tick lock is released. */
    private void flushTrade(PendingTrade t) {
        marketData.applyTrade(t.sid(), t.px(), t.qty(), t.bestBid(), t.bestAsk());
        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("securityId", t.sid());
        tick.put("symbol", symbols.getOrDefault(t.sid(), "?"));
        tick.put("price", t.px());
        tick.put("qty", t.qty());
        tick.put("side", "");
        tick.put("ts", String.valueOf(t.ts()));
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
