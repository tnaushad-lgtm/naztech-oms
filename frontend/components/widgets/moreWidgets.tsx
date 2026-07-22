"use client";

/**
 * Second wave of dashboard widgets.
 *
 * The library exposed 21 widgets while the app had grown well past forty chartable, tabulatable
 * things — the operations readouts in particular (FIX/ITCH health, session phase, the kill switch)
 * existed only on their own admin pages, so a desk that wanted them in view had to keep a second tab
 * open. These are the ones that were genuinely self-contained: each fetches its own data and takes
 * no props, because a widget that needs its old page's filter bar is not a widget.
 *
 * Everything here polls on its own schedule rather than sharing the dashboard's data hook. That is
 * deliberate: these are operational readouts whose value is being current, and the poll intervals
 * differ by an order of magnitude (2s for a FIX session, 30s for a user roster).
 */

import { useEffect, useMemo, useState } from "react";
import { get, post } from "@/lib/api";
import { useLive } from "@/lib/useLive";
import { nf, money, timeOf, statusColor } from "@/lib/format";
import { useDash } from "@/lib/dashboardData";
import { OrderDepthPanel } from "@/components/grid/OrderDepthPanel";

/** Poll an endpoint on an interval, tolerating transient failures. */
function usePoll<T>(path: string | null, ms: number): T | null {
  const [data, setData] = useState<T | null>(null);
  useEffect(() => {
    if (!path) return;
    let alive = true;
    const tick = async () => {
      try { const d = await get<T>(path); if (alive) setData(d); } catch { /* next tick retries */ }
    };
    tick();
    const t = setInterval(tick, ms);
    return () => { alive = false; clearInterval(t); };
  }, [path, ms]);
  return data;
}

const Empty = ({ what }: { what: string }) => (
  <div className="flex h-full items-center justify-center text-[11px] text-ink-500">{what}</div>
);
const Row = ({ k, v, tone = "" }: { k: string; v: React.ReactNode; tone?: string }) => (
  <div className="flex items-baseline justify-between gap-3 border-b border-line/[0.08] py-1 last:border-0">
    <span className="text-[10px] uppercase tracking-wider text-ink-400">{k}</span>
    <span className={`truncate text-[11.5px] tabular-nums ${tone || "text-ink-100"}`}>{v}</span>
  </div>
);

/* ------------------------------------------------------------------ operations */

export function ExchangeKpis() {
  const d = usePoll<any>("/api/admin/overview", 5000);
  if (!d) return <Empty what="loading…" />;
  const tiles: [string, any][] = [
    ["Brokers", d.brokers], ["Users", d.users], ["Securities", d.securities],
    ["Orders", d.orders], ["Trades", d.trades], ["Live clients", d.liveClients ?? d.clients],
  ];
  return (
    <div className="grid h-full grid-cols-2 gap-2 overflow-auto">
      {tiles.map(([k, v]) => (
        <div key={k} className="rounded-lg border border-line/[0.1] bg-surface/[0.04] p-2">
          <div className="text-[9px] uppercase tracking-wider text-ink-400">{k}</div>
          <div className="tabular-nums text-[18px] font-semibold text-ink-100">{v ?? "—"}</div>
        </div>
      ))}
    </div>
  );
}

export function FixSessionDetail() {
  const s = usePoll<any>("/api/admin/connectivity/status", 3000);
  const f = s?.fix;
  if (!f) return <Empty what="no FIX session" />;
  const chip = f.loggedOn ? "text-bull" : f.enabled ? "text-amber-300" : "text-ink-400";
  return (
    <div className="h-full overflow-auto">
      <div className={`mb-1.5 text-[11px] font-semibold uppercase tracking-wider ${chip}`}>
        {f.loggedOn ? "● Logged on" : f.enabled ? "● Connecting" : "○ Disabled"}
      </div>
      <Row k="Session" v={f.sessionId || "—"} />
      <Row k="Host" v={`${f.host}:${f.port}`} />
      <Row k="Comp IDs" v={`${f.senderCompId} → ${f.targetCompId}`} />
      <Row k="Version" v={`${f.beginString || ""} ${f.applVerId || ""}`.trim() || "—"} />
      <Row k="Last event" v={f.lastEvent || "—"} />
      <Row k="Heartbeat" v={timeOf(f.lastHeartbeatAt) || "—"} />
      <Row k="Seq out / in" v={`${f.nextSenderSeq ?? "—"} / ${f.nextTargetSeq ?? "—"}`} />
      <Row k="Msg out / in" v={`${f.lastOutMsgType || "—"} / ${f.lastInMsgType || "—"}`} />
    </div>
  );
}

