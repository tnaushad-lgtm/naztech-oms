"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { get } from "@/lib/api";

const MT: Record<string, string> = {
  A: "Logon", "0": "Heartbeat", "1": "Test Req", "2": "Resend Req", "3": "Reject", "4": "Seq Reset",
  "5": "Logout", D: "New Order", F: "Cancel", G: "Replace", "8": "Exec Report", "9": "Cancel Reject", j: "Biz Reject",
};
const SIDE: Record<string, string> = { "1": "BUY", "2": "SELL", "5": "SELL SHORT" };
const EXECTYPE: Record<string, string> = { "0": "New", "1": "Partial", "2": "Fill", "4": "Canceled", "8": "Rejected", "C": "Expired", "F": "Trade" };
const ORDSTATUS: Record<string, string> = { "0": "New", "1": "Partial", "2": "Filled", "4": "Canceled", "8": "Rejected", "C": "Expired" };
const GROUPS: Record<string, string[] | null> = { All: null, Orders: ["D", "F", "G"], Executions: ["8", "9"], Session: ["A", "0", "1", "2", "4", "5"] };

type Row = { t: string; dir: "IN" | "OUT"; type: string; seq: string; symbol: string; detail: string; clordid: string; raw: string };

function parseLine(line: string, sender: string): Row | null {
  const f: Record<string, string> = {};
  line.split("|").forEach((kv) => { const i = kv.indexOf("="); if (i > 0) f[kv.slice(0, i)] = kv.slice(i + 1); });
  if (!f["35"]) return null;
  const type = f["35"];
  const dir: "IN" | "OUT" = f["49"] === sender ? "OUT" : "IN";
  let detail = "";
  if (type === "D" || type === "F" || type === "G") {
    detail = `${SIDE[f["54"]] || ""} ${f["38"] || ""} ${f["55"] || ""}${f["44"] ? " @ " + f["44"] : ""}${f["236"] ? " @yld " + f["236"] : ""}`.trim();
  } else if (type === "8") {
    detail = `${EXECTYPE[f["150"]] || f["150"] || ""} · ${ORDSTATUS[f["39"]] || f["39"] || ""}${f["14"] ? " · cum " + f["14"] : ""}${f["6"] && +f["6"] > 0 ? " @ " + f["6"] : ""}`.trim();
  } else if (type === "9") {
    detail = "reject: " + (f["58"] || f["102"] || "");
  }
  return { t: (f["52"] || "").replace(/^\d{8}-/, ""), dir, type, seq: f["34"] || "", symbol: f["55"] || "", detail, clordid: f["11"] || f["41"] || "", raw: line };
}

export default function FixMonitor() {
  const [sender, setSender] = useState("");
  const [rows, setRows] = useState<Row[]>([]);
  const [group, setGroup] = useState("All");
  const [q, setQ] = useState("");
  const [paused, setPaused] = useState(false);
  const [note, setNote] = useState("");
  const pausedRef = useRef(paused); pausedRef.current = paused;

  useEffect(() => { get<any>("/api/admin/connectivity/status").then((s) => setSender(s?.fix?.senderCompId || "")).catch(() => {}); }, []);

  useEffect(() => {
    let stop = false;
    const load = async () => {
      if (pausedRef.current) return;
      try {
        const r = await get<{ messages: string }>("/api/admin/connectivity/logs");
        const msgs = r?.messages || "";
        if (msgs.startsWith("(")) { setNote(msgs); setRows([]); return; }
        setNote("");
        const parsed = msgs.split("\n").map((l) => parseLine(l.trim(), sender)).filter(Boolean) as Row[];
        if (!stop) setRows(parsed.reverse());   // newest first
      } catch { if (!stop) setNote("Could not read FIX logs."); }
    };
    load(); const t = setInterval(load, 2000);
    return () => { stop = true; clearInterval(t); };
  }, [sender]);

  const shown = useMemo(() => {
    const allow = GROUPS[group];
    const ql = q.trim().toLowerCase();
    return rows.filter((r) => (!allow || allow.includes(r.type)) &&
      (!ql || `${r.symbol} ${r.detail} ${r.clordid} ${MT[r.type] || r.type}`.toLowerCase().includes(ql)));
  }, [rows, group, q]);

  return (
    <Shell title="FIX Message Monitor" connected>
      <div className="flex h-full flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <div className="glass-soft flex rounded-xl p-0.5">
            {Object.keys(GROUPS).map((g) => (
              <button key={g} onClick={() => setGroup(g)}
                className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${
                  group === g ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>{g}</button>
            ))}
          </div>
          <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter symbol / clordid / text…"
            className="field max-w-xs py-1.5 text-xs" />
          <button onClick={() => setPaused((p) => !p)}
            className={`ghost-btn px-3 py-1.5 text-xs ${paused ? "text-amber-300" : ""}`}>{paused ? "▶ Resume" : "⏸ Pause"}</button>
          <div className="ml-auto text-[11px] text-ink-500">{shown.length} messages · session <span className="text-ink-300">{sender || "?"}</span> · {paused ? "paused" : "live (2s)"}</div>
        </div>

        <div className="glass min-h-0 flex-1 overflow-auto">
          {note ? (
            <div className="grid h-full place-items-center text-ink-500">{note}</div>
          ) : (
            <table className="w-full text-[12px]">
              <thead className="sticky top-0 bg-obsidian-850/95 text-[10px] uppercase tracking-wider text-ink-600">
                <tr className="text-left">
                  <th className="px-3 py-2">Time</th><th className="px-3 py-2">Dir</th><th className="px-3 py-2">Type</th>
                  <th className="px-3 py-2 text-right">Seq</th><th className="px-3 py-2">Symbol</th><th className="px-3 py-2">Detail</th><th className="px-3 py-2">ClOrdID</th>
                </tr>
              </thead>
              <tbody>
                {shown.map((r, i) => (
                  <tr key={i} className="border-t border-line/[0.08] hover:bg-surface/[0.05]" title={r.raw}>
                    <td className="px-3 py-1.5 tnum text-ink-500">{r.t}</td>
                    <td className="px-3 py-1.5">
                      <span className={`chip ${r.dir === "OUT" ? "bg-aurora-indigo/15 text-aurora-cyan" : "bg-bull/10 text-bull"}`}>{r.dir === "OUT" ? "▲ OUT" : "▼ IN"}</span>
                    </td>
                    <td className="px-3 py-1.5 font-semibold text-ink-100">{MT[r.type] || r.type}</td>
                    <td className="px-3 py-1.5 text-right tnum text-ink-400">{r.seq}</td>
                    <td className="px-3 py-1.5 text-ink-200">{r.symbol}</td>
                    <td className="px-3 py-1.5 text-ink-300">{r.detail}</td>
                    <td className="px-3 py-1.5 tnum text-[11px] text-ink-500">{r.clordid}</td>
                  </tr>
                ))}
                {shown.length === 0 && <tr><td colSpan={7} className="px-3 py-8 text-center text-ink-600">No FIX messages yet — place an order in FIX mode to see traffic.</td></tr>}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </Shell>
  );
}
