package com.naztech.oms.exchange.itch;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.exchange.config.ItchProperties;
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

    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;
    private final MarketDataService marketData;
    private final StreamService stream;
    private final ItchProperties props;

    private final Map<Long, ItchOrderBook> books = new ConcurrentHashMap<>();   // securityId → book
    private final Map<Long, Long> orderToSecurity = new ConcurrentHashMap<>();  // orderNumber → securityId
    private final Map<Long, String> symbols = new ConcurrentHashMap<>();        // securityId → symbol
    private final List<Long> indexIds = new ArrayList<>();
    private final Object lock = new Object();
    private final Random rnd = new Random();
    private ItchSource source;
    private volatile boolean ready = false;

    public ItchGateway(SecurityRepo securityRepo, MarketDataRepo marketRepo, MarketDataService marketData,
                       StreamService stream, ItchProperties props) {
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.marketData = marketData;
        this.stream = stream;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        synchronized (lock) {
            List<ItchSimulator.Instrument> instruments = new ArrayList<>();
            for (Security s : securityRepo.findAll()) {
                if (!"ACTIVE".equals(s.getStatus())) continue;
                if ("INDEX".equals(s.getAssetClass())) { indexIds.add(s.getId()); continue; }
                symbols.put(s.getId(), s.getSymbol());
                books.put(s.getId(), new ItchOrderBook());
                long ref = refPriceRaw(s.getId());
                long tick = tickRaw(s);
                instruments.add(new ItchSimulator.Instrument(s.getId(), s.getSymbol(), s.getName(), ref, PRICE_DECIMALS, tick));
            }
            if (instruments.isEmpty()) { log.warn("ITCH: no active equities to simulate"); return; }
            try {
                source = buildSource(instruments);
            } catch (IOException e) {
                log.error("ITCH: could not open configured source ({}) — falling back to simulator", e.toString());
                source = new SimulatorSource(instruments, props.getSeed(), props.getBurst());
            }
            for (Itch.Msg m : source.open()) route(wire(m));   // seed the books
            ready = true;
            log.info("ITCH feed active (source='{}', transport='{}') — {} instruments, {} index tickers. Market depth is now ITCH-driven.",
                    source.name(), props.getTransport(), instruments.size(), indexIds.size());
        }
    }

    /** Select the message source from config: replay a capture if requested, else simulate; tee to a
     *  recorder when {@code itch.record=true}. Adding a real SoupBinTCP/MoldUDP64 source is a new branch
     *  here only — nothing downstream changes. */
    private ItchSource buildSource(List<ItchSimulator.Instrument> instruments) throws IOException {
        ItchSource base;
        if (props.isReplay() && props.getReplayFile() != null && !props.getReplayFile().isBlank()) {
            base = new FileReplaySource(Path.of(props.getReplayFile()), props.getBurst());
        } else {
            base = new SimulatorSource(instruments, props.getSeed(), props.getBurst());
        }
        if (props.isRecord() && props.getRecordFile() != null && !props.getRecordFile().isBlank()) {
            base = new RecordingSource(base, new ItchSessionWriter(Path.of(props.getRecordFile())));
        }
        return base;
    }

    @EventListener(ContextClosedEvent.class)
    public void shutdown() {
        synchronized (lock) {
            if (source != null) {
                try { source.close(); } catch (IOException e) { log.debug("ITCH source close: {}", e.toString()); }
                source = null;
            }
        }
    }

    /** Stream continuous ITCH market activity. Runs only while this bean exists (itch.enabled=true). */
    @Scheduled(fixedDelayString = "${itch.tick-ms:1200}")
    public void tick() {
        if (!ready || source == null) return;
        synchronized (lock) {
            for (Itch.Msg m : source.poll()) route(wire(m));
            driftIndices();
            if (props.isValidate()) validateBooks();
        }
        stream.publish("market", Map.of("type", "itch", "ts", 0));
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
            default -> { /* T/S/R/H/O/Z/I/N — not book-affecting here */ }
        }
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

    /** Nudge index values so the index board stays alive under the ITCH feed. */
    private void driftIndices() {
        boolean any = false;
        for (Long id : indexIds) {
            MarketData m = marketRepo.findById(id).orElse(null);
            if (m == null || m.getLtp() == null || m.getLtp().signum() == 0) continue;
            double drift = (rnd.nextDouble() - 0.48) * 0.0015;
            BigDecimal v = m.getLtp().multiply(BigDecimal.valueOf(1 + drift)).setScale(2, RoundingMode.HALF_UP);
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
