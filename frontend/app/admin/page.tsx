"use client";

import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { get, post } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { compact, timeOf } from "@/lib/format";

export default function AdminPage() {
  const [ov, setOv] = useState<any>({});
  const [brokers, setBrokers] = useState<any[]>([]);
  const [users, setUsers] = useState<any[]>([]);
  const [audit, setAudit] = useState<any[]>([]);
  const [form, setForm] = useState({ exchangeId: 1, trecCode: "", name: "", firmLimit: 100000000 });
  const { connected } = useLive(() => {});

  const loadAll = async () => {
    try { setOv(await get("/api/admin/overview")); } catch {}
    try { setBrokers(await get("/api/admin/brokers")); } catch {}
    try { setUsers(await get("/api/admin/users")); } catch {}
    try { setAudit(await get("/api/admin/audit")); } catch {}
  };
  useEffect(() => { loadAll(); const t = setInterval(() => get("/api/admin/overview").then(setOv).catch(() => {}), 4000); return () => clearInterval(t); }, []);

  const onboard = async () => {
    if (!form.trecCode || !form.name) return;
    try { await post("/api/admin/brokers", form); setForm({ ...form, trecCode: "", name: "" }); loadAll(); } catch {}
  };

  const kpis = [
    { label: "TREC Brokers", value: ov.brokers, icon: "🏛️" },
    { label: "Users", value: ov.users, icon: "👥" },
    { label: "Securities", value: ov.securities, icon: "📈" },
    { label: "Orders", value: ov.orders, icon: "🧾" },
    { label: "Trades", value: ov.trades, icon: "⚡" },
    { label: "Live Clients", value: ov.sseClients, icon: "🛰️" },
  ];

  return (
    <Shell title="Exchange Control Plane" connected={connected}>
      {/* KPIs */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {kpis.map((k) => (
          <div key={k.label} className="glass p-4">
            <div className="text-2xl">{k.icon}</div>
            <div className="mt-1 tnum text-2xl font-bold text-ink-100">{k.value ?? "—"}</div>
            <div className="text-[11px] uppercase tracking-wider text-ink-500">{k.label}</div>
          </div>
        ))}
      </div>

      <div className="mt-4 grid grid-cols-12 gap-4">
        {/* AI status */}
        <div className="col-span-12 lg:col-span-4">
          <div className="glass p-4">
            <div className="panel-title">On-Prem AI Engine</div>
            <div className="mt-3 flex items-center gap-3">
              <span className={`h-3 w-3 rounded-full ${ov.aiReady ? "bg-bull animate-pulseDot" : "bg-amber-400"}`} />
              <div>
                <div className="text-sm font-semibold text-ink-100">all-MiniLM-L6-v2</div>
                <div className="text-[11px] text-ink-500">in-process · {ov.aiIndexed ?? 0} vectors indexed</div>
              </div>
            </div>
            <div className="mt-3 text-[12px] text-ink-400">
              Semantic security search & pre-trade risk scoring run inside the JVM. No order or ticker
              data leaves the exchange — a compliance-grade posture for capital-market infrastructure.
            </div>
          </div>

          {/* onboard broker */}
          <div className="glass mt-4 p-4">
            <div className="panel-title mb-2">Onboard TREC Holder</div>
            <div className="space-y-2">
              <input className="field" placeholder="TREC code (e.g. TREC-110)"
                value={form.trecCode} onChange={(e) => setForm({ ...form, trecCode: e.target.value })} />
              <input className="field" placeholder="Brokerage firm name"
                value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
              <div className="flex gap-2">
                <select className="field" value={form.exchangeId} onChange={(e) => setForm({ ...form, exchangeId: parseInt(e.target.value) })}>
                  <option value={1} className="bg-obsidian-850">DSE</option>
                  <option value={2} className="bg-obsidian-850">CSE</option>
                </select>
                <input type="number" className="field" value={form.firmLimit}
                  onChange={(e) => setForm({ ...form, firmLimit: parseInt(e.target.value) || 0 })} />
              </div>
              <button onClick={onboard} className="aurora-btn w-full">Onboard broker</button>
            </div>
          </div>
        </div>

        {/* brokers + users */}
        <div className="col-span-12 lg:col-span-8 space-y-4">
          <div className="glass overflow-hidden">
            <div className="px-4 py-3 panel-title">TREC Holders (Brokers)</div>
            <table className="w-full text-[12px]">
              <thead className="bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
                <tr className="text-left"><th className="px-4 py-2">Code</th><th className="px-3 py-2">Firm</th><th className="px-3 py-2 text-right">Firm Limit</th><th className="px-3 py-2">Status</th></tr>
              </thead>
              <tbody>
                {brokers.map((b) => (
                  <tr key={b.id} className="border-t border-line/[0.1]">
                    <td className="px-4 py-2 font-semibold text-ink-100">{b.trecCode}</td>
                    <td className="px-3 py-2 text-ink-300">{b.name}</td>
                    <td className="px-3 py-2 text-right tnum text-ink-300">৳{compact(b.firmLimit)}</td>
                    <td className="px-3 py-2"><span className="chip bg-bull/15 text-bull">{b.status}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="grid grid-cols-1 gap-4 xl:grid-cols-2">
            <div className="glass overflow-hidden">
              <div className="px-4 py-3 panel-title">User Hierarchy</div>
              <div className="max-h-[280px] overflow-auto">
                {users.map((u) => (
                  <div key={u.id} className="flex items-center justify-between border-t border-line/[0.1] px-4 py-2 text-[12px]">
                    <div><span className="font-semibold text-ink-100">{u.displayName}</span> <span className="text-ink-600">@{u.username}</span></div>
                    <span className="chip bg-surface/[0.1] text-ink-400">{u.role?.replace(/_/g, " ")}</span>
                  </div>
                ))}
              </div>
            </div>
            <div className="glass overflow-hidden">
              <div className="px-4 py-3 panel-title">Audit Trail</div>
              <div className="max-h-[280px] overflow-auto">
                {audit.map((a) => (
                  <div key={a.id} className="border-t border-line/[0.1] px-4 py-2 text-[12px]">
                    <div className="flex items-center justify-between">
                      <span className="font-medium text-ink-200">{a.action}</span>
                      <span className="text-[10px] text-ink-600">{timeOf(a.createdAt)}</span>
                    </div>
                    <div className="text-[11px] text-ink-500">{a.actor}{a.detail ? ` · ${a.detail}` : ""}</div>
                  </div>
                ))}
                {audit.length === 0 && <div className="px-4 py-6 text-center text-[12px] text-ink-600">No activity yet.</div>}
              </div>
            </div>
          </div>
        </div>
      </div>
    </Shell>
  );
}
