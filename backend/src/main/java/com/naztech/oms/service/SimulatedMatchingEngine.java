package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.api.Dtos.DepthLevel;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.entity.Trade;
import com.naztech.oms.exchange.OrderFillApplier;
import com.naztech.oms.exchange.config.SimulatorModeCondition;
import com.naztech.oms.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process price-time-priority matching-engine simulator. Stands in for the real
 * Exchange Matching Engine behind {@link MatchingGateway}. It keeps an in-memory order
 * book per security, matches incoming orders against resting liquidity, persists trades,
 * marks portfolios, and moves the last-traded price. A background "autosim" injects
 * synthetic liquidity and flow so the tape stays alive even when the market is closed.
 */
@Service
@Conditional(SimulatorModeCondition.class)   // active for exchange.mode=simulator (the default); real DSE uses FixTradingGateway
public class SimulatedMatchingEngine implements MatchingGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedMatchingEngine.class);
    private static final List<String> RESTING = List.of("OPEN", "PARTIAL");

    private final OmsOrderRepo orderRepo;
    private final TradeRepo tradeRepo;
    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;
    private final PortfolioService portfolio;
    private final MarketDataService marketData;
    private final StreamService stream;
    private final AuditService audit;
    private final OrderFillApplier fills;

    @Value("${app.matching.autosim:true}")
    private boolean autosim;

    // When a live external feed (Python → dsebd.org) drives real prices, set this false so
    // the simulator stops random-walking stock prices. It still provides synthetic order-book
    // depth (so client orders can fill) and gently animates the indices.
    @Value("${app.matching.price-sim:true}")
    private boolean priceSim;

    private final Map<Long, Book> books = new ConcurrentHashMap<>();
    private final Set<Long> armedStops = ConcurrentHashMap.newKeySet();   // STOP / STOP_LIMIT order ids
    private final AtomicLong seq = new AtomicLong(1);
    private final Object lock = new Object();
    private final Random rnd = new Random();

    public SimulatedMatchingEngine(OmsOrderRepo orderRepo, TradeRepo tradeRepo, SecurityRepo securityRepo,
                                   MarketDataRepo marketRepo, PortfolioService portfolio,
                                   MarketDataService marketData, StreamService stream, AuditService audit,
                                   OrderFillApplier fills) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.stream = stream;
        this.audit = audit;
        this.fills = fills;
    }

    // -------------------------------------------------------------- book model
    private static final class Resting {
        Long orderId; Long accountId; String side; BigDecimal price; long remaining; long seq; boolean synthetic;
    }
    private static final class Book {
        final List<Resting> bids = new ArrayList<>();  // BUY orders
        final List<Resting> asks = new ArrayList<>();  // SELL orders
    }
    private static final class Incoming {
        Long securityId; Long orderId; Long accountId; String side; String type; BigDecimal price; long remaining;
        boolean synthetic;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadRestingOrders() {
        List<OmsOrder> open = orderRepo.findByStatusIn(RESTING);
        for (OmsOrder o : open) addRestingFromOrder(o);
        log.info("Matching engine loaded {} resting orders into {} books", open.size(), books.size());
    }

    private void addRestingFromOrder(OmsOrder o) {
        Resting r = new Resting();
        r.orderId = o.getId();
        r.accountId = o.getAccountId();
        r.side = o.getSide();
        r.price = o.getPrice();
        r.remaining = o.remainingQty();
        r.seq = seq.getAndIncrement();
        r.synthetic = false;
        if (r.remaining > 0) {
            Book b = book(o.getSecurityId());
            ("BUY".equals(r.side) ? b.bids : b.asks).add(r);
        }
    }

    private Book book(Long securityId) { return books.computeIfAbsent(securityId, k -> new Book()); }

    // -------------------------------------------------------------- gateway API
    @Override
    public void submit(OmsOrder order) {
        synchronized (lock) {
            Incoming inc = new Incoming();
            inc.securityId = order.getSecurityId();
            inc.orderId = order.getId();
            inc.accountId = order.getAccountId();
            inc.side = order.getSide();
            inc.type = order.getOrderType();
            inc.price = order.getPrice() == null ? BigDecimal.ZERO : order.getPrice();
            inc.remaining = order.remainingQty();
            inc.synthetic = false;
            match(inc, order);
        }
    }

    @Override
    public void arm(OmsOrder stopOrder) {
        armedStops.add(stopOrder.getId());
    }

    @Override
    public void cancel(OmsOrder order) {
        synchronized (lock) {
            armedStops.remove(order.getId());
            Book b = books.get(order.getSecurityId());
            if (b == null) return;
            b.bids.removeIf(r -> Objects.equals(r.orderId, order.getId()));
            b.asks.removeIf(r -> Objects.equals(r.orderId, order.getId()));
        }
    }

    @Override
    public Depth depth(Long securityId, int levels) {
        synchronized (lock) {
            Book b = books.get(securityId);
            Security s = securityRepo.findById(securityId).orElse(null);
            MarketData m = marketRepo.findById(securityId).orElse(null);
            BigDecimal ltp = m == null || m.getLtp() == null ? BigDecimal.ZERO : m.getLtp();
            String sym = s == null ? "?" : s.getSymbol();
            if (b == null) return new Depth(sym, ltp, List.of(), List.of());
            return new Depth(sym, ltp, aggregate(b.bids, true, levels), aggregate(b.asks, false, levels));
        }
    }

    private List<DepthLevel> aggregate(List<Resting> side, boolean descending, int levels) {
        Map<BigDecimal, long[]> byPrice = new TreeMap<BigDecimal, long[]>(
                descending ? Comparator.<BigDecimal>reverseOrder() : Comparator.<BigDecimal>naturalOrder());
        for (Resting r : side) {
            if (r.remaining <= 0) continue;
            byPrice.computeIfAbsent(r.price, k -> new long[2]);
            long[] agg = byPrice.get(r.price);
            agg[0] += r.remaining; agg[1] += 1;
        }
        List<DepthLevel> out = new ArrayList<>();
        for (Map.Entry<BigDecimal, long[]> e : byPrice.entrySet()) {
            out.add(new DepthLevel(e.getKey(), e.getValue()[0], (int) e.getValue()[1]));
            if (out.size() >= levels) break;
        }
        return out;
    }

    // -------------------------------------------------------------- matching
    private void match(Incoming inc, OmsOrder dbOrder) {
        Book b = book(inc.securityId);
        List<Resting> opp = "BUY".equals(inc.side) ? b.asks : b.bids;

        while (inc.remaining > 0) {
            Resting best = bestEligible(opp, inc);
            if (best == null) break;
            long fillQty = Math.min(inc.remaining, best.remaining);
            BigDecimal fillPx = best.price;             // passive price (price-time priority)
            executeFill(inc, dbOrder, best, fillQty, fillPx);
            inc.remaining -= fillQty;
            best.remaining -= fillQty;
            if (best.remaining <= 0) opp.remove(best);
        }

        if (inc.remaining > 0) {
            if ("MARKET".equalsIgnoreCase(inc.type)) {
                // sweep remainder against synthetic liquidity at reference price
                BigDecimal ref = referencePrice(inc);
                Resting synth = synthetic(opposite(inc.side), ref, inc.remaining);
                executeFill(inc, dbOrder, synth, inc.remaining, ref);
                inc.remaining = 0;
            } else if (!inc.synthetic) {
                // rest the remainder of a real LIMIT order on the book
                Resting r = new Resting();
                r.orderId = inc.orderId; r.accountId = inc.accountId; r.side = inc.side;
                r.price = inc.price; r.remaining = inc.remaining; r.seq = seq.getAndIncrement();
                ("BUY".equals(inc.side) ? b.bids : b.asks).add(r);
            }
        }

        if (dbOrder != null) {
            String st = fills.deriveStatus(dbOrder);
            dbOrder.setStatus(st);
            dbOrder.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(dbOrder);
            audit.orderEvent(dbOrder.getId(), st, "Order " + st.toLowerCase()
                    + " (filled " + (dbOrder.getFilledQty() == null ? 0 : dbOrder.getFilledQty())
                    + "/" + dbOrder.getQuantity() + ")");
            publishOrder(dbOrder);
        }
    }

    private Resting bestEligible(List<Resting> opp, Incoming inc) {
        Resting best = null;
        boolean market = "MARKET".equalsIgnoreCase(inc.type);
        for (Resting r : opp) {
            if (r.remaining <= 0) continue;
            boolean crosses = market ||
                    ("BUY".equals(inc.side) ? r.price.compareTo(inc.price) <= 0
                                            : r.price.compareTo(inc.price) >= 0);
            if (!crosses) continue;
            if (best == null) { best = r; continue; }
            int cmp = "BUY".equals(inc.side) ? r.price.compareTo(best.price) : best.price.compareTo(r.price);
            if (cmp < 0 || (cmp == 0 && r.seq < best.seq)) best = r;  // best price, then oldest
        }
        return best;
    }

    private void executeFill(Incoming inc, OmsOrder dbOrder, Resting passive, long qty, BigDecimal px) {
        Long buyOrderId  = "BUY".equals(inc.side) ? inc.orderId : passive.orderId;
        Long sellOrderId = "BUY".equals(inc.side) ? passive.orderId : inc.orderId;

        Trade t = new Trade();
        t.setTradeRef("TRD-" + seq.getAndIncrement() + "-" + (System.currentTimeMillis() % 100000));
        t.setSecurityId(inc.securityId);
        t.setBuyOrderId(buyOrderId);
        t.setSellOrderId(sellOrderId);
        t.setPrice(px);
        t.setQuantity(qty);
        t.setAggressorSide(inc.side);
        t.setExecutedAt(LocalDateTime.now());
        tradeRepo.save(t);

        // incoming (aggressor) order + portfolio
        if (dbOrder != null) fills.applyFill(dbOrder, qty, px);
        if (!inc.synthetic && inc.accountId != null)
            portfolio.applyFill(inc.accountId, inc.securityId, inc.side, qty, px);

        // passive (resting) order + portfolio
        if (passive.orderId != null) {
            orderRepo.findById(passive.orderId).ifPresent(po -> {
                fills.applyFill(po, qty, px);
                String st = fills.deriveStatus(po);
                po.setStatus(st);
                po.setUpdatedAt(LocalDateTime.now());
                orderRepo.save(po);
                audit.orderEvent(po.getId(), "FILL", "Resting fill " + qty + " @ " + px.toPlainString());
                publishOrder(po);
            });
        }
        if (!passive.synthetic && passive.accountId != null)
            portfolio.applyFill(passive.accountId, inc.securityId, passive.side, qty, px);

        // move the market
        Book b = books.get(inc.securityId);
        BigDecimal bestBid = b == null ? null : bestPrice(b.bids, true);
        BigDecimal bestAsk = b == null ? null : bestPrice(b.asks, false);
        marketData.applyTrade(inc.securityId, px, qty, bestBid, bestAsk);

        // push the tape
        Security s = securityRepo.findById(inc.securityId).orElse(null);
        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("securityId", inc.securityId);
        tick.put("symbol", s == null ? "?" : s.getSymbol());
        tick.put("price", px);
        tick.put("qty", qty);
        tick.put("side", inc.side);
        tick.put("ts", t.getExecutedAt().toString());
        stream.publish("trade", tick);
    }

    private void publishOrder(OmsOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.getId());
        m.put("status", o.getStatus());
        m.put("filledQty", o.getFilledQty());
        m.put("avgFillPrice", o.getAvgFillPrice());
        m.put("accountId", o.getAccountId());
        m.put("brokerId", o.getBrokerId());
        stream.publish("order", m);
    }

    private BigDecimal bestPrice(List<Resting> side, boolean wantMax) {
        BigDecimal best = null;
        for (Resting r : side) {
            if (r.remaining <= 0) continue;
            if (best == null) best = r.price;
            else best = wantMax ? best.max(r.price) : best.min(r.price);
        }
        return best;
    }

    private BigDecimal referencePrice(Incoming inc) {
        MarketData m = marketRepo.findById(inc.securityId).orElse(null);
        if (m != null && m.getLtp() != null && m.getLtp().signum() > 0) return m.getLtp();
        return inc.price.signum() > 0 ? inc.price : BigDecimal.ONE;
    }

    private Resting synthetic(String side, BigDecimal price, long qty) {
        Resting r = new Resting();
        r.side = side; r.price = price; r.remaining = qty; r.seq = seq.getAndIncrement(); r.synthetic = true;
        return r;
    }

    private String opposite(String side) { return "BUY".equals(side) ? "SELL" : "BUY"; }

    // -------------------------------------------------------------- autosim
    /**
     * Keeps the book populated and the tape moving: refreshes synthetic depth around
     * the LTP and occasionally fires a synthetic marketable order so prices drift and
     * resting client orders can fill — making the demo feel like a live market.
     */
    @Scheduled(fixedDelayString = "${app.matching.tick-ms:1500}")
    public void tick() {
        if (!autosim) return;
        synchronized (lock) {
            List<Security> equities = new ArrayList<>();
            for (Security s : securityRepo.findAll()) {
                if ("ACTIVE".equals(s.getStatus()) && !"INDEX".equals(s.getAssetClass())) equities.add(s);
            }
            if (equities.isEmpty()) return;
            Collections.shuffle(equities, rnd);
            int n = Math.min(6, equities.size());
            for (int i = 0; i < n; i++) {
                Security s = equities.get(i);
                refreshSyntheticDepth(s);
                if (priceSim && rnd.nextDouble() < 0.65) fireSyntheticOrder(s);
            }
            checkStops();
            // nudge indices so the ticker tape lives
            driftIndices();
        }
    }

    /** Trigger armed STOP / STOP_LIMIT orders when the LTP crosses the stop price. */
    private void checkStops() {
        if (armedStops.isEmpty()) return;
        for (Long id : new ArrayList<>(armedStops)) {
            OmsOrder o = orderRepo.findById(id).orElse(null);
            if (o == null || !"OPEN".equals(o.getStatus())) { armedStops.remove(id); continue; }
            MarketData m = marketRepo.findById(o.getSecurityId()).orElse(null);
            if (m == null || m.getLtp() == null) continue;
            BigDecimal ltp = m.getLtp();
            BigDecimal stop = o.getStopPrice();
            if (stop == null) { armedStops.remove(id); continue; }
            boolean trigger = "BUY".equals(o.getSide()) ? ltp.compareTo(stop) >= 0 : ltp.compareTo(stop) <= 0;
            if (!trigger) continue;
            armedStops.remove(id);
            audit.orderEvent(o.getId(), "STOP_TRIGGERED",
                    "Stop @ " + stop.toPlainString() + " triggered at LTP " + ltp.toPlainString());
            Incoming inc = new Incoming();
            inc.securityId = o.getSecurityId();
            inc.orderId = o.getId();
            inc.accountId = o.getAccountId();
            inc.side = o.getSide();
            inc.type = "STOP_LIMIT".equalsIgnoreCase(o.getOrderType()) ? "LIMIT" : "MARKET";
            inc.price = o.getPrice() == null ? ltp : o.getPrice();
            inc.remaining = o.remainingQty();
            inc.synthetic = false;
            match(inc, o);
        }
    }

    private void refreshSyntheticDepth(Security s) {
        MarketData m = marketRepo.findById(s.getId()).orElse(null);
        if (m == null || m.getLtp() == null || m.getLtp().signum() == 0) return;
        Book b = book(s.getId());
        // drop stale synthetic levels, keep real client orders
        b.bids.removeIf(r -> r.synthetic);
        b.asks.removeIf(r -> r.synthetic);
        BigDecimal ltp = m.getLtp();
        BigDecimal tick = s.getTickSize() == null || s.getTickSize().signum() == 0
                ? new BigDecimal("0.10") : s.getTickSize();
        for (int lvl = 1; lvl <= 5; lvl++) {
            BigDecimal off = tick.multiply(BigDecimal.valueOf(lvl));
            long qty = (long) (rnd.nextInt(40) + 5) * Math.max(1, s.getLotSize() == null ? 1 : s.getLotSize()) * 100L;
            Resting bid = synthetic("BUY", ltp.subtract(off).max(tick), qty);
            Resting ask = synthetic("SELL", ltp.add(off), (long) (rnd.nextInt(40) + 5) * 100L);
            b.bids.add(bid); b.asks.add(ask);
        }
    }

    private void fireSyntheticOrder(Security s) {
        MarketData m = marketRepo.findById(s.getId()).orElse(null);
        if (m == null || m.getLtp() == null || m.getLtp().signum() == 0) return;
        Incoming inc = new Incoming();
        inc.securityId = s.getId();
        inc.side = rnd.nextBoolean() ? "BUY" : "SELL";
        inc.type = "MARKET";
        inc.price = m.getLtp();
        inc.remaining = (long) (rnd.nextInt(20) + 1) * 100L;
        inc.synthetic = true;
        match(inc, null);
    }

    private void driftIndices() {
        for (Security s : securityRepo.findByAssetClass("INDEX")) {
            MarketData m = marketRepo.findById(s.getId()).orElse(null);
            if (m == null || m.getLtp() == null || m.getLtp().signum() == 0) continue;
            double drift = (rnd.nextDouble() - 0.48) * 0.0015;       // ±~0.15%
            BigDecimal newLtp = m.getLtp().multiply(BigDecimal.valueOf(1 + drift))
                    .setScale(2, RoundingMode.HALF_UP);
            m.setLtp(newLtp);
            m.setChangePct(m.getYcp() == null || m.getYcp().signum() == 0 ? BigDecimal.ZERO
                    : newLtp.subtract(m.getYcp()).multiply(BigDecimal.valueOf(100))
                      .divide(m.getYcp(), 2, RoundingMode.HALF_UP));
            m.setUpdatedAt(LocalDateTime.now());
            marketRepo.save(m);
        }
        stream.publish("indices", Map.of("ts", System.currentTimeMillis()));
    }
}
