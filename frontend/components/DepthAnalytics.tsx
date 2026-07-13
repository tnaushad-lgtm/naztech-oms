"use client";

import { nf, nfInt } from "@/lib/format";

export type Level = { price: number; quantity: number; orders: number };
export type Depth = { symbol: string; ltp: number; bids: Level[]; asks: Level[] };

/**
 * Everything a dealer reads off an order book, derived from the ladder itself.
 *
 * <p>The exchange sends price levels; the meaning is in the arithmetic. Spread tells you the cost of
 * crossing; the microprice tells you where the next trade is likely to print (a size-weighted mid, so
 * a book that is 90% bid points above the naive mid); imbalance is the classic short-horizon pressure
 * signal; and the cumulative curve shows how far a large order would have to walk the book to fill.
 */
export type BookStats = {
  bestBid: number;
  bestAsk: number;
  spread: number;
  spreadBps: number;
  mid: number;
  microprice: number;
  bidQty: number;
  askQty: number;
  bidOrders: number;
  askOrders: number;
  imbalance: number;       // −1 = all offers, +1 = all bids
  bidVwap: number;
  askVwap: number;
  levels: number;
};

export function bookStats(d: Depth | null): BookStats | null {
  if (!d || !d.bids?.length || !d.asks?.length) return null;

  const bestBid = d.bids[0].price;
  const bestAsk = d.asks[0].price;
  const spread = bestAsk - bestBid;
  const mid = (bestBid + bestAsk) / 2;

  const bidQty = d.bids.reduce((s, l) => s + l.quantity, 0);
  const askQty = d.asks.reduce((s, l) => s + l.quantity, 0);
  const topBid = d.bids[0].quantity;
  const topAsk = d.asks[0].quantity;

  // Microprice: the mid, weighted by the size resting on the other side. Heavy bid, higher price.
  const microprice = topBid + topAsk > 0
    ? (bestAsk * topBid + bestBid * topAsk) / (topBid + topAsk)
    : mid;

  const wavg = (ls: Level[]) => {
    const q = ls.reduce((s, l) => s + l.quantity, 0);
    return q > 0 ? ls.reduce((s, l) => s + l.price * l.quantity, 0) / q : 0;
  };

  return {
    bestBid, bestAsk, spread,
    spreadBps: mid > 0 ? (spread / mid) * 10000 : 0,
    mid, microprice,
    bidQty, askQty,
    bidOrders: d.bids.reduce((s, l) => s + l.orders, 0),
    askOrders: d.asks.reduce((s, l) => s + l.orders, 0),
    imbalance: bidQty + askQty > 0 ? (bidQty - askQty) / (bidQty + askQty) : 0,
    bidVwap: wavg(d.bids),
    askVwap: wavg(d.asks),
    levels: Math.max(d.bids.length, d.asks.length),
  };
}

/** Bid pressure vs offer pressure, as a single bar. The number a dealer glances at. */
export function ImbalanceBar({ imbalance }: { imbalance: number }) {
  const bidPct = ((imbalance + 1) / 2) * 100;
  const lean = imbalance > 0.2 ? "Bid-heavy" : imbalance < -0.2 ? "Offer-heavy" : "Balanced";
  const tone = imbalance > 0.2 ? "text-bull" : imbalance < -0.2 ? "text-bear" : "text-ink-300";
  return (
    <div>
      <div className="mb-1 flex items-center justify-between text-[11px]">
        <span className="text-ink-500">Book imbalance</span>
        <span className={`font-semibold ${tone}`}>{lean} · {(imbalance * 100).toFixed(1)}%</span>
      </div>
      <div className="flex h-2.5 overflow-hidden rounded-full bg-surface/[0.08]">
        <div className="bg-bull/70 transition-all duration-300" style={{ width: `${bidPct}%` }} />
        <div className="bg-bear/70 transition-all duration-300" style={{ width: `${100 - bidPct}%` }} />
      </div>
    </div>
  );
}

/**
 * The depth curve: cumulative size available at or better than each price.
 *
 * <p>Read it as "how much can I fill, and how far do I have to reach to get it" — the steeper the
 * wall, the more liquidity is stacked there; a shallow slope is a book that will run away from a
 * large order.
 */
