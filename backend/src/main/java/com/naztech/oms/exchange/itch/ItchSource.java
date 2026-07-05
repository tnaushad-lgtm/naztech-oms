package com.naztech.oms.exchange.itch;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/**
 * A source of ITCH v2.2 messages consumed by {@link ItchGateway}. This is the transport-abstraction
 * seam: the gateway (and everything above it — market data, depth, the UI) is agnostic to whether the
 * messages come from the in-house {@link SimulatorSource}, a recorded {@link FileReplaySource}, or a
 * future live SoupBinTCP / MoldUDP64 reader. Adding a new venue transport is a new {@code ItchSource}
 * implementation only — no change to {@code ItchGateway}, {@code MarketDataService} or the frontend.
 *
 * <p>The gateway pulls: {@link #open()} once at session start (directory / reference prices / opening
 * book), then {@link #poll()} each scheduled tick for the next batch of market activity. A pull model
 * fits the gateway's existing scheduler; a push transport wraps trivially (buffer then drain in poll()).
 */
public interface ItchSource extends Closeable {

    /** Session-start messages to seed the books (empty if the source has none, e.g. a replay file). */
    List<Itch.Msg> open();

    /** Next batch of messages; empty when the source is momentarily idle or exhausted. */
    List<Itch.Msg> poll();

    /** False once the source can produce no more messages (e.g. a replay reached EOF). */
    default boolean isActive() { return true; }

    /** Short human label for logs / the connectivity page (e.g. {@code simulator}, {@code replay:foo.itch}). */
    default String name() { return "itch-source"; }

    @Override
    default void close() throws IOException { /* no-op by default */ }
}
