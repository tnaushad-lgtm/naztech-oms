"use client";

import { useDepth, type Level } from "@/lib/useDepth";
import { nf, nfInt } from "@/lib/format";

/**
 * The depth ladder. The book is pushed from the backend (see {@link useDepth}) rather than polled,
 * and any level whose size changed is flashed — on a live book the numbers move faster than anyone
 * can watch them, so the ladder has to point at what moved rather than leave the dealer to notice.
 */
export function DepthLadder({ securityId, onPickPrice }: { securityId: number; onPickPrice: (p: number) => void }) {
  const { depth, changed, pushed } = useDepth(securityId, 6);

  const max = Math.max(
    1,
    ...(depth?.bids || []).map((b) => b.quantity),
    ...(depth?.asks || []).map((a) => a.quantity)
  );

  const Row = ({ lvl, side }: { lvl: Level; side: "bid" | "ask" }) => {
    const lit = changed.has(`${side === "bid" ? "b" : "a"}${lvl.price}`);
    return (
      <button onClick={() => onPickPrice(lvl.price)}
        className={`relative grid grid-cols-3 items-center px-2 py-1 text-[12px] tnum transition-colors duration-200
          hover:bg-surface/[0.07] ${lit ? (side === "bid" ? "bg-bull/25" : "bg-bear/25") : ""}`}>
        <div className={`absolute inset-y-0 ${side === "bid" ? "right-0 bg-bull/10" : "left-0 bg-bear/10"}`}
          style={{ width: `${(lvl.quantity / max) * 100}%` }} />
        {side === "bid" ? (
          <>
            <span className="relative z-10 text-left text-ink-500">{lvl.orders}</span>
            <span className="relative z-10 text-right text-ink-300">{nfInt(lvl.quantity)}</span>
            <span className="relative z-10 text-right font-semibold text-bull">{nf(lvl.price)}</span>
          </>
        ) : (
          <>
            <span className="relative z-10 text-left font-semibold text-bear">{nf(lvl.price)}</span>
            <span className="relative z-10 text-left text-ink-300">{nfInt(lvl.quantity)}</span>
            <span className="relative z-10 text-right text-ink-500">{lvl.orders}</span>
          </>
        )}
      </button>
    );
  };

  return (
    <div className="glass p-3">
      <div className="mb-2 flex items-center justify-between">
        <div className="flex items-center gap-1.5">
          <div className="panel-title">Market Depth</div>
          <span title={pushed ? "Streaming — the exchange pushes each change" : "Waiting for the first push"}
            className={`h-1.5 w-1.5 rounded-full ${pushed ? "bg-bull animate-pulseDot" : "bg-ink-600"}`} />
        </div>
        <div className="text-[11px] text-ink-500">LTP <span className="tnum text-ink-200">{nf(depth?.ltp || 0)}</span></div>
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div>
          <div className="grid grid-cols-3 px-2 pb-1 text-[10px] uppercase tracking-wider text-ink-600">
            <span>Ord</span><span className="text-right">Qty</span><span className="text-right">Bid</span>
          </div>
          <div className="flex flex-col">
            {(depth?.bids || []).map((b, i) => <Row key={i} lvl={b} side="bid" />)}
            {!depth?.bids?.length && <div className="px-2 py-3 text-center text-[11px] text-ink-600">No bids</div>}
          </div>
        </div>
        <div>
          <div className="grid grid-cols-3 px-2 pb-1 text-[10px] uppercase tracking-wider text-ink-600">
            <span>Ask</span><span>Qty</span><span className="text-right">Ord</span>
          </div>
          <div className="flex flex-col">
            {(depth?.asks || []).map((a, i) => <Row key={i} lvl={a} side="ask" />)}
            {!depth?.asks?.length && <div className="px-2 py-3 text-center text-[11px] text-ink-600">No asks</div>}
          </div>
        </div>
      </div>
    </div>
  );
}
