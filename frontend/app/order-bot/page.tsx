"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { Shell } from "@/components/Shell";
import { useLive } from "@/lib/useLive";
import { get, post } from "@/lib/api";
import { getSession } from "@/lib/session";
import { nf } from "@/lib/format";

type Line = {
  raw: string; ok: boolean; side?: "BUY" | "SELL"; symbol?: string; securityId?: number;
  qty?: number; price?: number | null; market?: boolean; error?: string;
};

/** Strict grammar:  B|S  <qty>  <TICKER>  @  [price]   (blank price after @ = market order). */
function validate(raw: string, secMap: Map<string, number>): Line {
  const line = raw.trim();
  const parts = line.split(/\s+/);
  const sideTok = (parts[0] || "").toUpperCase();
  if (sideTok !== "B" && sideTok !== "S") return { raw, ok: false, error: "Must start with B (buy) or S (sell)" };
  const side = sideTok === "S" ? "SELL" : "BUY";
  if (parts.length < 2) return { raw, ok: false, side, error: `Need a quantity after ${sideTok}` };
  if (!/^\d+$/.test(parts[1])) return { raw, ok: false, side, error: "Quantity must be digits only" };
  const qty = parseInt(parts[1], 10);
  if (qty <= 0) return { raw, ok: false, side, error: "Quantity must be greater than 0" };
  if (parts.length < 3) return { raw, ok: false, side, qty, error: "Need a ticker after the quantity" };
  const sym = parts[2].toUpperCase();
  if (!secMap.has(sym)) return { raw, ok: false, side, qty, error: `Unknown ticker "${parts[2]}"` };
  const rest = parts.slice(3).join("");
  if (!rest.startsWith("@")) return { raw, ok: false, side, qty, symbol: sym, error: "Missing '@' before the price" };
  const priceStr = rest.slice(1).trim();
  const market = priceStr === "";
  if (!market && !/^\d+(\.\d+)?$/.test(priceStr)) return { raw, ok: false, side, qty, symbol: sym, error: "Price must be a number (or leave blank for @ market)" };
  const price = market ? null : parseFloat(priceStr);
  if (!market && (price as number) <= 0) return { raw, ok: false, side, qty, symbol: sym, error: "Price must be greater than 0" };
  return { raw, ok: true, side, symbol: sym, securityId: secMap.get(sym)!, qty, price, market };
}

const fmtCmd = (o: any) => `${o.side === "SELL" ? "S" : "B"} ${o.quantity} ${o.symbol} @${o.price != null ? " " + o.price : ""}`;

const EXAMPLES = ["B 100 GP @ 120", "S 50 BRACBANK @", "B 200 SQURPHARMA @ 220"];

/** A placed-order outcome; status is kept live (SSE + poll) until it reaches a terminal state. */
type Res = { ok: boolean; status?: string; orderRef?: string; orderId?: number; err?: string };
const TERMINAL = new Set(["FILLED", "REJECTED", "CANCELLED", "EXPIRED"]);
const stColor = (s?: string) =>
  s === "FILLED" ? "text-bull"
  : s === "REJECTED" || s === "CANCELLED" || s === "EXPIRED" ? "text-bear"
  : s === "PARTIAL" ? "text-aurora-cyan"
  : "text-ink-400";