export function DepthCurve({ depth, height = 200 }: { depth: Depth; height?: number }) {
  const bids = [...depth.bids].sort((a, b) => b.price - a.price);
  const asks = [...depth.asks].sort((a, b) => a.price - b.price);
  if (!bids.length || !asks.length) return null;

  const cum = (ls: Level[]) => {
    let run = 0;
    return ls.map((l) => ({ price: l.price, cumulative: (run += l.quantity) }));
  };
  const cb = cum(bids);
  const ca = cum(asks);

  const maxQty = Math.max(cb[cb.length - 1].cumulative, ca[ca.length - 1].cumulative, 1);
  const lo = Math.min(cb[cb.length - 1].price, bids[bids.length - 1].price);
  const hi = Math.max(ca[ca.length - 1].price, asks[asks.length - 1].price);
  const span = hi - lo || 1;

  const W = 100;
  const x = (p: number) => ((p - lo) / span) * W;
  const y = (q: number) => height - (q / maxQty) * (height - 8);

  // Step areas: a book is a staircase, not a smooth line — drawing it as a curve would flatter it.
  const area = (pts: { price: number; cumulative: number }[], toRight: boolean) => {
    const d: string[] = [];
    const start = toRight ? x(pts[0].price) : x(pts[0].price);
    d.push(`M ${start} ${height}`);
    pts.forEach((p, i) => {
      const px = x(p.price);
      const py = y(p.cumulative);
      if (i === 0) d.push(`L ${px} ${py}`);
      else {
        d.push(`L ${px} ${y(pts[i - 1].cumulative)}`);
        d.push(`L ${px} ${py}`);
      }
    });
    d.push(`L ${x(pts[pts.length - 1].price)} ${height}`);
    d.push("Z");
    return d.join(" ");
  };

  const midX = x((bids[0].price + asks[0].price) / 2);

  return (
    <svg viewBox={`0 0 ${W} ${height}`} preserveAspectRatio="none" className="h-[200px] w-full">
      <path d={area(cb, false)} fill="rgba(34,197,94,0.18)" stroke="rgb(34,197,94)" strokeWidth="0.4" />
      <path d={area(ca, true)} fill="rgba(251,91,107,0.18)" stroke="rgb(251,91,107)" strokeWidth="0.4" />
      <line x1={midX} y1="0" x2={midX} y2={height} stroke="rgba(255,255,255,0.25)" strokeWidth="0.3" strokeDasharray="2 2" />
    </svg>
  );
}

/** One statistic, stated plainly. */
export function Stat({ k, v, sub, tone }: { k: string; v: string; sub?: string; tone?: "bull" | "bear" | "cyan" }) {
  const color = tone === "bull" ? "text-bull" : tone === "bear" ? "text-bear"
    : tone === "cyan" ? "text-aurora-cyan" : "text-ink-100";
  return (
    <div className="glass-soft px-3 py-2">
      <div className="text-[10px] uppercase tracking-wider text-ink-500">{k}</div>
      <div className={`tnum text-base font-semibold ${color}`}>{v}</div>
      {sub && <div className="text-[10px] text-ink-600">{sub}</div>}
    </div>
  );
}

/**
 * The full ladder, with cumulative size — the part a trader actually works from.
 *
 * `changed` holds the price levels whose size moved on the last push, keyed `b<price>` / `a<price>`
 * (see `useDepth`). Those rows are lit for a moment: on a live book the numbers change faster than
 * anyone can watch, so the ladder has to point at what moved rather than leave it to be noticed.
 */
export function FullLadder({ depth, onPickPrice, changed }:
  { depth: Depth; onPickPrice?: (p: number) => void; changed?: Set<string> }) {
  const rows = Math.max(depth.bids.length, depth.asks.length);
  let cumB = 0;
  let cumA = 0;
  const bidCum = depth.bids.map((l) => (cumB += l.quantity));
  const askCum = depth.asks.map((l) => (cumA += l.quantity));
  const max = Math.max(cumB, cumA, 1);

  return (
    <div className="overflow-hidden rounded-xl border border-line/[0.08]">
      <div className="grid grid-cols-8 bg-surface/[0.04] px-2 py-1.5 text-[10px] uppercase tracking-wider text-ink-500">
        <span>Ord</span><span className="text-right">Cum</span><span className="text-right">Qty</span>
        <span className="text-right">Bid</span>
        <span>Ask</span><span>Qty</span><span>Cum</span><span className="text-right">Ord</span>
      </div>
      {Array.from({ length: rows }).map((_, i) => {
        const b = depth.bids[i];
        const a = depth.asks[i];
        const bLit = b && changed?.has(`b${b.price}`);
        const aLit = a && changed?.has(`a${a.price}`);
        return (
          <div key={i} className="relative grid grid-cols-8 items-center px-2 py-1 text-[12px] tnum
                                  border-t border-line/[0.05] hover:bg-surface/[0.04]">
            {/* the depth bars: how much is stacked at or better than this level */}
            {b && <div className="absolute inset-y-0 left-0 bg-bull/10"
              style={{ width: `${(bidCum[i] / max) * 50}%` }} />}
            {a && <div className="absolute inset-y-0 right-0 bg-bear/10"
              style={{ width: `${(askCum[i] / max) * 50}%` }} />}

            {/* …and the flash: this level's size just moved */}
            {bLit && <div className="absolute inset-y-0 left-0 w-1/2 bg-bull/25 transition-opacity duration-200" />}
            {aLit && <div className="absolute inset-y-0 right-0 w-1/2 bg-bear/25 transition-opacity duration-200" />}

            <span className="relative z-10 text-ink-600">{b?.orders ?? ""}</span>
            <span className="relative z-10 text-right text-ink-500">{b ? nfInt(bidCum[i]) : ""}</span>
            <span className="relative z-10 text-right text-ink-300">{b ? nfInt(b.quantity) : ""}</span>
            <button className="relative z-10 text-right font-semibold text-bull hover:underline"
              onClick={() => b && onPickPrice?.(b.price)}>{b ? nf(b.price) : ""}</button>

            <button className="relative z-10 text-left font-semibold text-bear hover:underline"
              onClick={() => a && onPickPrice?.(a.price)}>{a ? nf(a.price) : ""}</button>
            <span className="relative z-10 text-ink-300">{a ? nfInt(a.quantity) : ""}</span>
            <span className="relative z-10 text-ink-500">{a ? nfInt(askCum[i]) : ""}</span>
            <span className="relative z-10 text-right text-ink-600">{a?.orders ?? ""}</span>
          </div>
        );
      })}
    </div>
  );
}
