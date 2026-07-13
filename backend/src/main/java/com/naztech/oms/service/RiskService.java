package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.RiskResult;
import com.naztech.oms.market.MarketSessionService;
import com.naztech.oms.entity.*;
import com.naztech.oms.repo.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Pre-trade risk controls (RFP "Risk Management"): broker/trader/client limits,
 * buying-power & holdings checks, wash-sale block, single-order caps. Returns a
 * hard pass/reject plus the explainable AI risk score for the order.
 */
@Service
public class RiskService {

    private static final List<String> RESTING = List.of("OPEN", "PARTIAL");

    private final SecurityRepo securityRepo;
    private final RiskLimitRepo limitRepo;
    private final ClientAccountRepo accountRepo;
    private final HoldingRepo holdingRepo;
    private final MarketDataRepo marketRepo;
    private final OmsOrderRepo orderRepo;
    private final BrokerRepo brokerRepo;
    private final AiRiskScoringService ai;
    private final RefDataCache refData;
    private final MarketSessionService session;
    private final TradeWindowRules windows;

    public RiskService(SecurityRepo securityRepo, RiskLimitRepo limitRepo, ClientAccountRepo accountRepo,
                       HoldingRepo holdingRepo, MarketDataRepo marketRepo, OmsOrderRepo orderRepo,
                       BrokerRepo brokerRepo, AiRiskScoringService ai, RefDataCache refData,
                       MarketSessionService session, TradeWindowRules windows) {
        this.securityRepo = securityRepo;
        this.limitRepo = limitRepo;
        this.accountRepo = accountRepo;
        this.holdingRepo = holdingRepo;
        this.marketRepo = marketRepo;
        this.orderRepo = orderRepo;
        this.brokerRepo = brokerRepo;
        this.ai = ai;
        this.refData = refData;
        this.session = session;
        this.windows = windows;
    }

    public RiskResult check(OmsOrder o) {
        Security sec = refData.security(o.getSecurityId()).orElse(null);
        if (sec == null) return reject("Unknown security");
        // Market session — the outermost gate, checked before everything else: a closed market
        // outranks a halted broker, which outranks a suspended instrument.
        String closed = session.blockReason(sec.getExchangeId());
        if (closed != null) return reject(closed);
        // RMS kill-switch: a halted/suspended broker cannot trade. Read fresh, never cached — a halt
        // must bite the instant it is applied, whoever applied it and on whichever node. See RefDataCache.
        Broker br = o.getBrokerId() == null ? null : brokerRepo.findById(o.getBrokerId()).orElse(null);
        if (br != null && !"ACTIVE".equals(br.getStatus()))
            return reject("Broker trading halted by RMS kill-switch (" + br.getStatus() + ")");
        if (!"ACTIVE".equals(sec.getStatus())) return reject("Security is " + sec.getStatus() + " — trading not allowed");
        if ("INDEX".equals(sec.getAssetClass())) return reject("Indices are not tradable");

        long qty = o.getQuantity() == null ? 0 : o.getQuantity();
        if (qty <= 0) return reject("Quantity must be positive");

        MarketData md = marketRepo.findById(o.getSecurityId()).orElse(null);
        BigDecimal ltp = md == null || md.getLtp() == null ? BigDecimal.ZERO : md.getLtp();
        BigDecimal refPx = ("MARKET".equalsIgnoreCase(o.getOrderType()) || o.getPrice() == null
                || o.getPrice().signum() == 0) ? ltp : o.getPrice();
        BigDecimal notional = refPx.multiply(BigDecimal.valueOf(qty));

        // Board / trade-window rules: whole lots in the normal and spot markets, a floor on block
        // trades, and an odd lot that is actually odd. The OMS stored the window for a year and never
        // checked it, so any of those could be sent to the exchange and rejected there instead.
        String badWindow = windows.violation(sec, o.getTradeWindow(), qty, notional);
        if (badWindow != null) return reject(badWindow);

        ClientAccount acc = accountRepo.findById(o.getAccountId()).orElse(null);
        if (acc == null) return reject("Unknown client account");
        // A closed or suspended client may not trade. The status column existed and nothing read it,
        // so a suspended client's orders went through exactly as an active one's did — which is the
        // kind of gap that is invisible until a regulator asks why.
        if (acc.getStatus() != null && !"ACTIVE".equals(acc.getStatus()))
            return reject("Client account is " + acc.getStatus() + " — trading not allowed");

        // ---- limit checks across applicable scopes ----
        BigDecimal effMaxOrderValue = null;
        RiskLimit client = limit("CLIENT", o.getAccountId());
        RiskLimit trader = o.getDealerId() == null ? null : limit("TRADER", o.getDealerId());
        RiskLimit broker = limit("BROKER", o.getBrokerId());
        for (RiskLimit l : new RiskLimit[]{client, trader, broker}) {
            if (l == null || !Boolean.TRUE.equals(l.getEnabled())) continue;
            if (l.getMaxOrderQty() != null && l.getMaxOrderQty() > 0 && qty > l.getMaxOrderQty())
                return reject(l.getScope() + " order-qty limit exceeded (" + qty + " > " + l.getMaxOrderQty() + ")");
            if (l.getMaxOrderValue() != null && l.getMaxOrderValue().signum() > 0
                    && notional.compareTo(l.getMaxOrderValue()) > 0)
                return reject(l.getScope() + " order-value limit exceeded (" + notional.toPlainString()
                        + " > " + l.getMaxOrderValue().toPlainString() + ")");
            if (effMaxOrderValue == null || (l.getMaxOrderValue() != null
                    && l.getMaxOrderValue().signum() > 0 && l.getMaxOrderValue().compareTo(effMaxOrderValue) < 0))
                effMaxOrderValue = l.getMaxOrderValue();
        }

        // ---- buying-power (BUY) / holdings (SELL) ----
        if ("BUY".equals(o.getSide())) {
            if (acc.getBuyingPower() != null && acc.getBuyingPower().compareTo(notional) < 0)
                return reject("Insufficient buying power (need " + notional.toPlainString()
                        + ", have " + acc.getBuyingPower().toPlainString() + ")");
        } else if ("SELL".equals(o.getSide())) {
            long held = holdingRepo.findByAccountIdAndSecurityId(o.getAccountId(), o.getSecurityId())
                    .map(Holding::getQuantity).orElse(0L);
            if (held < qty)
                return reject("Insufficient holdings to sell (have " + held + ", sell " + qty + ")");
        } else {
            return reject("Invalid side: " + o.getSide());
        }

        // ---- wash-sale: opposite-side resting order, same client + security ----
        // COUNT, not a fetch: this scans oms_order — the table that grows fastest under load — and
        // the old version hydrated every matching row into an entity just to call size() on the list.
        String opposite = "BUY".equals(o.getSide()) ? "SELL" : "BUY";
        long washCount = orderRepo.countWashCandidates(o.getAccountId(), o.getSecurityId(), opposite, RESTING);
        boolean washBlock = client != null && Boolean.TRUE.equals(client.getWashSaleBlock());
        if (washBlock && washCount > 0)
            return reject("Wash-sale control: an opposite-side open order exists for this client/security");

        // ---- explainable AI risk score (soft signal) ----
        long dayVol = md == null || md.getVolume() == null ? 0 : md.getVolume();
        AiRiskScoringService.Score s = ai.score(o, ltp, dayVol, (int) Math.min(washCount, Integer.MAX_VALUE), effMaxOrderValue);

        return new RiskResult(true, "OK", s.value(), s.flags());
    }

