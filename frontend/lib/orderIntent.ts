"use client";

/**
 * "Start an order from here" — a one-way signal any screen can send to the floating order grid.
 *
 * A custom DOM event rather than a context provider or a store: the sender (a depth ladder, a
 * watchlist row, a heatmap tile) and the receiver (the launcher mounted in Shell) sit on opposite
 * sides of the tree, and neither should have to know the other exists to offer "trade this".
 */

export const ORDER_INTENT = "oms:order-intent";

export type OrderIntent = {
  securityId: number;
  /** Price to seed. The trader can still overwrite it; it is marked as inherited, not chosen. */
  price?: number | null;
  /**
   * Deliberately absent from every caller today.
   *
   * Clicking a bid could mean "join the bid" (buy) or "hit the bid" (sell) — the convention differs
   * between terminals, and guessing wrong picks the wrong direction on the one field where wrong is
   * unrecoverable. The instrument and price are seeded; the side stays unset and the row stays
   * un-sendable until a human chooses it.
   */
  side?: "BUY" | "SELL";
};

export function startOrder(intent: OrderIntent) {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent<OrderIntent>(ORDER_INTENT, { detail: intent }));
}
