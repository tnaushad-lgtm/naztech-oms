"use client";

import { useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/Shell";
import { get } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { useDepth } from "@/lib/useDepth";
import { nf, nfInt } from "@/lib/format";
import { bookStats, Depth, DepthCurve, FullLadder, ImbalanceBar, Stat } from "@/components/DepthAnalytics";
import { startOrder } from "@/lib/orderIntent";

type Row = { securityId: number; symbol: string; name: string; ltp: number; assetClass: string; changePct?: number };

const LEVELS = [10, 20, 50];

/**
 * Market Depth Analysis.
 *
 * <p>The ladder in the terminal shows six levels and stops there. This is the screen a dealer works a
 * large order from: the full book, what it would cost to walk it, and the pressure in it.
 *
 * <p>Everything here is derived from the depth the exchange already publishes — the arithmetic is the
 * product, not another feed.
 */
export default function DepthPage() {
  const [rows, setRows] = useState<Row[]>([]);
  const [sel, setSel] = useState<Row | null>(null);
  const [levels, setLevels] = useState(20);
  const [tape, setTape] = useState<any[]>([]);
  const [q, setQ] = useState("");
  const { connected } = useLive(() => {});

  // The book is pushed, not polled: the backend sends it when it changes and this screen redraws.
  const { depth, changed, pushed } = useDepth(sel?.securityId, levels);

  useEffect(() => {
    get<Row[]>("/api/market/watch?exchange=DSE")
      .then((r) => {
        const tradable = r.filter((x) => x.assetClass !== "INDEX");
        setRows(tradable);
        setSel((s) => s || tradable[0] || null);
      })
      .catch(() => {});
  }, []);

  // The tape is still pulled: it is a log, it only ever grows, and nobody needs it to the millisecond.
  useEffect(() => {
    if (!sel) return;
    let stop = false;
    const load = async () => {
      try {
        const t = await get<any[]>(`/api/market/${sel.securityId}/trades?limit=12`);
        if (!stop) setTape(t || []);
      } catch { /* the tape is optional */ }
    };
    load();
    const t = setInterval(load, 2000);
    return () => { stop = true; clearInterval(t); };
  }, [sel?.securityId]);

  const stats = useMemo(() => bookStats(depth), [depth]);
  const filtered = useMemo(
    () => rows.filter((r) => !q || r.symbol.toLowerCase().includes(q.toLowerCase())
      || r.name?.toLowerCase().includes(q.toLowerCase())),
    [rows, q]);

  const empty = !depth || (!depth.bids?.length && !depth.asks?.length);

  return (
    <Shell title="Market Depth Analysis" connected={connected}>
      <div className="grid grid-cols-12 gap-4">
        {/* instrument picker */}
        <div className="col-span-12 lg:col-span-3">
          <div className="glass flex h-full max-h-[calc(100vh-9rem)] flex-col p-3">
            <input className="field mb-2" placeholder="Filter instruments…" value={q}
              onChange={(e) => setQ(e.target.value)} />
            <div className="min-h-0 flex-1 overflow-y-auto pr-1">
              {filtered.map((r) => (
                <button key={r.securityId} onClick={() => setSel(r)}
                  className={`flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left transition-colors
                    ${sel?.securityId === r.securityId ? "bg-aurora-indigo/20" : "hover:bg-surface/[0.06]"}`}>
                  <div className="min-w-0">
                    <div className="truncate text-[12.5px] font-semibold text-ink-100">{r.symbol}</div>
                    <div className="truncate text-[10px] text-ink-600">{r.name}</div>
                  </div>
                  <span className="tnum text-[12px] text-ink-300">{nf(r.ltp)}</span>
                </button>
              ))}
            </div>
          </div>
        </div>

        {/* the book */}
        <div className="col-span-12 space-y-4 lg:col-span-9">
          <div className="glass p-4">
            <div className="mb-3 flex flex-wrap items-center gap-3">
              <div>
                <div className="text-lg font-bold text-ink-100">{sel?.symbol || "—"}</div>
                <div className="text-[11px] text-ink-500">{sel?.name}</div>
              </div>
              <div className="tnum text-2xl font-bold text-ink-100">{nf(depth?.ltp || sel?.ltp || 0)}</div>
              <span className={`chip ${pushed ? "bg-bull/15 text-bull" : "bg-surface/[0.1] text-ink-400"}`}
                title="The backend pushes the book when it changes — this screen does not poll for it">
                {pushed ? "● STREAMING" : "○ waiting for a push"}
              </span>
              <div className="ml-auto flex items-center gap-2">
                <span className="text-[11px] text-ink-500">Levels</span>
                {LEVELS.map((l) => (
                  <button key={l} onClick={() => setLevels(l)}
                    className={`rounded-lg px-2.5 py-1 text-xs font-semibold transition-all
                      ${levels === l ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white"
                                     : "text-ink-400 hover:text-ink-100"}`}>{l}</button>
                ))}
              </div>
            </div>

            {empty ? (
              <div className="rounded-xl border border-line/[0.1] bg-surface/[0.03] px-4 py-8 text-center">
                <div className="text-[13px] font-semibold text-ink-200">No book for {sel?.symbol}</div>
                <div className="mt-1 text-[12px] text-ink-500">
                  Depth comes from the market-data feed. If the market is closed, or the ITCH feed is off,
                  there is no book to show — open the market from Exchange Admin → Market Session.
                </div>
              </div>
            ) : (
              <>
                {/* The spread/microprice numbers need BOTH sides — bookStats returns null otherwise. A
                    one-sided book (bids-only or asks-only, common on a live venue) is valid and must
                    still render its ladder, so these stats are guarded rather than assumed. Reading
                    stats!.bestBid on a one-sided book is exactly what was crashing this page. */}
                {stats ? (
                  <>
                    <div className="grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-7">
                      <Stat k="Best bid" v={nf(stats.bestBid)} sub={`${nfInt(depth!.bids[0].quantity)} qty`} tone="bull" />
                      <Stat k="Best ask" v={nf(stats.bestAsk)} sub={`${nfInt(depth!.asks[0].quantity)} qty`} tone="bear" />
                      <Stat k="Spread" v={nf(stats.spread)} sub={`${stats.spreadBps.toFixed(1)} bps`} />
                      <Stat k="Mid" v={nf(stats.mid)} />
                      <Stat k="Microprice" v={nf(stats.microprice)}
                        sub={stats.microprice > stats.mid ? "leaning up" : stats.microprice < stats.mid ? "leaning down" : "at mid"}
                        tone="cyan" />
                      <Stat k="Bid depth" v={nfInt(stats.bidQty)} sub={`${stats.bidOrders} orders`} tone="bull" />
                      <Stat k="Ask depth" v={nfInt(stats.askQty)} sub={`${stats.askOrders} orders`} tone="bear" />
                    </div>

                    <div className="mt-4">
                      <ImbalanceBar imbalance={stats.imbalance} />
                    </div>

                    <div className="mt-4 grid grid-cols-12 gap-4">
                      <div className="col-span-12 xl:col-span-7">
                        <div className="panel-title mb-1.5">Depth curve — cumulative size at each price</div>
                        <DepthCurve depth={depth!} />
                        <div className="mt-1 flex justify-between text-[10px] text-ink-600">
                          <span>Bids · VWAP {nf(stats.bidVwap)}</span>
                          <span>Asks · VWAP {nf(stats.askVwap)}</span>
                        </div>
                      </div>
                      <div className="col-span-12 xl:col-span-5">
                        <div className="panel-title mb-1.5">Recent trades</div>
                        <div className="max-h-[200px] overflow-y-auto rounded-xl border border-line/[0.08]">
                          {tape.length === 0 && <div className="px-3 py-6 text-center text-[11px] text-ink-600">No prints yet</div>}
                          {tape.map((t, i) => (
                            <div key={i} className="flex items-center justify-between border-t border-line/[0.05] px-3 py-1 text-[11.5px] tnum first:border-0">
                              <span className="text-ink-600">{String(t.executedAt || "").slice(11, 19)}</span>
                              <span className={t.side === "SELL" ? "text-bear" : "text-bull"}>{nf(t.price)}</span>
                              <span className="text-ink-300">{nfInt(t.quantity)}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    </div>
                  </>
                ) : (
                  <div className="rounded-xl border border-amber-400/25 bg-amber-400/[0.06] px-4 py-3 text-[12px] text-ink-300">
                    <b className="text-ink-100">One-sided book</b> — only {depth!.bids?.length ? "bids" : "asks"} are
                    resting right now, so spread and microprice can't be computed. The ladder below shows the side
                    that has liquidity.
                  </div>
                )}

                <div className="mt-4">
                  <div className="panel-title mb-1.5">
                    Order book — {Math.max(depth!.bids?.length || 0, depth!.asks?.length || 0)} levels a side
                  </div>
                  <FullLadder
                    depth={depth!}
                    changed={changed}
                    // The ladder's price cells have always been buttons accepting onPickPrice; this
                    // page just never passed one, so they looked clickable and did nothing.
                    onPickPrice={(price) => sel && startOrder({ securityId: sel.securityId, price })}
                  />
                </div>
              </>
            )}
          </div>
        </div>
      </div>
    </Shell>
  );
}