    /**
     * Lighter re-check for amending a working order: security/broker/lot/limit caps + AI score.
     * Skips buying-power & holdings (already validated and partially executed) to avoid double counting.
     */
    public RiskResult checkAmend(OmsOrder o) {
        Security sec = securityRepo.findById(o.getSecurityId()).orElse(null);
        if (sec == null) return reject("Unknown security");
        // An amend is a new order in every way that matters, so the session gate applies here too —
        // otherwise a halt could be walked around by amending a working order instead of placing one.
        String closed = session.blockReason(sec.getExchangeId());
        if (closed != null) return reject(closed);
        Broker br = o.getBrokerId() == null ? null : brokerRepo.findById(o.getBrokerId()).orElse(null);
        if (br != null && !"ACTIVE".equals(br.getStatus()))
            return reject("Broker trading halted by RMS kill-switch (" + br.getStatus() + ")");
        if (!"ACTIVE".equals(sec.getStatus())) return reject("Security is " + sec.getStatus());

        long qty = o.getQuantity() == null ? 0 : o.getQuantity();
        if (qty <= 0) return reject("Quantity must be positive");

        MarketData md = marketRepo.findById(o.getSecurityId()).orElse(null);
        BigDecimal ltp = md == null || md.getLtp() == null ? BigDecimal.ZERO : md.getLtp();
        BigDecimal refPx = (o.getPrice() == null || o.getPrice().signum() == 0) ? ltp : o.getPrice();
        BigDecimal notional = refPx.multiply(BigDecimal.valueOf(qty));

        // An amend can change quantity and price, so it can walk an order out of its own market: a
        // block trade amended down below the floor, a normal-market order amended off-lot. Same rules.
        String badWindow = windows.violation(sec, o.getTradeWindow(), qty, notional);
        if (badWindow != null) return reject(badWindow);

        ClientAccount acc = o.getAccountId() == null ? null : accountRepo.findById(o.getAccountId()).orElse(null);
        if (acc != null && acc.getStatus() != null && !"ACTIVE".equals(acc.getStatus()))
            return reject("Client account is " + acc.getStatus() + " — trading not allowed");

        BigDecimal effMax = null;
        for (RiskLimit l : new RiskLimit[]{limit("CLIENT", o.getAccountId()),
                o.getDealerId() == null ? null : limit("TRADER", o.getDealerId()), limit("BROKER", o.getBrokerId())}) {
            if (l == null || !Boolean.TRUE.equals(l.getEnabled())) continue;
            if (l.getMaxOrderQty() != null && l.getMaxOrderQty() > 0 && qty > l.getMaxOrderQty())
                return reject(l.getScope() + " order-qty limit exceeded");
            if (l.getMaxOrderValue() != null && l.getMaxOrderValue().signum() > 0 && notional.compareTo(l.getMaxOrderValue()) > 0)
                return reject(l.getScope() + " order-value limit exceeded");
            if (effMax == null || (l.getMaxOrderValue() != null && l.getMaxOrderValue().signum() > 0
                    && l.getMaxOrderValue().compareTo(effMax) < 0)) effMax = l.getMaxOrderValue();
        }
        long dayVol = md == null || md.getVolume() == null ? 0 : md.getVolume();
        AiRiskScoringService.Score s = ai.score(o, ltp, dayVol, 0, effMax);
        return new RiskResult(true, "OK", s.value(), s.flags());
    }

    private RiskLimit limit(String scope, Long entityId) {
        if (entityId == null) return null;
        Optional<RiskLimit> l = limitRepo.findByScopeAndEntityId(scope, entityId);
        return l.orElse(null);
    }

    private RiskResult reject(String reason) {
        return new RiskResult(false, reason, BigDecimal.valueOf(100), List.of(reason));
    }
}
