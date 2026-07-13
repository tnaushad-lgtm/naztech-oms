package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.*;
import com.naztech.oms.entity.Exchange;
import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ExchangeRepo;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Market picture: live watch rows, depth-independent quotes, movers and indices. */
@Service
public class MarketDataService {

    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;
    private final ExchangeRepo exchangeRepo;
    private final com.naztech.oms.repo.TradeRepo tradeRepo;
    private final StreamService stream;

    private final com.naztech.oms.marketstore.TickStore ticks;
    private final com.naztech.oms.marketstore.HotStore hot;

    public MarketDataService(SecurityRepo securityRepo, MarketDataRepo marketRepo,
                             ExchangeRepo exchangeRepo, com.naztech.oms.repo.TradeRepo tradeRepo,
                             StreamService stream, com.naztech.oms.marketstore.TickStore ticks,
                             com.naztech.oms.marketstore.HotStore hot) {
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.exchangeRepo = exchangeRepo;
        this.tradeRepo = tradeRepo;
        this.stream = stream;
        this.ticks = ticks;
        this.hot = hot;
    }

    // -------------------------------------------------------------- candles
    /**
     * OHLCV candles for charting. Intraday timeframes are aggregated from the real
     * trade tape (the matching engine); when there isn't enough flow yet (or for daily),
     * a deterministic synthetic series anchored to the LTP is returned so the chart is never empty.
     */
    public List<com.naztech.oms.api.Dtos.Candle> candles(Long securityId, int bucketSec, int limit) {
        // Real candles, built from the tick history by the time-series store. This is what the chart
        // is supposed to show. Everything below is the fallback for when there is no tick store: the
        // old bucketing of the last 1000 trades, and — when even that is too thin — a synthetic
        // random walk, which is honest only because there was never any history to draw.
        List<com.naztech.oms.api.Dtos.Candle> fromTicks = ticks.candles(securityId, bucketSec, limit);
        if (fromTicks.size() >= 5) {
            return fromTicks;
        }

        List<com.naztech.oms.api.Dtos.Candle> out = new ArrayList<>();
        if (bucketSec < 86400) {
            List<com.naztech.oms.entity.Trade> trades = tradeRepo.findTop1000BySecurityIdOrderByExecutedAtDesc(securityId);
            java.util.TreeMap<Long, double[]> buckets = new java.util.TreeMap<>(); // ts -> [o,h,l,c,vol]
            for (int i = trades.size() - 1; i >= 0; i--) {              // oldest first
                com.naztech.oms.entity.Trade t = trades.get(i);
                if (t.getExecutedAt() == null) continue;
                long sec = t.getExecutedAt().atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
                long b = sec - (sec % bucketSec);
                double px = t.getPrice().doubleValue();
                double[] c = buckets.get(b);
                if (c == null) buckets.put(b, new double[]{px, px, px, px, t.getQuantity()});
                else { c[1] = Math.max(c[1], px); c[2] = Math.min(c[2], px); c[3] = px; c[4] += t.getQuantity(); }
            }
            for (var e : buckets.entrySet()) {
                double[] c = e.getValue();
                out.add(new com.naztech.oms.api.Dtos.Candle(e.getKey(), c[0], c[1], c[2], c[3], (long) c[4]));
            }
        }
        if (out.size() >= 5) {
            int from = Math.max(0, out.size() - limit);
            return out.subList(from, out.size());
        }
        return synthCandles(securityId, bucketSec, limit);   // fallback (daily, or thin tape)
    }

