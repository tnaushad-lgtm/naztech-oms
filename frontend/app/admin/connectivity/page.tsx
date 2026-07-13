"use client";

import { useEffect, useState } from "react";
import { Shell } from "@/components/Shell";
import { get, post } from "@/lib/api";

const MODE_LABEL: Record<string, string> = {
  simulator: "Simulator (in-process)",
  "dse-cert": "DSE Certification (live FIX/ITCH)",
  "dse-prod": "DSE Production (live FIX/ITCH)",
};

function Row({ k, v }: { k: string; v: any }) {
  return (
    <div className="flex items-center justify-between border-b border-line/[0.06] py-1.5">
      <span className="text-[11px] uppercase tracking-wider text-ink-500">{k}</span>
      <span className="tnum text-[12.5px] font-medium text-ink-100">{v === null || v === undefined || v === "" ? "—" : String(v)}</span>
    </div>
  );
}

/** Seeded ids — the proprietary account has the deepest buying power, so it is the default. */
const LT_DEFAULTS = {
  targetPerSec: 100,
  durationSec: 10,
  accountId: 3,
  securityId: 7,
  dealerId: 4,
  quantity: 100,
  side: "BUY",
  strategy: "RESTING",
  primeAccount: true,
  threads: 0,
};

export default function Connectivity() {
  const [s, setS] = useState<any>(null);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState("");
  const [lt, setLt] = useState<any>(LT_DEFAULTS);
  const [ltRun, setLtRun] = useState<any>(null);

  const load = async () => { try { setS(await get("/api/admin/connectivity/status")); } catch {} };
  useEffect(() => { load(); const t = setInterval(load, 2000); return () => clearInterval(t); }, []);

  // While a run is in flight, poll fast enough to watch the rate settle.
  useEffect(() => {
    if (!ltRun?.running) return;
    const t = setInterval(async () => {
      try { setLtRun(await get("/api/admin/loadtest/status")); } catch {}
    }, 500);
    return () => clearInterval(t);
  }, [ltRun?.running]);

  const runLoadTest = async () => {
    setBusy("loadtest");
    try {
      const r: any = await post("/api/admin/loadtest/start", lt);
      setLtRun(r.status);
      setMsg(`Throughput test running: ${lt.targetPerSec}/sec for ${lt.durationSec}s`);
    } catch (e: any) { setMsg(e.message || "Could not start the throughput test"); }
    finally { setBusy(""); }
  };
  const stopLoadTest = async () => {
    try { const r: any = await post("/api/admin/loadtest/stop", {}); setLtRun(r.status); }
    catch (e: any) { setMsg(e.message || "Stop failed"); }
  };

  const fix = s?.fix || {};
  const itch = s?.itch || {};
  const connected = !!fix.loggedOn;

  const reconnect = async () => {
    setBusy("reconnect");
    try { const r: any = await post("/api/admin/connectivity/reconnect", {}); setMsg(r.message || "Reconnect requested"); }
    catch (e: any) { setMsg(e.message || "Reconnect failed"); }
    finally { setBusy(""); load(); }
  };
  const testOrder = async () => {
    setBusy("test");
    try {
      const r: any = await post("/api/admin/connectivity/test-order", {});
      setMsg(r.ok ? `Test order ${r.orderRef} on ${r.symbol} → ${r.status} (routed via ${r.routedVia})`
                  : `Test order blocked: ${r.message || "risk"}`);
    } catch (e: any) { setMsg(e.message || "Test order failed"); }
    finally { setBusy(""); load(); }
  };
  const downloadLogs = async () => {
    try {
      const r: any = await get("/api/admin/connectivity/logs");
      const blob = new Blob([`# FIX MESSAGES\n${r.messages}\n\n# EVENTS\n${r.events}`], { type: "text/plain" });
      const a = document.createElement("a"); a.href = URL.createObjectURL(blob);
      a.download = `fix-log-${new Date().toISOString().slice(0, 19).replace(/:/g, "")}.txt`; a.click();
    } catch (e: any) { setMsg(e.message || "Could not fetch logs"); }
  };

  return (
    <Shell title="Exchange Connectivity" connected={connected}>
      {/* status banner */}
      <div className="glass mb-4 flex flex-wrap items-center justify-between gap-3 p-4">
        <div className="flex items-center gap-3">
          <span className={`h-3 w-3 rounded-full ${connected ? "bg-bull animate-pulseDot" : "bg-bear"}`} />
          <div>
            <div className="text-lg font-bold text-ink-100">{connected ? "FIX Session Connected" : "FIX Session Disconnected"}</div>
            <div className="text-[12px] text-ink-500">
              Mode: <span className="text-aurora-cyan">{MODE_LABEL[s?.mode] || s?.mode || "…"}</span>
              {" · "}Market depth from <span className="text-aurora-cyan">{itch.depthSource || "…"}</span>
            </div>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <button onClick={reconnect} disabled={busy === "reconnect"} className="ghost-btn py-1.5 text-xs">↻ Reconnect</button>
          <button onClick={testOrder} disabled={busy === "test"} className="aurora-btn py-1.5 text-xs">⚡ Test Order</button>
          <button onClick={downloadLogs} className="ghost-btn py-1.5 text-xs">⤓ Download Logs</button>
        </div>
      </div>

      {msg && <div className="glass-soft mb-4 px-4 py-2 text-[12.5px] text-ink-200">{msg}</div>}

      <div className="grid grid-cols-12 gap-4">
        {/* FIX */}
        <div className="col-span-12 lg:col-span-7">
          <div className="glass p-4">
            <div className="mb-2 flex items-center gap-2">
              <div className="panel-title">FIX Order-Entry Session</div>
              <span className={`chip ${connected ? "bg-bull/15 text-bull" : "bg-bear/15 text-bear"}`}>
                {connected ? "LOGGED ON" : (fix.enabled ? "CONNECTING…" : "DISABLED")}
              </span>
            </div>
            <Row k="Session" v={fix.sessionId} />
            <Row k="Endpoint" v={fix.host ? `${fix.host}:${fix.port}` : "(not set)"} />
            <Row k="Sender → Target" v={`${fix.senderCompId || "?"} → ${fix.targetCompId || "?"}`} />
            <Row k="Protocol" v={`${fix.beginString || ""} / ${fix.applVerId || ""}`} />
            <Row k="Last event" v={fix.lastEvent} />
            <Row k="Last heartbeat" v={fix.lastHeartbeatAt ? new Date(fix.lastHeartbeatAt).toLocaleTimeString("en-GB") : "—"} />
            <Row k="Next seq (out / in)" v={`${fix.nextSenderSeq ?? "—"} / ${fix.nextTargetSeq ?? "—"}`} />
            <Row k="Last msg (out / in)" v={`${fix.lastOutMsgType ?? "—"} / ${fix.lastInMsgType ?? "—"}`} />
          </div>
        </div>

        {/* ITCH */}
        <div className="col-span-12 lg:col-span-5">
          <div className="glass p-4">
            <div className="mb-2 flex items-center gap-2">
              <div className="panel-title">ITCH Market-Data Feed</div>
              <span className={`chip ${itch.enabled ? "bg-bull/15 text-bull" : "bg-surface/[0.1] text-ink-400"}`}>
                {itch.enabled ? "LIVE" : "OFF"}
              </span>
            </div>
            <Row k="Transport" v={itch.transport} />
            <Row k="Depth source" v={itch.depthSource} />
            <Row k="Enabled" v={itch.enabled ? "yes" : "no"} />
          </div>

          <div className="glass mt-4 p-4">
            <div className="panel-title mb-2">Switching to live DSE</div>
            <p className="text-[12px] leading-relaxed text-ink-400">
              This screen is <b>config-only</b>. To point at real DSE, set <span className="text-aurora-cyan">exchange.mode</span>,
              the <span className="text-aurora-cyan">fix</span> host/port/CompIDs and the <span className="text-aurora-cyan">itch</span> feed —
              no code changes. The password stays in the gitignored secrets file.
            </p>
          </div>
        </div>

        {/* Throughput harness */}
        <div className="col-span-12">
          <div className="glass p-4">
            <div className="mb-3 flex items-center gap-2">
              <div className="panel-title">Throughput Test</div>
              {ltRun && (
                <span className={`chip ${ltRun.running ? "bg-aurora-cyan/15 text-aurora-cyan" : "bg-surface/[0.1] text-ink-400"}`}>
                  {ltRun.phase}
                </span>
              )}
              <span className="ml-auto text-[11px] text-ink-500">
                Orders go through the real path — risk, matching, persistence. Routed via {ltRun?.routedVia || (s?.mode === "simulator" ? "in-process simulator" : "FIX")}.
              </span>
            </div>

            <div className="grid grid-cols-2 gap-2 md:grid-cols-4 lg:grid-cols-8">
              <label className="block">
                <span className="mb-1 block text-[10px] uppercase tracking-wider text-ink-500">Orders / sec</span>
                <input type="number" className="field tnum" value={lt.targetPerSec}
                  onChange={(e) => setLt({ ...lt, targetPerSec: parseInt(e.target.value) || 1 })} />
              </label>
              <label className="block">
                <span className="mb-1 block text-[10px] uppercase tracking-wider text-ink-500">Duration (s)</span>
                <input type="number" className="field tnum" value={lt.durationSec}
                  onChange={(e) => setLt({ ...lt, durationSec: parseInt(e.target.value) || 1 })} />
              </label>
              <label className="block">
                <span className="mb-1 block text-[10px] uppercase tracking-wider text-ink-500">Quantity</span>
                <input type="number" className="field tnum" value={lt.quantity}
                  onChange={(e) => setLt({ ...lt, quantity: parseInt(e.target.value) || 1 })} />
              </label>
              <label className="block">
                <span className="mb-1 block text-[10px] uppercase tracking-wider text-ink-500">Side</span>
                <select className="field" value={lt.side} onChange={(e) => setLt({ ...lt, side: e.target.value })}>
                  <option value="BUY" className="bg-obsidian-850">BUY</option>
                  <option value="SELL" className="bg-obsidian-850">SELL</option>
                </select>
              </label>
              <label className="block col-span-2">
                <span className="mb-1 block text-[10px] uppercase tracking-wider text-ink-500">Strategy</span>
                <select className="field" value={lt.strategy} onChange={(e) => setLt({ ...lt, strategy: e.target.value })}>
                  <option value="RESTING" className="bg-obsidian-850">Resting — never fills (sustainable)</option>
                  <option value="CROSSING" className="bg-obsidian-850">Crossing limit — fills</option>
                  <option value="MARKET" className="bg-obsidian-850">Market — fills</option>
                </select>
              </label>
              <label className="block">
                <span className="mb-1 block text-[10px] uppercase tracking-wider text-ink-500">Threads</span>
                <input type="number" className="field tnum" value={lt.threads} placeholder="auto"
                  onChange={(e) => setLt({ ...lt, threads: parseInt(e.target.value) || 0 })} />
              </label>
              <div className="flex items-end gap-2">
                {ltRun?.running ? (
                  <button onClick={stopLoadTest} className="ghost-btn w-full">■ Stop</button>
                ) : (
                  <button onClick={runLoadTest} disabled={busy === "loadtest"} className="aurora-btn w-full">▶ Run</button>
                )}
              </div>
            </div>

            <label className="mt-2 flex items-center gap-2 text-[12px] text-ink-400">
              <input type="checkbox" checked={lt.primeAccount}
                onChange={(e) => setLt({ ...lt, primeAccount: e.target.checked })} />
              Prime buying power before the run — without it, fills drain the account and later orders are
              rejected on risk (cheap rejects would flatter the rate).
            </label>

            {ltRun && (
              <>
                <div className="mt-4 grid grid-cols-2 gap-3 md:grid-cols-4 lg:grid-cols-8">
                  <Stat k="Achieved / sec" v={ltRun.achievedPerSec} big
                    tone={ltRun.achievedPerSec >= ltRun.targetPerSec * 0.95 ? "bull" : "bear"} />
                  <Stat k="Target / sec" v={ltRun.targetPerSec} />
                  <Stat k="Submitted" v={ltRun.submitted} />
                  <Stat k="Accepted" v={ltRun.accepted} tone="bull" />
                  <Stat k="Risk-rejected" v={ltRun.rejected} tone={ltRun.rejected > 0 ? "bear" : undefined} />
                  <Stat k="Errors" v={ltRun.errors} tone={ltRun.errors > 0 ? "bear" : undefined} />
                  <Stat k="p50 latency" v={`${ltRun.p50Ms} ms`} />
                  <Stat k="p99 latency" v={`${ltRun.p99Ms} ms`} />
                </div>
                <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-[11px] text-ink-500">
                  <span>elapsed {ltRun.elapsedSec}s</span>
                  <span>p95 {ltRun.p95Ms} ms · max {ltRun.maxMs} ms</span>
                  <span>{ltRun.threads} submitter threads · DB pool {ltRun.dbPoolSize}</span>
                </div>
                {ltRun.note && <div className="mt-2 text-[11.5px] text-ink-400">{ltRun.note}</div>}
                {ltRun.rejectReasons && Object.keys(ltRun.rejectReasons).length > 0 && (
                  <div className="mt-2 text-[11.5px] text-bear">
                    Rejections: {Object.entries(ltRun.rejectReasons).map(([r, n]) => `${r} × ${n}`).join(" · ")}
                  </div>
                )}
                {ltRun.errorSamples?.length > 0 && (
                  <div className="mt-1 text-[11.5px] text-bear">Errors: {ltRun.errorSamples.join(" · ")}</div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </Shell>
  );
}

function Stat({ k, v, big, tone }: { k: string; v: any; big?: boolean; tone?: "bull" | "bear" }) {
  const color = tone === "bull" ? "text-bull" : tone === "bear" ? "text-bear" : "text-ink-100";
  return (
    <div className="glass-soft px-3 py-2">
      <div className="text-[10px] uppercase tracking-wider text-ink-500">{k}</div>
      <div className={`tnum font-semibold ${big ? "text-xl" : "text-base"} ${color}`}>{v ?? "—"}</div>
    </div>
  );
}
