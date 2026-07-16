package com.naztech.oms.exchange.fix;

import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.entity.Trade;
import com.naztech.oms.exchange.OrderFillApplier;
import com.naztech.oms.perf.ExecutionStats;
import com.naztech.oms.repo.OmsOrderRepo;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.repo.TradeRepo;
import com.naztech.oms.service.AuditService;
import com.naztech.oms.service.MarketDataService;
import com.naztech.oms.service.PortfolioService;
import com.naztech.oms.service.StreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import quickfix.Message;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.OrdStatus;
import quickfix.field.OrigClOrdID;
import quickfix.field.Text;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps inbound FIX {@code ExecutionReport(8)} and {@code OrderCancelReject(9)} messages onto the
 * existing OMS order lifecycle — the exact same eight statuses, fill accounting, portfolio marking,
 * trade tape and SSE the simulator uses, so the UI is unchanged whether fills come from the
 * simulator or over FIX. Reads {@code ExecType(150)} and {@code OrdStatus(39)} independently and
 * trusts the exchange's {@code CumQty(14)}/{@code AvgPx(6)} when present.
 */
@Service
@ConditionalOnProperty(prefix = "fix", name = "enabled", havingValue = "true")
public class ExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final OmsOrderRepo orderRepo;
    private final TradeRepo tradeRepo;
    private final SecurityRepo securityRepo;
    private final PortfolioService portfolio;
    private final MarketDataService marketData;
    private final StreamService stream;
    private final AuditService audit;
    private final OrderFillApplier fills;
    private final ExecutionStats stats;
    private final com.naztech.oms.service.BondService bonds;

    public ExecutionService(OmsOrderRepo orderRepo, TradeRepo tradeRepo, SecurityRepo securityRepo,
                            PortfolioService portfolio, MarketDataService marketData, StreamService stream,
                            AuditService audit, OrderFillApplier fills, ExecutionStats stats,
                            com.naztech.oms.service.BondService bonds) {
        this.orderRepo = orderRepo;
        this.tradeRepo = tradeRepo;
        this.securityRepo = securityRepo;
        this.portfolio = portfolio;
        this.marketData = marketData;
        this.stream = stream;
        this.audit = audit;
        this.fills = fills;
        this.stats = stats;
        this.bonds = bonds;
    }

    @Transactional
    public void onExecutionReport(Message msg) {
        try {
            OmsOrder o = lookup(msg);
            if (o == null) { log.warn("FIX ExecutionReport for unknown order (ClOrdID/OrigClOrdID)"); return; }

            char execType = msg.isSetField(ExecType.FIELD) ? msg.getChar(ExecType.FIELD) : '0';
            char ordStatus = msg.isSetField(OrdStatus.FIELD) ? msg.getChar(OrdStatus.FIELD) : execType;

            double lastQty = msg.isSetField(LastQty.FIELD) ? msg.getDouble(LastQty.FIELD) : 0;
            if (lastQty > 0) {
                BigDecimal lastPx = msg.isSetField(LastPx.FIELD)
                        ? BigDecimal.valueOf(msg.getDouble(LastPx.FIELD)) : nz(o.getPrice());
                recordFill(o, (long) lastQty, lastPx, msg);
            }

            // Prefer the exchange's cumulative figures when present (authoritative, dedup-safe).
            if (msg.isSetField(CumQty.FIELD)) o.setFilledQty((long) msg.getDouble(CumQty.FIELD));
            if (msg.isSetField(AvgPx.FIELD) && msg.getDouble(AvgPx.FIELD) > 0)
                o.setAvgFillPrice(BigDecimal.valueOf(msg.getDouble(AvgPx.FIELD)));

            String st = mapStatus(ordStatus, o);
            o.setStatus(st);
            if (ordStatus == '8' && msg.isSetField(Text.FIELD)) o.setRejectReason(msg.getString(Text.FIELD));
            o.setUpdatedAt(LocalDateTime.now());
            orderRepo.save(o);
            stats.execution(st);       // the round trip actually completed — what a perf run must count
            audit.orderEvent(o.getId(), "EXCH_" + st,
                    "FIX ExecType=" + execType + " OrdStatus=" + ordStatus + (lastQty > 0 ? " fill " + (long) lastQty : ""));
            publishOrder(o);
        } catch (Exception e) {
            log.error("onExecutionReport failed: {}", e.toString(), e);
        }
    }

    public void onCancelReject(Message msg) {
        try {
            OmsOrder o = lookup(msg);
            if (o == null) return;
            String text = msg.isSetField(Text.FIELD) ? msg.getString(Text.FIELD) : "Cancel rejected by exchange";
            audit.orderEvent(o.getId(), "CANCEL_REJECT", text);
            publishOrder(o);
        } catch (Exception e) {
            log.warn("onCancelReject failed: {}", e.toString());
        }
    }

    private OmsOrder lookup(Message msg) throws Exception {
        if (msg.isSetField(ClOrdID.FIELD)) {
            OmsOrder o = orderRepo.findByOrderRef(msg.getString(ClOrdID.FIELD)).orElse(null);
            if (o != null) return o;
        }
        if (msg.isSetField(OrigClOrdID.FIELD)) {
            return orderRepo.findByOrderRef(msg.getString(OrigClOrdID.FIELD)).orElse(null);
        }
        return null;
    }

    private void recordFill(OmsOrder o, long qty, BigDecimal px, Message msg) throws Exception {
        Trade t = new Trade();
        String execId = msg.isSetField(ExecID.FIELD) ? msg.getString(ExecID.FIELD) : String.valueOf(System.currentTimeMillis());
        t.setTradeRef("FIX-" + execId);
        t.setSecurityId(o.getSecurityId());
        if ("BUY".equals(o.getSide())) t.setBuyOrderId(o.getId()); else t.setSellOrderId(o.getId());
        t.setPrice(px);
        t.setQuantity(qty);
        t.setAggressorSide(o.getSide());
        t.setExecutedAt(LocalDateTime.now());
        stampBondEconomics(t, o, px, msg);
        tradeRepo.save(t);

        if (o.getAccountId() != null) portfolio.applyFill(o.getAccountId(), o.getSecurityId(), o.getSide(), qty, px);
        marketData.applyTrade(o.getSecurityId(), px, qty, null, null);

        Security s = securityRepo.findById(o.getSecurityId()).orElse(null);
        Map<String, Object> tick = new LinkedHashMap<>();
        tick.put("securityId", o.getSecurityId());
        tick.put("symbol", s == null ? "?" : s.getSymbol());
        tick.put("price", px);
        tick.put("qty", qty);
        tick.put("side", o.getSide());
        tick.put("ts", t.getExecutedAt().toString());
        stream.publish("trade", tick);
    }

    /**
     * Bond trades carry their settlement economics (DSE Bond BRS §1.1.2/§1.1.8): the traded price is
     * the CLEAN price, and settlement price = clean + accrued interest.
     *
     * <p>The BRS says the venue puts Accrued Interest on the Execution Report — FIX tag 159,
     * {@code AccruedInterestAmt} — and when it does, <b>the venue's figure wins</b>: it knows the real
     * settlement calendar and coupon schedule, and disagreeing with the exchange about money is a
     * reconciliation break. When the report carries no tag 159 (our local exchange doesn't), we compute
     * it ourselves from the instrument's coupon terms at the order's settlement window.
     */
    private void stampBondEconomics(Trade t, OmsOrder o, BigDecimal px, Message msg) {
        Security s = securityRepo.findById(o.getSecurityId()).orElse(null);
        if (!bonds.isBond(s)) return;
        String window = o.getTradeWindow() == null ? "NORMAL" : o.getTradeWindow();
        try {
            if (msg.isSetField(159)) {                                     // AccruedInterestAmt from the venue
                t.setAccruedInterest(BigDecimal.valueOf(msg.getDouble(159)));
            } else {
                t.setAccruedInterest(bonds.accruedFor(s, window));
            }
        } catch (Exception e) {
            t.setAccruedInterest(bonds.accruedFor(s, window));
        }
        t.setTradeYield(bonds.yieldAt(s, px, window));
    }

    /** DSE FIX OrdStatus(39) → OMS lifecycle status. */
    private String mapStatus(char ordStatus, OmsOrder o) {
        return switch (ordStatus) {
            case '0' -> "OPEN";       // New (accepted, resting)
            case '1' -> "PARTIAL";    // Partially filled
            case '2' -> "FILLED";     // Filled
            case '4' -> "CANCELLED";  // Canceled
            case '8' -> "REJECTED";   // Rejected
            case 'C' -> "EXPIRED";    // Expired
            case '6' -> o.getStatus(); // Pending Cancel — do not flip yet
            default -> fills.deriveStatus(o);
        };
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

    private static BigDecimal nz(BigDecimal b) { return b == null ? BigDecimal.ZERO : b; }
}