export function ItchFeedStatus() {
  const s = usePoll<any>("/api/admin/connectivity/status", 3000);
  const f = s?.itch?.feed;
  const i = s?.itch;
  if (!i) return <Empty what="no ITCH feed" />;
  return (
    <div className="h-full overflow-auto">
      <div className={`mb-1.5 text-[11px] font-semibold uppercase tracking-wider ${f?.live ? "text-bull" : "text-bear"}`}>
        {f?.live ? "● Live" : "○ Off"}
      </div>
      <Row k="Transport" v={i.transport || "—"} />
      <Row k="Depth source" v={i.depthSource || "—"} />
      <Row k="Session" v={f?.session || "—"} />
      <Row k="Sequence" v={f?.seq != null ? nf(f.seq, 0) : "—"} />
      <Row k="Delivered" v={f?.delivered != null ? nf(f.delivered, 0) : "—"} />
      <Row k="Gaps / recovered" v={`${f?.gapsDetected ?? 0} / ${f?.gapsRecovered ?? 0}`}
           tone={f?.gapsDetected ? "text-amber-300" : ""} />
      {/* `lost` is the number that matters: a gap that was never recovered means the book was rebuilt
          on incomplete data, which is a different class of problem from a gap that healed. */}
      <Row k="Lost" v={f?.lost ?? 0} tone={f?.lost ? "text-bear font-semibold" : "text-bull"} />
      <Row k="Idle" v={f?.idleMs != null ? `${nf(f.idleMs / 1000, 1)}s` : "—"}
           tone={f?.idleMs > 30000 ? "text-amber-300" : ""} />
    </div>
  );
}

export function AuditTrail() {
  const rows = usePoll<any[]>("/api/admin/audit", 8000);
  if (!rows?.length) return <Empty what="no audit entries" />;
  return (
    <div className="h-full space-y-1 overflow-auto pr-1">
      {rows.map((a, i) => (
        <div key={i} className="rounded border border-line/[0.08] bg-surface/[0.03] px-2 py-1">
          <div className="flex items-baseline justify-between gap-2">
            <span className="text-[11px] font-semibold text-aurora-cyan">{a.action}</span>
            <span className="shrink-0 text-[10px] tabular-nums text-ink-400">{timeOf(a.createdAt)}</span>
          </div>
          <div className="truncate text-[10.5px] text-ink-300">{a.detail}</div>
          <div className="text-[10px] text-ink-400">by {a.actor || "system"}</div>
        </div>
      ))}
    </div>
  );
}

