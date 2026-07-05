"use client";

import { useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { get, put } from "@/lib/api";
import { nf, timeOf, statusColor } from "@/lib/format";

const EVENT_META: Record<string, { c: string; label: string }> = {
  RISK_PASS: { c: "#22c55e", label: "Risk passed" }, RISK_REJECT: { c: "#fb5b6b", label: "Risk rejected" },
  OPEN: { c: "#38bdf8", label: "Open on book" }, EXCH_OPEN: { c: "#38bdf8", label: "Accepted (New)" },
  PARTIAL: { c: "#fbbf24", label: "Partial fill" }, FILLED: { c: "#22c55e", label: "Filled" },
  ARMED: { c: "#a78bfa", label: "Stop armed" }, AMENDED: { c: "#a78bfa", label: "Amended" },
  CANCELLED: { c: "#94a3b8", label: "Cancelled" }, EXPIRED: { c: "#94a3b8", label: "Expired" },
  REJECTED: { c: "#fb5b6b", label: "Rejected" }, EXCH_FILL: { c: "#22c55e", label: "Fill (exchange)" },
};
const dotColor = (t: string) => EVENT_META[t]?.c || "#8b93b0";
const prettyEvent = (t: string) => EVENT_META[t]?.label || (t || "").replace(/_/g, " ");

export type Order = {
  id: number; orderRef: string; symbol: string; side: string; orderType: string;
  tradeWindow: string; validity: string; price: number; quantity: number; filledQty: number;
  avgFillPrice: number; status: string; rejectReason: string; riskScore: number; createdAt: string;
};

const WORKING = ["OPEN", "PARTIAL"];

export function Blotter({ orders, onCancel, onChanged }: { orders: Order[]; onCancel: (id: number) => void; onChanged?: () => void }) {
  const [edit, setEdit] = useState<Order | null>(null);
  const [price, setPrice] = useState(0);
  const [qty, setQty] = useState(0);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [hist, setHist] = useState<Order | null>(null);
  const [events, setEvents] = useState<any[]>([]);
  const [histBusy, setHistBusy] = useState(false);

  const openHistory = async (o: Order) => {
    setHist(o); setEvents([]); setHistBusy(true);
    try { setEvents(await get<any[]>(`/api/orders/${o.id}/events`)); } catch {} finally { setHistBusy(false); }
  };

  const openEdit = (o: Order) => { setEdit(o); setPrice(o.price); setQty(o.quantity); setErr(""); };
  const saveEdit = async () => {
    if (!edit) return;
    setBusy(true); setErr("");
    try {
      const r: any = await put(`/api/orders/${edit.id}/modify`, { price, quantity: qty });
      if (r.error) setErr(r.error);
      else { setEdit(null); onChanged?.(); }
    } catch (e: any) { setErr(e.message || "Amend failed"); }
    finally { setBusy(false); }
  };

  return (
    <div className="glass flex h-full flex-col overflow-hidden">
      <div className="flex items-center justify-between px-4 py-3">
        <div className="panel-title">Order Blotter</div>
        <div className="text-[11px] text-ink-500">{orders.length} orders</div>
      </div>
      <div className="flex-1 overflow-auto">
        <table className="w-full text-[12px]">
          <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
            <tr className="text-left">
              <th className="px-3 py-2 font-medium">Time</th>
              <th className="px-3 py-2 font-medium">Symbol</th>
              <th className="px-3 py-2 font-medium">Side</th>
              <th className="px-3 py-2 font-medium text-right">Qty</th>
              <th className="px-3 py-2 font-medium text-right">Price</th>
              <th className="px-3 py-2 font-medium text-right">Filled</th>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium text-right">Risk</th>
              <th className="px-3 py-2"></th>
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.id} className="border-t border-line/[0.1] hover:bg-surface/[0.05]">
                <td className="px-3 py-2 tnum text-ink-500">{timeOf(o.createdAt)}</td>
                <td className="px-3 py-2 font-semibold text-ink-100">{o.symbol}</td>
                <td className={`px-3 py-2 font-bold ${o.side === "BUY" ? "text-bull" : "text-bear"}`}>{o.side}</td>
                <td className="px-3 py-2 text-right tnum text-ink-200">{o.quantity?.toLocaleString()}</td>
                <td className="px-3 py-2 text-right tnum text-ink-200">{o.orderType === "MARKET" ? "MKT" : nf(o.price)}</td>
                <td className="px-3 py-2 text-right tnum text-ink-300">
                  {o.filledQty?.toLocaleString()}{o.avgFillPrice > 0 ? ` @${nf(o.avgFillPrice)}` : ""}
                </td>
                <td className="px-3 py-2">
                  <span className={`chip ${statusColor(o.status)}`} title={o.rejectReason || ""}>{o.status}</span>
                </td>
                <td className="px-3 py-2 text-right tnum text-ink-400">{Math.round(o.riskScore || 0)}</td>
                <td className="px-3 py-2 text-right whitespace-nowrap">
                  <button onClick={() => openHistory(o)} title="Order history"
                    className="mr-1 rounded-md px-2 py-0.5 text-[11px] text-ink-400 hover:bg-aurora-indigo/20 hover:text-aurora-cyan">🕑</button>
                  {WORKING.includes(o.status) && (
                    <button onClick={() => openEdit(o)} title="Amend price/qty"
                      className="mr-1 rounded-md px-2 py-0.5 text-[11px] text-ink-400 hover:bg-aurora-indigo/20 hover:text-aurora-cyan">✎</button>
                  )}
                  {["OPEN", "PARTIAL", "PENDING_RISK", "NEW"].includes(o.status) && (
                    <button onClick={() => onCancel(o.id)} title="Cancel"
                      className="rounded-md px-2 py-0.5 text-[11px] text-ink-400 hover:bg-bear/15 hover:text-bear">✕</button>
                  )}
                </td>
              </tr>
            ))}
            {orders.length === 0 && (
              <tr><td colSpan={9} className="px-3 py-8 text-center text-[12px] text-ink-600">No orders yet — place one from the ticket.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <AnimatePresence>
        {edit && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 grid place-items-center bg-obsidian-950/70 backdrop-blur-sm p-4"
            onClick={() => setEdit(null)}>
            <motion.div initial={{ scale: 0.96, y: 8 }} animate={{ scale: 1, y: 0 }} exit={{ scale: 0.96 }}
              className="glass w-full max-w-sm p-5" onClick={(e) => e.stopPropagation()}>
              <div className="flex items-center justify-between">
                <div className="panel-title">Amend Order</div>
                <span className={`chip ${edit.side === "BUY" ? "bg-bull/15 text-bull" : "bg-bear/15 text-bear"}`}>{edit.side} {edit.symbol}</span>
              </div>
              <div className="mt-1 text-[11px] text-ink-500">Ref {edit.orderRef} · filled {edit.filledQty}/{edit.quantity}</div>
              <div className="mt-4 grid grid-cols-2 gap-3">
                <div>
                  <label className="panel-title">New Price</label>
                  <input type="number" step="0.1" className="field mt-1" value={price}
                    onChange={(e) => setPrice(parseFloat(e.target.value) || 0)} />
                </div>
                <div>
                  <label className="panel-title">New Quantity</label>
                  <input type="number" className="field mt-1" value={qty}
                    onChange={(e) => setQty(parseInt(e.target.value) || 0)} />
                </div>
              </div>
              {err && <div className="mt-3 rounded-lg bg-bear/10 px-3 py-2 text-[12px] text-bear">{err}</div>}
              <div className="mt-4 flex gap-2">
                <button onClick={() => setEdit(null)} className="ghost-btn flex-1 justify-center">Cancel</button>
                <button onClick={saveEdit} disabled={busy} className="aurora-btn flex-1">{busy ? "Saving…" : "Amend order"}</button>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {hist && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 grid place-items-center bg-obsidian-950/70 backdrop-blur-sm p-4"
            onClick={() => setHist(null)}>
            <motion.div initial={{ scale: 0.96, y: 8 }} animate={{ scale: 1, y: 0 }} exit={{ scale: 0.96 }}
              className="glass w-full max-w-md p-5" onClick={(e) => e.stopPropagation()}>
              <div className="flex items-center justify-between">
                <div className="panel-title">Order History</div>
                <span className={`chip ${statusColor(hist.status)}`}>{hist.status}</span>
              </div>
              <div className="mt-1 text-[11px] text-ink-500">
                {hist.side} {hist.quantity?.toLocaleString()} {hist.symbol} @ {hist.orderType === "MARKET" ? "MKT" : nf(hist.price)} · ref {hist.orderRef}
              </div>
              <div className="mt-4 max-h-[52vh] overflow-auto pr-1">
                {histBusy && <div className="text-[12px] text-ink-500">Loading…</div>}
                {!histBusy && events.length === 0 && <div className="text-[12px] text-ink-500">No history events recorded.</div>}
                <ol className="relative ml-1 border-l border-line/[0.15] pl-4">
                  {events.map((e, i) => (
                    <li key={i} className="mb-3 last:mb-0">
                      <span className="absolute -left-[5px] mt-1 h-2.5 w-2.5 rounded-full ring-2 ring-obsidian-900"
                        style={{ background: dotColor(e.eventType) }} />
                      <div className="flex items-center justify-between gap-2">
                        <span className="text-[12px] font-semibold text-ink-100">{prettyEvent(e.eventType)}</span>
                        <span className="tnum text-[10px] text-ink-500">{timeOf(e.createdAt)}</span>
                      </div>
                      {e.detail && <div className="text-[11px] text-ink-400">{e.detail}</div>}
                    </li>
                  ))}
                </ol>
              </div>
              <button onClick={() => setHist(null)} className="ghost-btn mt-4 w-full justify-center">Close</button>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
