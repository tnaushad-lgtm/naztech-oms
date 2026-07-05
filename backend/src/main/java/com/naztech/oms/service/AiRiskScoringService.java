package com.naztech.oms.service;

import com.naztech.oms.entity.OmsOrder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * On-prem AI/heuristic order-risk scoring (0..100) — a value-add the RFP explicitly
 * invites (§3.13). It surfaces fat-finger prices, oversized clips, wash-trade patterns
 * and aggressive sweeps BEFORE the order reaches the matching engine, complementing the
 * hard pre-trade limits in {@link RiskService}. Fully deterministic & explainable, so it
 * is auditable — a soft signal, never an opaque black box.
 */
@Service
public class AiRiskScoringService {

    public record Score(BigDecimal value, List<String> flags) {}

    /**
     * @param order      the incoming order
     * @param ltp        last traded price of the security (0 if unknown)
     * @param dayVolume  today's traded volume for the security (liquidity proxy)
     * @param washCount  number of opposite-side open orders by the same client/security
     * @param maxOrderValue the client/trader notional cap (for sizing context)
     */
    public Score score(OmsOrder order, BigDecimal ltp, long dayVolume, int washCount, BigDecimal maxOrderValue) {
        double score = 0;
        List<String> flags = new ArrayList<>();

        BigDecimal px = order.getPrice() == null ? BigDecimal.ZERO : order.getPrice();
        long qty = order.getQuantity() == null ? 0 : order.getQuantity();
        BigDecimal notional = px.multiply(BigDecimal.valueOf(qty));

        // 1) Fat-finger: price far from LTP.
        if (ltp != null && ltp.signum() > 0 && px.signum() > 0) {
            double dev = Math.abs(px.subtract(ltp).doubleValue()) / ltp.doubleValue();
            if (dev > 0.20)      { score += 45; flags.add(String.format("Price %.1f%% off LTP (fat-finger risk)", dev * 100)); }
            else if (dev > 0.10) { score += 25; flags.add(String.format("Price %.1f%% off LTP", dev * 100)); }
            else if (dev > 0.05) { score += 12; flags.add(String.format("Price %.1f%% off LTP", dev * 100)); }
        }

        // 2) Oversized clip vs the day's liquidity.
        if (dayVolume > 0) {
            double frac = (double) qty / dayVolume;
            if (frac > 0.25)      { score += 30; flags.add(String.format("Order is %.0f%% of day volume (liquidity impact)", frac * 100)); }
            else if (frac > 0.10) { score += 15; flags.add(String.format("Order is %.0f%% of day volume", frac * 100)); }
        }

        // 3) Notional concentration vs cap.
        if (maxOrderValue != null && maxOrderValue.signum() > 0) {
            double use = notional.doubleValue() / maxOrderValue.doubleValue();
            if (use > 0.90)      { score += 20; flags.add(String.format("Uses %.0f%% of order-value limit", use * 100)); }
            else if (use > 0.70) { score += 10; flags.add(String.format("Uses %.0f%% of order-value limit", use * 100)); }
        }

        // 4) Wash-trade pattern: opposite open order same client + security.
        if (washCount > 0) {
            score += 35;
            flags.add("Opposite-side open order exists (possible wash trade)");
        }

        // 5) Market order aggressiveness.
        if ("MARKET".equalsIgnoreCase(order.getOrderType())) {
            score += 8;
            flags.add("Market order — no price protection");
        }

        score = Math.min(100, score);
        if (flags.isEmpty()) flags.add("No anomalies detected");
        return new Score(BigDecimal.valueOf(Math.round(score * 10.0) / 10.0), flags);
    }
}
