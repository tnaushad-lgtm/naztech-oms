package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.Depth;

/**
 * Source of live market depth for a security — the seam that lets the OMS read the order book from
 * either the in-process simulator or the real DSE ITCH feed, chosen purely by configuration.
 * The OMS core (controller/UI) depends only on this interface, never on ITCH classes.
 */
public interface MarketDataGateway {

    /** Aggregated bid/ask depth for a security (same shape the UI already renders). */
    Depth depth(Long securityId, int levels);

    /** Diagnostic label, e.g. "simulator-book" or "itch". */
    default String source() { return "unknown"; }
}
