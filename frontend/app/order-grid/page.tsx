"use client";

/**
 * The /order-grid route. The grid itself lives in components/grid/OrderGridBody so the identical
 * component also serves the floating window that can be opened over any other screen — one screen,
 * one set of validation rules, no chance of the two drifting apart.
 */

import { useCallback, useState } from "react";
import { Shell } from "@/components/Shell";
import { OrderGridBody } from "@/components/grid/OrderGridBody";

export default function OrderGridPage() {
  // The body owns the SSE stream; Shell owns the Live/Offline badge. Extracting the grid dropped
  // this wiring, so this page read "Offline" permanently even with a healthy stream — the same
  // defect the badge had before, reintroduced by the refactor.
  const [connected, setConnected] = useState(false);
  const onConnected = useCallback((c: boolean) => setConnected(c), []);

  return (
    <Shell title="Order Grid" connected={connected}>
      <OrderGridBody onConnected={onConnected} />
    </Shell>
  );
}