export function TrecHolders() {
  const rows = usePoll<any[]>("/api/admin/brokers", 15000);
  if (!rows?.length) return <Empty what="no brokers" />;
  return (
    <div className="h-full overflow-auto">
      <table className="w-full border-collapse text-[11px]">
        <thead className="sticky top-0 bg-obsidian-850/95">
          <tr className="text-[9px] uppercase tracking-wider text-ink-400">
            <th className="px-1.5 py-1 text-left">TREC</th>
            <th className="px-1.5 py-1 text-left">Firm</th>
            <th className="px-1.5 py-1 text-right">Limit</th>
            <th className="px-1.5 py-1 text-left">Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((b) => (
            <tr key={b.id} className="border-t border-line/[0.08]">
              <td className="px-1.5 py-1 tabular-nums text-ink-300">{b.trecCode || b.code || "—"}</td>
              <td className="max-w-[140px] truncate px-1.5 py-1 text-ink-100">{b.name}</td>
              <td className="px-1.5 py-1 text-right tabular-nums text-ink-300">{b.firmLimit ? money(b.firmLimit) : "—"}</td>
              <td className={`px-1.5 py-1 ${b.status === "HALTED" ? "text-bear" : "text-bull"}`}>{b.status}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function AiEngineStatus() {
  const s = usePoll<any>("/api/ai/status", 10000);
  if (!s) return <Empty what="loading…" />;
  return (
    <div className="h-full overflow-auto">
      <div className={`mb-1.5 flex items-center gap-2 text-[11px] font-semibold uppercase tracking-wider ${s.ready ? "text-bull" : "text-amber-300"}`}>
        <span className={`h-2 w-2 rounded-full ${s.ready ? "bg-bull animate-pulseDot" : "bg-amber-300"}`} />
        {s.ready ? "Index ready" : "Warming up"}
      </div>
      <Row k="Model" v={s.model || "—"} />
      <Row k="Vectors indexed" v={s.indexed != null ? nf(s.indexed, 0) : "—"} />
      {/* The index is built once at start-up; anything added later is invisible until a reindex. */}
      <div className="mt-2 text-[10px] leading-snug text-ink-400">
        Runs in-process — no ticker or order text leaves the exchange.
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ orders & risk */

export function BrokerKillSwitch() {
  const rows = usePoll<any[]>("/api/rms/brokers", 4000);
  const [busy, setBusy] = useState<number | null>(null);
  const flip = async (b: any) => {
    setBusy(b.id);
    try { await post(`/api/rms/broker/${b.id}/halt?halt=${b.status !== "HALTED"}`, {}); } catch {}
    setBusy(null);
  };
  if (!rows?.length) return <Empty what="no brokers" />;
  return (
    <div className="h-full space-y-1.5 overflow-auto pr-1">
      {rows.map((b) => {
        const halted = b.status === "HALTED";
        return (
          <div key={b.id}
            className={`flex items-center gap-2 rounded-lg border px-2 py-1.5 ${
              halted ? "border-bear/40 bg-bear/[0.08]" : "border-line/[0.1] bg-surface/[0.04]"}`}>
            <span className="min-w-0 flex-1">
              <span className="block truncate text-[11.5px] font-medium text-ink-100">{b.name}</span>
              <span className="text-[10px] text-ink-400">{b.trecCode || b.code}</span>
            </span>
            <span className={`text-[10px] font-semibold ${halted ? "text-bear" : "text-bull"}`}>{b.status}</span>
            <button disabled={busy === b.id} onClick={() => flip(b)}
              className={`rounded border px-2 py-0.5 text-[10px] font-semibold disabled:opacity-40 ${
                halted ? "border-bull/40 text-bull hover:bg-bull/10" : "border-bear/40 text-bear hover:bg-bear/10"}`}>
              {halted ? "Resume" : "Halt"}
            </button>
          </div>
        );
      })}
    </div>
  );
}

export function RmsAlertFeed() {
  const rows = usePoll<any[]>("/api/rms/alerts?minScore=35", 5000);
  if (!rows?.length) return <Empty what="no elevated-risk orders" />;
  return (
    <div className="h-full space-y-1 overflow-auto pr-1">
      {rows.map((a: any) => (
        <div key={a.id} className="rounded border border-line/[0.08] bg-surface/[0.03] px-2 py-1.5">
          <div className="flex items-baseline gap-2">
            <span className={`text-[11px] font-bold ${a.side === "BUY" ? "text-bull" : "text-bear"}`}>{a.side}</span>
            <span className="text-[11.5px] font-medium text-ink-100">{a.symbol}</span>
            <span className="text-[10.5px] tabular-nums text-ink-300">{nf(a.quantity, 0)} @ {nf(a.price)}</span>
            <span className={`ml-auto text-[10px] ${statusColor ? statusColor(a.status) : "text-ink-400"}`}>{a.status}</span>
          </div>
          {/* The meter is the point: a score of 41 and a score of 95 are different conversations. */}
          <div className="mt-1 h-1 w-full overflow-hidden rounded bg-white/10">
            <div className={`h-full ${a.riskScore >= 70 ? "bg-bear" : a.riskScore >= 50 ? "bg-amber-400" : "bg-aurora-cyan"}`}
                 style={{ width: `${Math.min(100, a.riskScore || 0)}%` }} />
          </div>
          {a.rejectReason && <div className="mt-0.5 truncate text-[10px] text-bear">{a.rejectReason}</div>}
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ market */

export function TimeAndSales() {
  const [rows, setRows] = useState<any[]>([]);
  useEffect(() => {
    get<any[]>("/api/market/tape?limit=120").then((d) => setRows(d || [])).catch(() => {});
  }, []);
  useLive((type, data) => {
    if (type !== "trade" || !data) return;
    setRows((prev) => [data, ...prev].slice(0, 250));   // newest first, capped
  });
  if (!rows.length) return <Empty what="no prints yet" />;
  return (
    <div className="h-full overflow-auto">
      <table className="w-full border-collapse text-[11px]">
        <thead className="sticky top-0 bg-obsidian-850/95">
          <tr className="text-[9px] uppercase tracking-wider text-ink-400">
            <th className="px-1.5 py-1 text-left">Time</th>
            <th className="px-1.5 py-1 text-left">Symbol</th>
            <th className="px-1.5 py-1 text-right">Price</th>
            <th className="px-1.5 py-1 text-right">Qty</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t, i) => (
            <tr key={t.tradeRef || i} className="border-t border-line/[0.06]">
              <td className="px-1.5 py-0.5 tabular-nums text-ink-400">{timeOf(t.executedAt || t.time)}</td>
              <td className="px-1.5 py-0.5 text-ink-100">{t.symbol}</td>
              <td className={`px-1.5 py-0.5 text-right tabular-nums ${t.aggressorSide === "SELL" ? "text-bear" : "text-bull"}`}>{nf(t.price)}</td>
              <td className="px-1.5 py-0.5 text-right tabular-nums text-ink-300">{nf(t.quantity, 0)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------------ depth */

/**
 * Market depth as a dashboard widget.
 *
 * Jewel's point, and it is the right one for this market: a Bangladeshi desk reads the book
 * constantly, so it should be pinnable next to whatever else the trader watches rather than living
 * only on its own page. Reuses the same panel the order grid puts beside entry, so the ladder a
 * trader learns in one place behaves identically in the other.
 */
export function DepthWidget() {
  const { watch } = useDash();
  const equities = useMemo(() => watch.filter((r: any) => r.assetClass !== "INDEX"), [watch]);
  const [symbol, setSymbol] = useState("");
  useEffect(() => { if (!symbol && equities.length) setSymbol(equities[0].symbol); }, [equities, symbol]);
  const sec = equities.find((e: any) => e.symbol === symbol);

  return (
    <div className="flex h-full flex-col">
      <select className="field mb-1 max-w-[60%] py-1 text-[11px]" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
        {equities.map((e: any) => <option key={e.securityId} value={e.symbol} className="bg-obsidian-850">{e.symbol}</option>)}
      </select>
      <div className="min-h-0 flex-1">
        <OrderDepthPanel securityId={sec?.securityId ?? null} symbol={sec?.symbol} levels={8} compact />
      </div>
    </div>
  );
}
