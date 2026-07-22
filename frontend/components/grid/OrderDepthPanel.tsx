"use client";

/**
 * The order book, beside the order you are writing.
 *
 * Standard practice on every professional terminal — TT, CQG, Bloomberg EMSX — is that the ladder
 * sits next to the ticket, because the two questions a trader asks are "where is the market" and
 * "where do I want to be", and answering them on separate screens is how you send a bid through the
 * offer. This follows the row you are editing, so moving down a list of client instructions moves
 * the book with you.
 *
 * Three things it shows that a plain ladder does not:
 *
 *  1. WHERE YOUR PRICE SITS. The level matching the row's limit price is outlined, and the header
 *     says plainly whether that price would rest on the book or cross the spread and trade. That is
 *     the difference between an order that works and an order that fills, and it is worth knowing
 *     before you press send, not after.
 *  2. WHERE YOUR OWN ORDERS ARE. Levels carrying your working orders are marked. This matters
 *     because an exchange feed is anonymous: ITCH publishes aggregate size per level, so after you
 *     send, the level's quantity grows — it never says "that is yours". We know our own orders, so
 *     we can say it.
 *  3. THAT IT IS LIVE. The book is pushed over SSE with sequence-gap detection, and changed levels
 *     flash. A stale ladder beside a live ticket is worse than none.
 */

import { useEffect, useMemo, useState } from "react";
import { useDepth } from "@/lib/useDepth";
import { get } from "@/lib/api";
import { nf } from "@/lib/format";

/**
 * As returned by /api/orders. Note there is NO securityId on this DTO — it carries `symbol`.
 * Matching on securityId is why the "your order" markers never appeared: the filter silently
 * matched nothing, so the panel had no own-orders to mark and looked simply as if the feature
 * were missing.
 */
type OwnOrder = { id: number; symbol: string; price: number; side: string; quantity: number; filledQty?: number; status: string };

