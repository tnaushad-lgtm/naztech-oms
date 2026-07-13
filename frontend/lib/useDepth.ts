"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { useLive } from "./useLive";
import { get, post } from "./api";

export type Level = { price: number; quantity: number; orders: number };
export type Depth = { securityId?: number; symbol: string; ltp: number; seq?: number; bids: Level[]; asks: Level[] };

/** How long a changed row stays lit. Long enough to catch the eye, short enough not to smear. */
const FLASH_MS = 700;
/** Re-assert interest well inside the backend's watch TTL, so the push never lapses mid-stare. */
const KEEPALIVE_MS = 20_000;

/**
 * The order book, pushed rather than polled.
 *
 * The ladder used to ask for the book every two seconds and redraw whatever came back. That is both
 * too slow (levels come and go inside the gap) and too expensive (the same question, sixty times a
 * minute, whether or not anything moved). Now the backend pushes the book on the SSE stream when it
 * changes, and this hook does three things with it:
 *
 *  - registers interest (`/depth/watch`), and keeps registering while the instrument stays selected;
 *  - watches the sequence number, and re-syncs from `/depth/snapshot` the moment it skips — a gap
 *    means a frame was dropped, and a ladder built on a book with a hole in it is worse than no
 *    ladder at all;
 *  - reports which price levels actually changed, so the UI can flash them instead of the dealer
 *    having to spot the difference.
 */
export function useDepth(securityId: number | undefined, levels = 10) {
  const [depth, setDepth] = useState<Depth | null>(null);
  const [changed, setChanged] = useState<Set<string>>(new Set());
  const [pushed, setPushed] = useState(false);   // true once a live update has actually arrived

  const seq = useRef(0);
  const prev = useRef<Depth | null>(null);
  const id = useRef<number | undefined>(securityId);
  id.current = securityId;

  /** Which rows moved between two books. Keyed side+price, since price is the row's identity. */
  const diff = (before: Depth | null, after: Depth): Set<string> => {
    const out = new Set<string>();
    if (!before) return out;
    const seen = (side: "b" | "a", from: Level[], to: Level[]) => {
      const was = new Map(from.map((l) => [l.price, l.quantity]));
      to.forEach((l) => {
        if (was.get(l.price) !== l.quantity) out.add(`${side}${l.price}`);
      });
    };
    seen("b", before.bids || [], after.bids || []);
    seen("a", before.asks || [], after.asks || []);
    return out;
  };

  const apply = useCallback((next: Depth) => {
    const moved = diff(prev.current, next);
    prev.current = next;
    seq.current = next.seq ?? seq.current;
    setDepth(next);
    if (moved.size) {
      setChanged(moved);
      setTimeout(() => setChanged(new Set()), FLASH_MS);
    }
  }, []);

  const resync = useCallback(async () => {
    if (!id.current) return;
    try {
      const d = await get<Depth>(`/api/market/${id.current}/depth/snapshot?levels=${levels}`);
      prev.current = null;               // after a gap, nothing is "changed" — it is all just new
      apply(d);
    } catch {}
  }, [levels, apply]);

  // Register interest, and keep it registered. The reply carries the current book, so this doubles
  // as a safety net: even if the stream dies entirely, the ladder still refreshes on this cadence.
  useEffect(() => {
    if (!securityId) return;
    let stop = false;
    setDepth(null);
    setPushed(false);
    prev.current = null;
    seq.current = 0;

    const watch = async () => {
      try {
        const d = await post<Depth>(`/api/market/${securityId}/depth/watch?levels=${levels}`, {});
        if (!stop) apply(d);
      } catch {}
    };
    watch();
    const t = setInterval(watch, KEEPALIVE_MS);
    return () => { stop = true; clearInterval(t); };
  }, [securityId, levels, apply]);

  useLive((type, data) => {
    if (type !== "depth" || !id.current || data?.securityId !== id.current) return;
    const next: Depth = data;
    // A skipped sequence means we missed a push. Do not paper over it by applying this one on top of
    // a book that is now missing an update: go and get the truth.
    if (next.seq != null && seq.current > 0 && next.seq > seq.current + 1) {
      resync();
      return;
    }
    setPushed(true);
    apply(next);
  });

  return { depth, changed, pushed };
}
