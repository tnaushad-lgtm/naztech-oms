"use client";

import { useEffect, useMemo, useState } from "react";
import { Shell } from "@/components/Shell";
import { get, post, del } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { getSession } from "@/lib/session";
import { nf, timeOf } from "@/lib/format";

type Alert = {
  id: number; securityId: number; symbol: string; securityName: string; targetPrice: number;
  direction: string; status: string; note: string | null; ltp: number; ltpAtTrigger: number | null;
  createdAt: string; triggeredAt: string | null;
};

const statusChip = (s: string) =>
  s === "TRIGGERED" ? "bg-bull/15 text-bull" : s === "CANCELLED" ? "bg-surface/[0.1] text-ink-500" : "bg-aurora-indigo/15 text-aurora-cyan";

export default function AlertsPage() {
  const [accounts, setAccounts] = useState<any[]>([]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [secs, setSecs] = useState<{ securityId: number; symbol: string; ltp: number }[]>([]);
  const [symbol, setSymbol] = useState("");
  const [target, setTarget] = useState<number>(0);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const s = getSession();
    if (s?.brokerId) get<any[]>(`/api/accounts?brokerId=${s.brokerId}`).then((a) => {
      setAccounts(a); setAccountId(s.defaultAccountId || a[0]?.id || null);
    }).catch(() => {});
    get<any[]>("/api/market/watch?exchange=DSE").then((r) =>
      setSecs((r || []).filter((x: any) => x.assetClass !== "INDEX").map((x: any) => ({ securityId: x.securityId, symbol: x.symbol, ltp: x.ltp })))
    ).catch(() => {});
  }, []);

  const load = () => { if (accountId) get<Alert[]>(`/api/alerts?accountId=${accountId}`).then(setAlerts).catch(() => {}); };
  useEffect(() => { load(); const t = setInterval(load, 5000); return () => clearInterval(t); }, [accountId]);
  const { connected } = useLive((type) => { if (type === "alert") setTimeout(load, 400); });

  const picked = useMemo(() => secs.find((s) => s.symbol.toUpperCase() === symbol.trim().toUpperCase()), [secs, symbol]);
  useEffect(() => { if (picked) setTarget((t) => (t > 0 ? t : +picked.ltp)); }, [picked?.securityId]);

  const quick = (pctOff: number) => { if (picked) setTarget(+(picked.ltp * (1 + pctOff / 100)).toFixed(2)); };

  const setAlert = async () => {
    if (!picked || !accountId || !target) return;
    setBusy(true);
    try {
      await post("/api/alerts", { accountId, securityId: picked.securityId, targetPrice: target, direction: null, note: null });
      setSymbol(""); setTarget(0); load();
    } catch {} finally { setBusy(false); }
  };
  const cancel = async (id: number) => { try { await del(`/api/alerts/${id}`); load(); } catch {} };

  return (
    <Shell title="Price Alerts" connected={connected}>
      <div className="mx-auto flex h-full w-full max-w-3xl flex-col gap-4">
        <div className="glass p-4">
          <div className="mb-3 flex items-center justify-between">
            <div className="panel-title">Set a price alert</div>
            <select className="field max-w-[220px] py-1 text-xs" value={accountId ?? ""} onChange={(e) => setAccountId(parseInt(e.target.value))}>
              {accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name} · {a.boId}</option>)}
            </select>
          </div>
          <div className="grid grid-cols-[1.3fr_1fr_auto] items-end gap-2">
            <div>
              <label className="panel-title">Security</label>
              <input list="alert-secs" value={symbol} onChange={(e) => setSymbol(e.target.value)} placeholder="e.g. GP"
                className="field mt-1 uppercase" />
              <datalist id="alert-secs">{secs.slice(0, 500).map((s) => <option key={s.securityId} value={s.symbol} />)}</datalist>
            </div>
            <div>
              <label className="panel-title">Target price {picked && <span className="text-ink-500">· LTP {nf(picked.ltp)}</span>}</label>
              <input type="number" step="0.1" value={target || ""} onChange={(e) => setTarget(parseFloat(e.target.value) || 0)} className="field mt-1" />
            </div>
            <button onClick={setAlert} disabled={busy || !picked || !target} className="aurora-btn h-10 px-5 disabled:opacity-40">Set alert</button>
          </div>
          {picked && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {[-20, -10, -5, 5, 10, 20].map((p) => (
                <button key={p} onClick={() => quick(p)} className="chip bg-surface/[0.06] text-ink-400 hover:text-ink-100">
                  {p > 0 ? "+" : ""}{p}% → {nf(picked.ltp * (1 + p / 100))}
                </button>
              ))}
            </div>
          )}
          <div className="mt-2 text-[11px] text-ink-600">Direction is auto (above/below the current price). You'll get a 🔔 notification anywhere in the app when it triggers.</div>
        </div>

        <div className="glass min-h-0 flex-1 overflow-auto">
          <table className="w-full text-[12px]">
            <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
              <tr className="text-left">
                <th className="px-3 py-2">Security</th><th className="px-3 py-2">Condition</th><th className="px-3 py-2 text-right">Target</th>
                <th className="px-3 py-2 text-right">LTP</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">When</th><th className="px-3 py-2"></th>
              </tr>
            </thead>
            <tbody>
              {alerts.map((a) => (
                <tr key={a.id} className="border-t border-line/[0.08] hover:bg-surface/[0.05]">
                  <td className="px-3 py-2"><span className="font-semibold text-ink-100">{a.symbol}</span></td>
                  <td className="px-3 py-2 text-ink-400">{a.direction === "ABOVE" ? "≥ rises to" : "≤ falls to"}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-200">{nf(a.targetPrice)}</td>
                  <td className="px-3 py-2 text-right tnum text-ink-300">{nf(a.status === "TRIGGERED" && a.ltpAtTrigger ? a.ltpAtTrigger : a.ltp)}</td>
                  <td className="px-3 py-2"><span className={`chip ${statusChip(a.status)}`}>{a.status}</span></td>
                  <td className="px-3 py-2 tnum text-[11px] text-ink-500">{a.triggeredAt ? "fired " + timeOf(a.triggeredAt) : timeOf(a.createdAt)}</td>
                  <td className="px-3 py-2 text-right">
                    {a.status === "ACTIVE" && <button onClick={() => cancel(a.id)} className="rounded-md px-2 py-0.5 text-[11px] text-ink-400 hover:bg-bear/15 hover:text-bear">✕</button>}
                  </td>
                </tr>
              ))}
              {alerts.length === 0 && <tr><td colSpan={7} className="px-3 py-8 text-center text-ink-600">No alerts yet — set one above.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
    </Shell>
  );
}