    private List<com.naztech.oms.api.Dtos.Candle> synthCandles(Long securityId, int bucketSec, int limit) {
        MarketData m = marketRepo.findById(securityId).orElse(null);
        double ltp = (m == null || m.getLtp() == null || m.getLtp().signum() == 0) ? 100 : m.getLtp().doubleValue();
        java.util.Random rnd = new java.util.Random(securityId * 1000003L + bucketSec);
        double vol = bucketSec >= 86400 ? 0.02 : 0.004;
        int n = Math.max(20, Math.min(limit, 200));
        double[] closes = new double[n];
        double p = ltp;
        for (int i = n - 1; i >= 0; i--) { closes[i] = p; p = p / (1 + (rnd.nextDouble() - 0.5) * vol); }
        long now = java.time.Instant.now().getEpochSecond();
        long start = now - (now % bucketSec) - (long) (n - 1) * bucketSec;
        List<com.naztech.oms.api.Dtos.Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double c = closes[i];
            double o = i == 0 ? c * (1 - (rnd.nextDouble() - 0.5) * vol) : closes[i - 1];
            double hi = Math.max(o, c) * (1 + rnd.nextDouble() * vol);
            double lo = Math.min(o, c) * (1 - rnd.nextDouble() * vol);
            long v = (long) (rnd.nextInt(40000) + 5000);
            out.add(new com.naztech.oms.api.Dtos.Candle(start + (long) i * bucketSec,
                    round(o), round(hi), round(lo), round(c), v));
        }
        return out;
    }

    private static double round(double d) { return Math.round(d * 100.0) / 100.0; }

    private Map<Long, String> exchangeCodes() {
        return exchangeRepo.findAll().stream()
                .collect(Collectors.toMap(Exchange::getId, Exchange::getCode));
    }

    /** One row per security with its latest snapshot. */
    public List<MarketRow> watch(String exchangeCode, boolean includeIndices) {
        Map<Long, MarketData> md = marketRepo.findAll().stream()
                .collect(Collectors.toMap(MarketData::getSecurityId, m -> m));
        Map<Long, String> ex = exchangeCodes();
        Long exId = exchangeCode == null ? null :
                exchangeRepo.findByCode(exchangeCode).map(Exchange::getId).orElse(null);

        List<MarketRow> rows = new ArrayList<>();
        for (Security s : securityRepo.findAll()) {
            if (exId != null && !exId.equals(s.getExchangeId())) continue;
            boolean isIndex = "INDEX".equals(s.getAssetClass());
            if (isIndex && !includeIndices) continue;
            if (!isIndex && includeIndices) continue;
            rows.add(toRow(s, md.get(s.getId()), ex.get(s.getExchangeId())));
        }
        rows.sort(Comparator.comparing(MarketRow::valueMn,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    private MarketRow toRow(Security s, MarketData m, String exCode) {
        BigDecimal ltp = m == null ? BigDecimal.ZERO : nz(m.getLtp());
        BigDecimal ycp = m == null ? BigDecimal.ZERO : nz(m.getYcp());
        BigDecimal changeAbs = ltp.subtract(ycp);
        BigDecimal changePct = (m == null) ? BigDecimal.ZERO : nz(m.getChangePct());
        return new MarketRow(
                s.getId(), exCode, s.getSymbol(), s.getName(), s.getAssetClass(), s.getSector(),
                ltp, changeAbs, changePct,
                m == null ? BigDecimal.ZERO : nz(m.getBid()),
                m == null ? BigDecimal.ZERO : nz(m.getAsk()),
                m == null ? 0L : nzL(m.getVolume()),
                m == null ? BigDecimal.ZERO : nz(m.getValueMn()),
                m == null ? BigDecimal.ZERO : nz(m.getHighPrice()),
                m == null ? BigDecimal.ZERO : nz(m.getLowPrice()),
                ycp, s.getStatus());
    }

    public Map<String, List<MarketRow>> movers(String exchangeCode) {
        List<MarketRow> eq = watch(exchangeCode, false);
        List<MarketRow> gainers = eq.stream()
                .sorted(Comparator.comparing(MarketRow::changePct).reversed())
                .limit(10).collect(Collectors.toList());
        List<MarketRow> losers = eq.stream()
                .sorted(Comparator.comparing(MarketRow::changePct))
                .limit(10).collect(Collectors.toList());
        List<MarketRow> active = eq.stream()
                .sorted(Comparator.comparing(MarketRow::valueMn).reversed())
                .limit(10).collect(Collectors.toList());
        Map<String, List<MarketRow>> out = new LinkedHashMap<>();
        out.put("gainers", gainers);
        out.put("losers", losers);
        out.put("active", active);
        return out;
    }

    public List<MarketRow> indices() {
        List<MarketRow> all = new ArrayList<>();
        all.addAll(watch("DSE", true));
        all.addAll(watch("CSE", true));
        return all;
    }

    public MarketData snapshot(Long securityId) {
        return marketRepo.findById(securityId).orElse(null);
    }

    // ----------------------------------------------------- writes (feed / ME)

    /** Upsert quotes coming from the Python feed (bdshare / simulator). */
    @Transactional
    public int ingest(List<QuoteIn> quotes) {
        if (quotes == null || quotes.isEmpty()) return 0;
        Map<Long, String> codeById = exchangeCodes();
        Map<String, Long> idByCode = new HashMap<>();
        codeById.forEach((id, code) -> idByCode.put(code, id));

        int n = 0;
        for (QuoteIn q : quotes) {
            Long exId = idByCode.get(q.exchange());
            if (exId == null) continue;
            Optional<Security> sec = securityRepo.findBySymbolAndExchangeId(q.symbol(), exId);
            if (sec.isEmpty()) continue;
            Long sid = sec.get().getId();
            MarketData m = marketRepo.findById(sid).orElseGet(() -> {
                MarketData nm = new MarketData();
                nm.setSecurityId(sid);
                return nm;
            });
            if (q.ltp() != null) m.setLtp(q.ltp());
            if (q.open() != null) m.setOpenPrice(q.open());
            if (q.high() != null) m.setHighPrice(q.high());
            if (q.low() != null) m.setLowPrice(q.low());
            if (q.ycp() != null) { m.setYcp(q.ycp()); m.setClosePrice(q.ycp()); }
            if (q.volume() != null) m.setVolume(q.volume());
            if (q.trades() != null) m.setTrades(q.trades());
            if (q.valueMn() != null) m.setValueMn(q.valueMn());
            if (m.getBid() == null || m.getBid().signum() == 0)
                m.setBid(nz(m.getLtp()).subtract(new BigDecimal("0.10")));
            if (m.getAsk() == null || m.getAsk().signum() == 0)
                m.setAsk(nz(m.getLtp()).add(new BigDecimal("0.10")));
            m.setChangePct(pctChange(m.getLtp(), m.getYcp()));
            m.setSource(q.source() == null ? "FEED" : q.source());
            m.setUpdatedAt(LocalDateTime.now());
            marketRepo.save(m);
            n++;
        }
        // tell the terminal a refresh happened
        stream.publish("market", Map.of("type", "ingest", "count", n));
        return n;
    }

    /**
     * Called after every fill — by the matching engine, by the FIX execution path, and by the ITCH
     * feed — to move the last-traded price.
     *
     * <p>Three things happen to a trade print now: it is appended to the tick store (history, and the
     * source of every candle), it is written to the hot store (the live picture other instances read),
     * and it updates the MySQL snapshot (the durable last-known state). Only the last of those existed
     * before, and it was the one that threw the tick itself away.
     */
    @Transactional
    public void applyTrade(Long securityId, BigDecimal price, long qty, BigDecimal bestBid, BigDecimal bestAsk) {
        MarketData m = marketRepo.findById(securityId).orElseGet(() -> {
            MarketData nm = new MarketData();
            nm.setSecurityId(securityId);
            return nm;
        });
        m.setLtp(price);
        if (m.getOpenPrice() == null || m.getOpenPrice().signum() == 0) m.setOpenPrice(price);
        m.setHighPrice(m.getHighPrice() == null ? price : m.getHighPrice().max(price));
        m.setLowPrice((m.getLowPrice() == null || m.getLowPrice().signum() == 0) ? price : m.getLowPrice().min(price));
        m.setVolume(nzL(m.getVolume()) + qty);
        m.setTrades((m.getTrades() == null ? 0 : m.getTrades()) + 1);
        m.setValueMn(nz(m.getValueMn()).add(price.multiply(BigDecimal.valueOf(qty))
                .divide(BigDecimal.valueOf(1_000_000), 4, RoundingMode.HALF_UP)));
        if (bestBid != null) m.setBid(bestBid);
        if (bestAsk != null) m.setAsk(bestAsk);
        m.setChangePct(pctChange(price, m.getYcp()));
        m.setSource("ME");
        m.setUpdatedAt(LocalDateTime.now());
        marketRepo.save(m);

        // The tick itself — the thing that used to be published to the browser and then dropped.
        ticks.tick(securityId, symbolOf(securityId), price, qty, System.currentTimeMillis());
        hot.putQuote(securityId, new com.naztech.oms.marketstore.HotStore.Quote(price, m.getBid(), m.getAsk(),
                nzL(m.getVolume()), m.getTrades() == null ? 0 : m.getTrades()));
    }

    private String symbolOf(Long securityId) {
        return securityRepo.findById(securityId).map(com.naztech.oms.entity.Security::getSymbol).orElse("?");
    }

    /** Update an index value (from the ITCH Index [Z] feed). */
    @Transactional
    public void applyIndex(Long securityId, BigDecimal value) {
        MarketData m = marketRepo.findById(securityId).orElseGet(() -> {
            MarketData nm = new MarketData();
            nm.setSecurityId(securityId);
            return nm;
        });
        m.setLtp(value);
        m.setChangePct(pctChange(value, m.getYcp()));
        m.setSource("ITCH");
        m.setUpdatedAt(LocalDateTime.now());
        marketRepo.save(m);
    }

    private BigDecimal pctChange(BigDecimal ltp, BigDecimal ycp) {
        if (ltp == null || ycp == null || ycp.signum() == 0) return BigDecimal.ZERO;
        return ltp.subtract(ycp).multiply(BigDecimal.valueOf(100))
                .divide(ycp, 2, RoundingMode.HALF_UP);
    }

    static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
    static long nzL(Long l) { return l == null ? 0L : l; }
}
