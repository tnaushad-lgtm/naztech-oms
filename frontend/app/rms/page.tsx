"use client";

import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { get, put, post } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { nf, compact, timeOf, statusColor } from "@/lib/format";

export default function RmsPage() {
  const [limits, setLimits] = useState<any[]>([]);
  const [brokers, setBrokers] = useState<any[]>([]);
  const [alerts, setAlerts] = useState<any[]>([]);
  const [draft, setDraft] = useState<Record<number, any>>({});
  const [saved, setSaved] = useState<number | null>(null);
  const { connected } = useLive((type) => { if (type === "order") loadAlerts(); });

  const loadLimits = async () => { try { setLimits(await get("/api/rms/limits")); } catch {} };
  const loadBrokers = async () => { try { setBrokers(await get("/api/rms/brokers")); } catch {} };
  const loadAlerts = async () => { try { setAlerts(await get("/api/rms/alerts?minScore=35")); } catch {} };

  useEffect(() => { loadLimits(); loadBrokers(); loadAlerts(); const t = setInterval(() => { loadAlerts(); loadBrokers(); }, 4000); return () => clearInterval(t); }, []);

  const scopeColor: Record<string, string> = {
    BROKER: "bg-aurora-violet/15 text-aurora-violet", TRADER: "bg-aurora-cyan/15 text-aurora-cyan", CLIENT: "bg-aurora-teal/15 text-aurora-teal",
  };

  const field = (l: any, k: string) => (draft[l.id]?.[k] ?? l[k]);
  const setField = (id: number, k: string, v: any) => setDraft((d) => ({ ...d, [id]: { ...d[id], [k]: v } }));
  const saveLimit = async (l: any) => {
    const body = { ...l, ...draft[l.id] };
    try { await put(`/api/rms/limits/${l.id}`, body); setSaved(l.id); setTimeout(() => setSaved(null), 1500); setDraft((d) => { const n = { ...d }; delete n[l.id]; return n; }); loadLimits(); } catch {}
  };

  const toggleHalt = async (b: any) => {
    const halt = b.status === "ACTIVE";
    try { await post(`/api/rms/broker/${b.id}/halt?halt=${halt}`, {}); loadBrokers(); } catch {}
  };

  return (
    <Shell title="Risk Management (RMS)" connected={connected}>
      {/* kill-switch */}
      <div className="glass mb-4 p-4">
        <div className="mb-3 flex items-center gap-2">
          <span className="text-bear">⛔</span>
          <div className="panel-title">Broker Kill-Switch</div>
          <span className="text-[11px] text-ink-500">— instantly halt or resume all new orders for a broker</span>
        </div>
        <div className="grid grid-cols-1 gap-2 md:grid-cols-2 lg:grid-cols-3">
          {brokers.map((b) => {
            const active = b.status === "ACTIVE";
            return (
              <div key={b.id} className={`flex items-center justify-between rounded-xl border p-3 ${active ? "border-line/[0.1] bg-surface/[0.05]" : "border-bear/40 bg-bear/10"}`}>
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold text-ink-100">{b.name}</div>
                  <div className="text-[11px] text-ink-500">{b.trecCode} · <span className={active ? "text-bull" : "text-bear"}>{b.status}</span></div>
                </div>
                <button onClick={() => toggleHalt(b)}
                  className={`rounded-lg px-3 py-1.5 text-xs font-bold transition-all ${active ? "bg-bear/20 text-bear hover:bg-bear/30" : "bg-bull/20 text-bull hover:bg-bull/30"}`}>
                  {active ? "HALT" : "RESUME"}
                </button>
              </div>
            );
          })}
        </div>
      </div>

      <div className="grid grid-cols-12 gap-4">
        {/* editable limits */}
        <div className="col-span-12 lg:col-span-7">
          <div className="glass overflow-hidden">
            <div className="px-4 py-3 panel-title">Pre-Trade Risk Limits — editable, applied live at order entry</div>
            <div className="overflow-auto">
              <table className="w-full text-[12px]">
                <thead className="bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
                  <tr className="text-left"><th className="px-3 py-2">Scope</th><th className="px-2 py-2 text-right">Max Order ৳</th><th className="px-2 py-2 text-right">Max Qty</th><th className="px-2 py-2 text-right">MTM Stop ৳</th><th className="px-2 py-2 text-center">Wash</th><th className="px-2 py-2 text-center">On</th><th className="px-2 py-2"></th></tr>
                </thead>
                <tbody>
                  {limits.map((l) => (
                    <tr key={l.id} className="border-t border-line/[0.1]">
                      <td className="px-3 py-2"><span className={`chip ${scopeColor[l.scope] || "bg-surface/[0.1]"}`}>{l.scope}</span> <span className="text-ink-600">#{l.entityId}</span></td>
                      <td className="px-2 py-1.5"><input type="number" className="field w-28 py-1 text-right tnum" value={field(l, "maxOrderValue")} onChange={(e) => setField(l.id, "maxOrderValue", parseFloat(e.target.value) || 0)} /></td>
                      <td className="px-2 py-1.5"><input type="number" className="field w-24 py-1 text-right tnum" value={field(l, "maxOrderQty")} onChange={(e) => setField(l.id, "maxOrderQty", parseInt(e.target.value) || 0)} /></td>
                      <td className="px-2 py-1.5"><input type="number" className="field w-28 py-1 text-right tnum" value={field(l, "mtmLossLimit")} onChange={(e) => setField(l.id, "mtmLossLimit", parseFloat(e.target.value) || 0)} /></td>
                      <td className="px-2 py-2 text-center"><input type="checkbox" checked={!!field(l, "washSaleBlock")} onChange={(e) => setField(l.id, "washSaleBlock", e.target.checked)} /></td>
                      <td className="px-2 py-2 text-center"><input type="checkbox" checked={!!field(l, "enabled")} onChange={(e) => setField(l.id, "enabled", e.target.checked)} /></td>
                      <td className="px-2 py-1.5 text-right">
                        <button onClick={() => saveLimit(l)} disabled={!draft[l.id]}
                          className={`rounded-md px-2.5 py-1 text-[11px] font-semibold ${saved === l.id ? "bg-bull/20 text-bull" : draft[l.id] ? "bg-aurora-indigo/20 text-aurora-cyan hover:bg-aurora-indigo/30" : "bg-surface/[0.07] text-ink-600"}`}>
                          {saved === l.id ? "Saved ✓" : "Save"}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
          <div className="glass mt-4 p-4">
            <div className="panel-title mb-2">Controls enforced at order entry</div>
            <div className="grid grid-cols-2 gap-2 text-[12px] text-ink-300 sm:grid-cols-3">
              {["Broker / Firm limit", "Trader / Dealer limit", "Client limit", "Buying-power check",
                "Holdings (short-sell) check", "Wash-sale block", "Lot-size validation", "Stop-loss arming",
                "AI fat-finger detection", "Broker kill-switch"].map((c) => (
                <div key={c} className="glass-soft px-3 py-2">✓ {c}</div>
              ))}
            </div>
          </div>
        </div>

        {/* alerts */}
        <div className="col-span-12 lg:col-span-5">
          <div className="glass flex h-[640px] flex-col overflow-hidden">
            <div className="flex items-center justify-between px-4 py-3">
              <div className="panel-title">AI Risk Alert Feed</div>
              <span className="chip bg-bear/15 text-bear animate-pulseDot">LIVE</span>
            </div>
            <div className="flex-1 overflow-auto px-3 pb-3">
              {alerts.map((o) => (
                <div key={o.id} className="mb-2 rounded-xl border border-line/[0.1] bg-obsidian-900/50 p-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2">
                      <span className={`font-bold ${o.side === "BUY" ? "text-bull" : "text-bear"}`}>{o.side}</span>
                      <span className="font-semibold text-ink-100">{o.symbol}</span>
                      <span className="text-[11px] text-ink-500">{o.quantity?.toLocaleString()} @ {o.orderType === "MARKET" ? "MKT" : nf(o.price)}</span>
                    </div>
                    <span className={`chip ${statusColor(o.status)}`}>{o.status}</span>
                  </div>
                  <div className="mt-1.5 flex items-center justify-between">
                    <span className="text-[11px] text-ink-500">{timeOf(o.createdAt)}</span>
                    <div className="flex items-center gap-2">
                      <div className="h-1.5 w-24 overflow-hidden rounded-full bg-surface/[0.14]">
                        <div className="h-full rounded-full" style={{ width: `${Math.min(100, o.riskScore)}%`, background: o.riskScore >= 60 ? "#fb5b6b" : o.riskScore >= 35 ? "#fbbf24" : "#22c55e" }} />
                      </div>
                      <span className="tnum text-[11px] font-semibold text-ink-300">{Math.round(o.riskScore)}</span>
                    </div>
                  </div>
                  {o.rejectReason && <div className="mt-1 text-[11px] text-bear">{o.rejectReason}</div>}
                </div>
              ))}
              {alerts.length === 0 && <div className="px-2 py-10 text-center text-[12px] text-ink-600">No elevated-risk orders. All clear.</div>}
            </div>
          </div>
        </div>
      </div>
    </Shell>
  );
}
