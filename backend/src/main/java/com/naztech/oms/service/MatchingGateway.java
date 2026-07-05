package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.entity.OmsOrder;

/**
 * Abstraction over the Trading / Matching Engine. The OMS routes orders here.
 *
 * <p>Today the implementation is an in-process price-time-priority simulator
 * ({@link SimulatedMatchingEngine}). For production this same interface is
 * implemented by a FIX/FAST/ITCH gateway to the real Exchange Matching Engine
 * (NASDAQ X-Stream INET today; next-gen ME tomorrow) — with zero changes to the
 * OMS order/risk/portfolio layers above it.
 */
public interface MatchingGateway {

    /** Route a risk-approved order to the engine for matching/resting. */
    void submit(OmsOrder order);

    /** Arm a STOP / STOP_LIMIT order; it triggers when the LTP crosses its stop price. */
    void arm(OmsOrder stopOrder);

    /** Pull a resting order off the book. */
    void cancel(OmsOrder order);

    /** Aggregated market depth (bids/asks) for a security. */
    Depth depth(Long securityId, int levels);
}
