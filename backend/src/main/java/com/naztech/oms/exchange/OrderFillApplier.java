package com.naztech.oms.exchange;

import com.naztech.oms.entity.OmsOrder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for applying a fill to an {@link OmsOrder}: it advances the
 * filled quantity and the volume-weighted average fill price, and derives the resulting
 * lifecycle status.
 *
 * <p>Extracted verbatim from {@code SimulatedMatchingEngine} so that the simulator, the
 * FIX {@code ExecutionReport} handler (Phase 5) and the ITCH-driven fills (Phase 4) all
 * mutate order state identically — no drift between demo and real-exchange behaviour.
 * Behaviour is intentionally unchanged: VWAP at scale 4, {@link RoundingMode#HALF_UP}.
 */
@Component
public class OrderFillApplier {

    /**
     * Apply one execution to the order: {@code filledQty += qty} and
     * {@code avgFillPrice = VWAP(existing, this fill)} at scale 4, HALF_UP.
     */
    public void applyFill(OmsOrder o, long qty, BigDecimal px) {
        long oldFilled = o.getFilledQty() == null ? 0 : o.getFilledQty();
        BigDecimal oldAvg = o.getAvgFillPrice() == null ? BigDecimal.ZERO : o.getAvgFillPrice();
        long newFilled = oldFilled + qty;
        BigDecimal newAvg = oldAvg.multiply(BigDecimal.valueOf(oldFilled))
                .add(px.multiply(BigDecimal.valueOf(qty)))
                .divide(BigDecimal.valueOf(newFilled), 4, RoundingMode.HALF_UP);
        o.setFilledQty(newFilled);
        o.setAvgFillPrice(newAvg);
    }

    /**
     * Derive lifecycle status from quantities: {@code FILLED} when nothing remains,
     * {@code PARTIAL} when partly filled, otherwise {@code OPEN} (accepted, nothing filled yet).
     * Matches the existing simulator logic for both aggressor and resting orders.
     */
    public String deriveStatus(OmsOrder o) {
        if (o.remainingQty() <= 0) return "FILLED";
        long filled = o.getFilledQty() == null ? 0 : o.getFilledQty();
        return filled > 0 ? "PARTIAL" : "OPEN";
    }
}