export default function OrderBotPage() {
  const [text, setText] = useState("");
  const [accounts, setAccounts] = useState<any[]>([]);
  const [accountId, setAccountId] = useState<number | null>(null);
  const [secMap, setSecMap] = useState<Map<string, number>>(new Map());
  const [voiceLang, setVoiceLang] = useState<"en-US" | "bn-BD">("en-US");
  const [listening, setListening] = useState(false);
  const [busyVoice, setBusyVoice] = useState(false);
  const [heard, setHeard] = useState("");
  const [results, setResults] = useState<Record<number, Res>>({});
  const [placingAll, setPlacingAll] = useState(false);
  const resultsRef = useRef(results); resultsRef.current = results;
  const placed = useRef<Set<string>>(new Set());
  const recRef = useRef<any>(null);
  const transcriptRef = useRef("");
  useEffect(() => () => { try { recRef.current?.stop(); } catch {} }, []);

  useEffect(() => {
    const s = getSession();
    if (s?.brokerId) get<any[]>(`/api/accounts?brokerId=${s.brokerId}`).then((a) => {
      setAccounts(a); setAccountId(s.defaultAccountId || a[0]?.id || null);
    }).catch(() => {});
    get<any[]>("/api/market/watch?exchange=DSE").then((r) => {
      const m = new Map<string, number>();
      (r || []).forEach((x: any) => { if (x.assetClass !== "INDEX") m.set((x.symbol || "").toUpperCase(), x.securityId); });
      setSecMap(m);
    }).catch(() => {});
  }, []);

  const lines = useMemo(() => text.split(/\n+/).map((l) => l.trim()).filter(Boolean).map((l) => validate(l, secMap)), [text, secMap]);
  const validCount = lines.filter((l) => l.ok).length;

  const place = async (l: Line, idx: number) => {
    if (!l.ok || !accountId) return;
    const key = `${accountId}:${l.raw}`;
    if (placed.current.has(key)) return;
    placed.current.add(key);
    try {
      const r = await post<any>("/api/orders", {
        accountId, securityId: l.securityId, side: l.side, orderType: l.market ? "MARKET" : "LIMIT",
        tradeWindow: "NORMAL", validity: "DAY", price: l.market ? null : l.price, stopPrice: null,
        quantity: l.qty, dealerId: null, priceBasis: "PRICE", orderYield: null,
      });
      const ord = r.order;
      setResults((p) => ({ ...p, [idx]: { ok: ord?.status !== "REJECTED", status: ord?.status || "PLACED", orderRef: ord?.orderRef, orderId: ord?.id } }));
    } catch (e: any) {
      placed.current.delete(key);
      setResults((p) => ({ ...p, [idx]: { ok: false, status: "FAILED", err: e.message || "failed" } }));
    }
  };
  const placeAll = async () => {
    if (placingAll) return;
    setPlacingAll(true);
    try { for (let i = 0; i < lines.length; i++) if (lines[i].ok) await place(lines[i], i); }
    finally { setPlacingAll(false); }
  };

  // keep placed-order cards live (e.g. PENDING_RISK → FILLED once FIXSIM's ExecutionReport lands)
  const applyStatus = (byId: Map<number, string>) => setResults((prev) => {
    let changed = false; const next: Record<number, Res> = { ...prev };
    for (const k of Object.keys(next)) {
      const r = next[+k]; if (!r.orderId) continue;
      const st = byId.get(r.orderId);
      if (st && st !== r.status) { next[+k] = { ...r, status: st, ok: st !== "REJECTED" }; changed = true; }
    }
    return changed ? next : prev;
  });
  const { connected } = useLive((type, data) => {
    if (type === "order" && data?.id != null && data?.status) applyStatus(new Map<number, string>([[data.id, data.status]]));
  });
  useEffect(() => {
    const t = setInterval(async () => {                       // fallback in case SSE is proxied/buffered
      const cur = resultsRef.current;
      if (!accountId || !Object.values(cur).some((r) => r.orderId && !TERMINAL.has(r.status || ""))) return;
      try {
        const list = await get<any[]>(`/api/orders?accountId=${accountId}`);
        const m = new Map<number, string>(); (list || []).forEach((o: any) => m.set(o.id, o.status));
        applyStatus(m);
      } catch {}
    }, 4000);
    return () => clearInterval(t);
  }, [accountId]);

  const fromSpeech = async (transcript: string) => {
    setHeard(transcript); setBusyVoice(true);
    try {
      const r = await post<{ orders: any[] }>("/api/ai/order-intent", { text: transcript });
      const cmds = (r.orders || []).filter((o) => o.ok).map(fmtCmd);
      if (cmds.length) { setText((prev) => (prev.trim() ? prev.trim() + "\n" : "") + cmds.join("\n")); setResults({}); }
    } catch {} finally { setBusyVoice(false); }
  };
  const mic = () => {
    if (busyVoice) return;
    // second press → finish; hand the whole utterance to the AI to build the command
    if (listening) { try { recRef.current?.stop(); } catch {} return; }
    const SR = (typeof window !== "undefined") && ((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition);
    if (!SR) { alert("Voice input isn't supported in this browser."); return; }
    const r = new SR();
    r.lang = voiceLang; r.continuous = true; r.interimResults = true; r.maxAlternatives = 1;
    transcriptRef.current = ""; setHeard("");
    r.onresult = (e: any) => {
      let txt = "";
      for (let i = 0; i < e.results.length; i++) txt += e.results[i][0].transcript + " ";
      transcriptRef.current = txt.trim(); setHeard(txt.trim());
    };
    r.onerror = () => setListening(false);
    r.onend = () => { setListening(false); const t = transcriptRef.current.trim(); if (t) fromSpeech(t); };
    try { r.start(); recRef.current = r; setListening(true); } catch { setListening(false); }
  };

  return (
    <Shell title="AI Order Bot" connected={connected}>
      <div className="mx-auto flex h-full w-full max-w-3xl flex-col gap-4">
        <div className="glass p-4">
          <div className="mb-2 flex items-center justify-between">
            <div className="panel-title">Command trading</div>
            <select className="field max-w-[220px] py-1 text-xs" value={accountId ?? ""} onChange={(e) => setAccountId(parseInt(e.target.value))}>
              {accounts.map((a) => <option key={a.id} value={a.id} className="bg-obsidian-850">{a.name} · {a.boId}</option>)}
            </select>
          </div>
          <div className="mb-2 rounded-lg bg-surface/[0.05] px-3 py-2 text-[11px] text-ink-400">
            Format: <span className="tnum font-semibold text-ink-200">B|S&nbsp;&nbsp;qty&nbsp;&nbsp;TICKER&nbsp;&nbsp;@&nbsp;&nbsp;[price]</span>
            &nbsp;— e.g. <span className="tnum text-bull">B 100 GP @ 120</span> (limit), <span className="tnum text-bear">S 50 BRACBANK @</span> (market). One order per line.
          </div>
          <div className="flex items-end gap-2">
            <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} spellCheck={false}
              placeholder="B 100 GP @ 120" className="field flex-1 resize-none font-mono uppercase tracking-wide" />
            <div className="flex flex-col items-stretch gap-1">
              <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5 text-[10px]">
                {(["en-US", "bn-BD"] as const).map((lng) => (
                  <button key={lng} onClick={() => setVoiceLang(lng)} disabled={listening}
                    className={`rounded px-2 py-1 font-semibold disabled:opacity-40 ${voiceLang === lng ? "bg-aurora-indigo/40 text-white" : "text-ink-500"}`}>
                    {lng === "en-US" ? "EN" : "বাংলা"}
                  </button>
                ))}
              </div>
              <button onClick={mic} title="Press to speak, then press again when you're done"
                className={`h-9 justify-center whitespace-nowrap rounded-lg border px-3 text-xs font-semibold transition-all ${
                  listening ? "animate-pulse border-bear/70 bg-bear/20 text-bear" : "ghost-btn"} ${busyVoice ? "opacity-70" : ""}`}>
                {busyVoice ? "Reading…" : listening ? "◉ Done" : "🎤 Speak"}
              </button>
            </div>
          </div>
          {listening && (
            <div className="mt-2 flex items-center gap-2 rounded-lg border border-bear/40 bg-bear/10 px-3 py-2 text-[12px] text-bear">
              <span className="relative flex h-2.5 w-2.5">
                <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-bear/70" />
                <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-bear" />
              </span>
              <span>Listening… <b>speak now</b>, then press <b>◉ Done</b> when finished.{heard ? ` — “${heard}”` : ""}</span>
            </div>
          )}
          {!listening && heard && <div className="mt-1 text-[11px] text-ink-500">Heard: “{heard}” → auto-formatted below.</div>}
          <div className="mt-1 flex flex-wrap gap-1.5">
            {EXAMPLES.map((ex) => (
              <button key={ex} onClick={() => setText((p) => (p.trim() ? p.trim() + "\n" : "") + ex)}
                className="chip bg-surface/[0.06] font-mono text-ink-400 hover:text-ink-100">{ex}</button>
            ))}
          </div>
          <div className="mt-2 text-[11px] text-ink-600">Press <b>🎤 Speak</b>, talk in English or বাংলা (any wording), then press <b>◉ Done</b> — the system writes the validated command for you. Nothing is placed until you confirm.</div>
        </div>

        {lines.length > 0 && (
          <div className="glass min-h-0 flex-1 overflow-auto p-4">
            <div className="mb-3 flex items-center justify-between">
              <div className="panel-title">{validCount} valid{lines.length - validCount ? ` · ${lines.length - validCount} to fix` : ""}</div>
              {validCount > 0 && <button onClick={placeAll} disabled={placingAll} className="aurora-btn px-4 py-1.5 text-xs disabled:opacity-40">{placingAll ? "Placing…" : `Place all ${validCount}`}</button>}
            </div>
            <div className="space-y-2">
              {lines.map((l, i) => {
                const res = results[i];
                return (
                  <div key={i} className={`glass-soft flex flex-wrap items-center gap-3 p-3 ${l.ok ? "" : "border-l-2 border-bear/60"}`}>
                    <span className={`text-[11px] ${l.ok ? "text-bull" : "text-bear"}`}>{l.ok ? "✓" : "✕"}</span>
                    <span className="font-mono text-[12px] text-ink-500">{l.raw}</span>
                    {l.ok ? (
                      <>
                        <span className="text-ink-600">→</span>
                        <span className={`chip font-bold ${l.side === "SELL" ? "bg-bear/15 text-bear" : "bg-bull/15 text-bull"}`}>{l.side}</span>
                        <span className="text-sm font-semibold text-ink-100">{l.symbol}</span>
                        <span className="tnum text-sm text-ink-200">{l.qty?.toLocaleString()}</span>
                        <span className="tnum text-sm text-ink-300">{l.market ? "@ market" : `@ ${nf(l.price as number)}`}</span>
                      </>
                    ) : (
                      <span className="text-[12px] text-bear">{l.error}</span>
                    )}
                    <div className="ml-auto flex items-center gap-2">
                      {res && (res.err
                        ? <span className="text-[12px] text-bear">{res.err}</span>
                        : <span className={`text-[12px] tnum font-semibold ${stColor(res.status)}`}>{res.status}{res.orderRef ? ` · ${res.orderRef}` : ""}</span>)}
                      {l.ok && !res && <button onClick={() => place(l, i)} className="ghost-btn px-3 py-1 text-xs">Place</button>}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>
    </Shell>
  );
}
