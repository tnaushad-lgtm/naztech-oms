package com.naztech.oms.api;

import java.math.BigDecimal;
import java.util.List;

/** Request / response shapes for the OMS API (grouped for brevity). */
public final class Dtos {
    private Dtos() {}

    // ---- auth ----
    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, String username, String displayName,
                                String role, Long brokerId, String brokerName, Long defaultAccountId) {}

    // ---- orders ----
    public record OrderRequest(
            Long accountId, Long securityId, String side, String orderType,
            String tradeWindow, String validity, String expireDate,
            BigDecimal price, BigDecimal stopPrice, Long quantity, Long dealerId,
            String priceBasis, BigDecimal orderYield) {
        /** Back-compat 11-arg form (defaults to PRICE basis) for existing callers/tests. */
        public OrderRequest(Long accountId, Long securityId, String side, String orderType,
                            String tradeWindow, String validity, String expireDate,
                            BigDecimal price, BigDecimal stopPrice, Long quantity, Long dealerId) {
            this(accountId, securityId, side, orderType, tradeWindow, validity, expireDate,
                 price, stopPrice, quantity, dealerId, "PRICE", null);
        }
    }

    public record RiskResult(boolean pass, String reason, BigDecimal score, List<String> flags) {}

    public record OrderView(
            Long id, String orderRef, String symbol, String securityName, String side,
            String orderType, String tradeWindow, String validity, BigDecimal price,
            Long quantity, Long filledQty, BigDecimal avgFillPrice, String status,
            String rejectReason, BigDecimal riskScore, String createdAt,
            BigDecimal orderYield, String priceBasis) {}

    /** Bond price/yield quote for the order ticket. */
    public record BondQuote(
            String symbol, BigDecimal faceValue, BigDecimal couponRate, int couponFreq,
            String maturityDate, BigDecimal yield, BigDecimal cleanPrice,
            BigDecimal accrued, BigDecimal dirtyPrice) {}

    /** A natural-language order parsed by the AI Order Bot (parsed for confirmation, not yet placed). */
    public record ParsedOrder(
            boolean ok, String side, String symbol, Long securityId, String securityName,
            Long quantity, BigDecimal price, String priceBasis, BigDecimal orderYield, String note) {}

    // ---- price alerts ----
    public record AlertRequest(Long accountId, Long securityId, BigDecimal targetPrice, String direction, String note) {}
    public record AlertView(
            Long id, Long securityId, String symbol, String securityName, BigDecimal targetPrice,
            String direction, String status, String note, BigDecimal ltp, BigDecimal ltpAtTrigger,
            String createdAt, String triggeredAt) {}

    // ---- market data ----
    public record MarketRow(
            Long securityId, String exchange, String symbol, String name, String assetClass,
            String sector, BigDecimal ltp, BigDecimal changeAbs, BigDecimal changePct,
            BigDecimal bid, BigDecimal ask, Long volume, BigDecimal valueMn,
            BigDecimal high, BigDecimal low, BigDecimal ycp, String status) {}

    public record DepthLevel(BigDecimal price, Long quantity, Integer orders) {}
    public record Depth(String symbol, BigDecimal ltp, List<DepthLevel> bids, List<DepthLevel> asks) {}

    public record TradeTick(Long securityId, String symbol, BigDecimal price, Long quantity,
                            String side, String executedAt) {}

    // ---- portfolio ----
    public record PositionView(Long securityId, String symbol, String name, String sector, String assetClass,
                               Long quantity, BigDecimal avgCost, BigDecimal ltp, BigDecimal ycp,
                               BigDecimal investedValue, BigDecimal marketValue,
                               BigDecimal unrealizedPnl, BigDecimal pnlPct,
                               BigDecimal dayPnl, BigDecimal dayPnlPct, BigDecimal weightPct) {}

    public record AllocSlice(String label, BigDecimal value, BigDecimal pct) {}

    public record PortfolioView(Long accountId, String accountName,
                                BigDecimal cash, BigDecimal buyingPower, BigDecimal invested,
                                BigDecimal holdingsValue, BigDecimal totalValue,
                                BigDecimal unrealizedPnl, BigDecimal realizedPnl, BigDecimal dayPnl,
                                List<PositionView> positions,
                                List<AllocSlice> bySector, List<AllocSlice> byAsset) {}

    public record FillRow(String time, String symbol, String side, Long quantity,
                          BigDecimal price, BigDecimal value, String tradeRef) {}

    // ---- ingest from Python feed ----
    public record QuoteIn(String exchange, String symbol, BigDecimal ltp, BigDecimal open,
                          BigDecimal high, BigDecimal low, BigDecimal ycp, Long volume,
                          Integer trades, BigDecimal valueMn, String source) {}
    public record IngestRequest(List<QuoteIn> quotes) {}

    // ---- AI ----
    public record AiHit(Long securityId, String symbol, String name, String assetClass,
                        double matchPct) {}

    // ---- candles (OHLCV) ----
    public record Candle(long time, double open, double high, double low, double close, long volume) {}

    // ---- equity / P&L over time ----
    public record EquityPoint(long time, double totalValue, double unrealizedPnl,
                              double realizedPnl, double pnl, double dayPnl) {}
}
