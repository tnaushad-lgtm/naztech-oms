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

export default function Connectivity() {
  const [s, setS] = useState<any>(null);
  const [msg, setMsg] = useState("");
  const [busy, setBusy] = useState("");

  const load = async () => { try { setS(await get("/api/admin/connectivity/status")); } catch {} };
  useEffect(() => { load(); const t = setInterval(load, 2000); return () => clearInterval(t); }, []);

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
      </div>
    </Shell>
  );
}