export function OrderDepthPanel({
  securityId, symbol, side, price, onPickPrice, levels = 6, compact = false, wide = false,
}: {
  securityId: number | null;
  symbol?: string;
  /** The row's side and price — used to say whether this order rests or crosses. */
  side?: "BUY" | "SELL";
  price?: number | null;
  onPickPrice?: (p: number) => void;
  levels?: number;
  compact?: boolean;
  /**
   * Laid out under the order grid rather than beside it, so it is short and very wide. Bid and ask
   * ladders sit side by side and each gets a cumulative column, because the horizontal room exists
   * and cumulative size is what tells a trader how far a market order would walk.
   */
  wide?: boolean;
}) {
  const { depth, changed, pushed } = useDepth(securityId ?? undefined, levels);
  const [own, setOwn] = useState<OwnOrder[]>([]);

  // Our own working orders, so their levels can be marked. Polled rather than pushed because it
  // changes on our action, not the market's — and a stale marker here is cosmetic, not dangerous.
  const mySymbol = symbol || depth?.symbol;
  useEffect(() => {
    if (!securityId || !mySymbol) { setOwn([]); return; }
    let alive = true;
    const tick = async () => {
      try {
        const all = await get<OwnOrder[]>("/api/orders");
        if (!alive) return;
        setOwn((all || []).filter((o) => o.symbol === mySymbol && o.status === "OPEN"));
      } catch { /* the ladder is still useful without the markers */ }
    };
    tick();
    const t = setInterval(tick, 4000);
    return () => { alive = false; clearInterval(t); };
  }, [securityId, mySymbol]);

  const ownAt = useMemo(() => {
    const m = new Map<string, number>();
    for (const o of own) {
      const k = `${o.side}:${nf(o.price, 4)}`;
      m.set(k, (m.get(k) || 0) + (o.quantity - (o.filledQty || 0)));
    }
    return m;
  }, [own]);

  const bestBid = depth?.bids?.[0]?.price ?? 0;
  const bestAsk = depth?.asks?.[0]?.price ?? 0;
  const spread = bestBid && bestAsk ? bestAsk - bestBid : 0;

  /**
   * Would this order rest, or trade? A buy at or above the best offer lifts it; a sell at or below
   * the best bid hits it. Anything else joins the queue.
   */
  const outcome = useMemo(() => {
    if (!price || !side || !bestBid || !bestAsk) return null;
    if (side === "BUY") return price >= bestAsk ? "crosses" : "rests";
    return price <= bestBid ? "crosses" : "rests";
  }, [price, side, bestBid, bestAsk]);

  if (!securityId) {
    return (
      <div className="flex h-full items-center justify-center text-[11px] text-ink-400">
        Pick an instrument to see its order book
      </div>
    );
  }

  // Which of our orders are priced outside the levels currently on screen.
  const shownPrices = new Set([...(depth?.bids || []), ...(depth?.asks || [])].map((l) => nf(l.price, 4)));
  const offScreen = own.filter((o) => !shownPrices.has(nf(o.price, 4)));

  const max = Math.max(1, ...(depth?.bids || []).map((b) => b.quantity), ...(depth?.asks || []).map((a) => a.quantity));
  const rows = Math.max(depth?.bids?.length || 0, depth?.asks?.length || 0, 1);
  const same = (a?: number, b?: number | null) => a != null && b != null && Math.abs(a - b) < 1e-6;

  const Side = ({ lvl, isBid, cum }: { lvl?: { price: number; quantity: number; orders: number }; isBid: boolean; cum?: number }) => {
    if (!lvl) return <div className="h-[19px]" />;
    const key = `${isBid ? "bid" : "ask"}:${nf(lvl.price, 4)}`;
    const lit = changed?.has(key);
    const mine = ownAt.get(`${isBid ? "BUY" : "SELL"}:${nf(lvl.price, 4)}`);
    const atMyPrice = same(lvl.price, price ?? undefined);
    const pct = (lvl.quantity / max) * 100;

    return (
      <button
        onClick={() => onPickPrice?.(lvl.price)}
        title={`${nf(lvl.quantity, 0)} at ${nf(lvl.price)}${mine ? ` · ${nf(mine, 0)} yours` : ""}`}
        className={`relative flex h-[19px] w-full items-center justify-between px-1 text-[10.5px] tabular-nums transition-colors
          ${atMyPrice ? "ring-1 ring-inset ring-aurora-cyan/70" : ""}
          ${lit ? (isBid ? "bg-bull/25" : "bg-bear/25") : ""} hover:bg-white/[0.06]`}
      >
        {/* proportional size bar, drawn from the outside in so the two sides mirror */}
        <span aria-hidden className={`absolute inset-y-0 ${isBid ? "right-0" : "left-0"} ${isBid ? "bg-bull/15" : "bg-bear/15"}`}
              style={{ width: `${pct}%` }} />
        {isBid ? (
          <>
            {wide && <span className="relative w-[70px] text-left text-ink-400">{cum != null ? nf(cum, 0) : ""}</span>}
            <span className="relative flex-1 text-right text-ink-200">{nf(lvl.quantity, 0)}</span>
            <span className="relative ml-3 flex w-[86px] items-center justify-end gap-1 font-semibold text-bull">
              {mine ? <span className="text-aurora-cyan" title={`${nf(mine, 0)} of this is yours`}>▸</span> : null}
              {nf(lvl.price)}
            </span>
          </>
        ) : (
          <>
            <span className="relative flex w-[86px] items-center gap-1 font-semibold text-bear">
              {nf(lvl.price)}
              {mine ? <span className="text-aurora-cyan" title={`${nf(mine, 0)} of this is yours`}>◂</span> : null}
            </span>
            <span className="relative ml-3 flex-1 text-left text-ink-200">{nf(lvl.quantity, 0)}</span>
            {wide && <span className="relative w-[70px] text-right text-ink-400">{cum != null ? nf(cum, 0) : ""}</span>}
          </>
        )}
      </button>
    );
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* header — the market, and what this order would do to it */}
      <div className="mb-1 flex flex-wrap items-baseline gap-x-3 gap-y-1 px-1 text-[10px]">
        <span className="rounded bg-aurora-cyan/15 px-2 py-0.5 text-[11px] font-bold uppercase tracking-[0.12em] text-aurora-cyan">
          Market Depth
        </span>
        <span className="text-[12px] font-semibold uppercase tracking-wider text-ink-100">{symbol || depth?.symbol || "—"}</span>
        <span className="text-ink-400">
          bid <span className="tabular-nums text-bull">{bestBid ? nf(bestBid) : "—"}</span>
        </span>
        <span className="text-ink-400">
          ask <span className="tabular-nums text-bear">{bestAsk ? nf(bestAsk) : "—"}</span>
        </span>
        <span className="text-ink-400">
          spread <span className="tabular-nums text-ink-200">{spread ? nf(spread) : "—"}</span>
        </span>
        {outcome && (
          <span className={`rounded px-1.5 py-0.5 font-semibold ${
            outcome === "crosses" ? "bg-amber-400/15 text-amber-300" : "bg-aurora-cyan/15 text-aurora-cyan"}`}
            title={outcome === "crosses"
              ? "At this price the order crosses the spread and should trade immediately"
              : "At this price the order joins the queue and waits"}>
            {outcome === "crosses" ? "will trade now" : "will rest on the book"}
          </span>
        )}
        <span className={`ml-auto text-[9px] ${pushed ? "text-bull" : "text-ink-400"}`}>
          {pushed ? "● live" : "waiting for a push"}
        </span>
      </div>

      {/* ladder */}
      <div className="min-h-0 flex-1 overflow-auto rounded border border-line/[0.1]">
        <div className="grid grid-cols-2 gap-px bg-line/[0.08] text-[9px] uppercase tracking-wider text-ink-400">
          <div className="flex items-center bg-obsidian-850 px-1 py-0.5">
            {wide && <span className="w-[70px]">Cum</span>}
            <span className="flex-1 text-right">Qty</span>
            <span className="ml-3 w-[86px] text-right">Bid</span>
          </div>
          <div className="flex items-center bg-obsidian-850 px-1 py-0.5">
            <span className="w-[86px]">Ask</span>
            <span className="ml-3 flex-1 text-left">Qty</span>
            {wide && <span className="w-[70px] text-right">Cum</span>}
          </div>
        </div>
        {Array.from({ length: rows }).map((_, i) => {
          const cumB = (depth?.bids || []).slice(0, i + 1).reduce((n, l) => n + l.quantity, 0);
          const cumA = (depth?.asks || []).slice(0, i + 1).reduce((n, l) => n + l.quantity, 0);
          return (
            <div key={i} className="grid grid-cols-2 gap-px border-t border-line/[0.06]">
              <Side lvl={depth?.bids?.[i]} isBid cum={cumB} />
              <Side lvl={depth?.asks?.[i]} isBid={false} cum={cumA} />
            </div>
          );
        })}
      </div>

      {own.length > 0 && (
        <div className="mt-1 flex flex-wrap gap-x-3 px-1 text-[10px] text-aurora-cyan">
          <span>▸ {own.length} working order{own.length === 1 ? "" : "s"} of yours here</span>
          {/* An order priced away from the touch sits below the visible levels. Showing nothing at
              all reads as "it did not arrive", which is the wrong conclusion entirely. */}
          {offScreen.length > 0 && (
            <span className="text-amber-300" title={offScreen.map((o) => `${o.side} ${nf(o.quantity - (o.filledQty || 0), 0)} @ ${nf(o.price)}`).join(" · ")}>
              {offScreen.length} outside these {levels} levels
              {offScreen[0] ? ` (${offScreen[0].side} ${nf(offScreen[0].quantity - (offScreen[0].filledQty || 0), 0)} @ ${nf(offScreen[0].price)})` : ""}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
