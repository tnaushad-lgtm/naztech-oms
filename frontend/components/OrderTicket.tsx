"use client";

import { useEffect, useRef, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import { get, post } from "@/lib/api";
import { nf } from "@/lib/format";

type Sec = { securityId: number; symbol: string; name: string; ltp: number; assetClass: string };
type Risk = { pass: boolean; reason: string; score: number; flags: string[] };

const TYPES = ["LIMIT", "MARKET", "STOP", "STOP_LIMIT"];
const WINDOWS = ["NORMAL", "SPOT", "BLOCK", "ODD_LOT", "FOREIGN"];
const VALIDITY = ["DAY", "GTC", "GTD", "GTS"];

export function OrderTicket({
  sec, accountId, dealerId, pickedPrice, onPlaced,
}: {
  sec: Sec | null; accountId: number | null; dealerId: number | null;
  pickedPrice?: number; onPlaced?: () => void;
}) {
  const [side, setSide] = useState<"BUY" | "SELL">("BUY");
  const [type, setType] = useState("LIMIT");
  const [tradeWindow, setWindow] = useState("NORMAL");
  const [validity, setValidity] = useState("DAY");
  const [qty, setQty] = useState<number>(100);
  const [price, setPrice] = useState<number>(0);
  const [stop, setStop] = useState<number>(0);
  const [risk, setRisk] = useState<Risk | null>(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ ok: boolean; msg: string } | null>(null);
  const timer = useRef<any>(null);

  // ---- bonds: trade on price OR yield ----
  const isBond = !!sec && /BOND|SUKUK/i.test(sec.assetClass || "");
  const [priceBasis, setPriceBasis] = useState<"PRICE" | "YIELD">("PRICE");
  const [yieldPct, setYieldPct] = useState<number>(8);
  const [bondQ, setBondQ] = useState<{ cleanPrice: number; accrued: number; dirtyPrice: number; yield: number } | null>(null);
  useEffect(() => { if (!isBond) setPriceBasis("PRICE"); }, [isBond]);

  useEffect(() => { if (sec) setPrice(sec.ltp); }, [sec?.securityId]);
  useEffect(() => { if (pickedPrice && pickedPrice > 0) setPrice(pickedPrice); }, [pickedPrice]);

  // live bond price⇄yield quote (clean / accrued / dirty / YTM)
  useEffect(() => {
    if (!isBond || !sec) { setBondQ(null); return; }
    const value = priceBasis === "YIELD" ? yieldPct : price;
    if (!value || value <= 0) { setBondQ(null); return; }
    const t = setTimeout(async () => {
      try {
        const q = await get<any>(`/api/bonds/${sec.securityId}/quote?basis=${priceBasis}&value=${value}`);
        setBondQ({ cleanPrice: +q.cleanPrice, accrued: +q.accrued, dirtyPrice: +q.dirtyPrice, yield: +q.yield });
      } catch { setBondQ(null); }
    }, 250);
    return () => clearTimeout(t);
  }, [isBond, sec?.securityId, priceBasis, yieldPct, price]);

  const body = () => ({
    accountId, securityId: sec?.securityId, side, orderType: type, tradeWindow, validity,
    price: type === "MARKET" ? null : (isBond && priceBasis === "YIELD" ? (bondQ?.cleanPrice ?? null) : price),
    stopPrice: type.startsWith("STOP") ? stop : null,
    quantity: qty, dealerId,
    priceBasis: isBond ? priceBasis : "PRICE",
    orderYield: isBond && priceBasis === "YIELD" ? yieldPct : null,
  });

  // live risk preview (debounced)
  useEffect(() => {
    if (!sec || !accountId || !qty) { setRisk(null); return; }
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      try { setRisk(await post<Risk>("/api/ai/risk-preview", body())); } catch { setRisk(null); }
    }, 300);
  }, [sec?.securityId, accountId, side, type, tradeWindow, validity, qty, price, stop, priceBasis, yieldPct, bondQ]);

  const notional = (isBond && bondQ ? bondQ.dirtyPrice : type === "MARKET" ? sec?.ltp || 0 : price) * qty;

  async function place() {
    if (!sec || !accountId) return;
    setBusy(true); setToast(null);
    try {
      const r = await post<{ order: any; risk: Risk }>("/api/orders", body());
      const o = r.order;
      if (o.status === "REJECTED") setToast({ ok: false, msg: `Rejected — ${o.rejectReason}` });
      else setToast({ ok: true, msg: `${o.status}: ${o.side} ${o.quantity} ${o.symbol} (ref ${o.orderRef})` });
      onPlaced?.();
    } catch (e: any) {
      setToast({ ok: false, msg: e.message || "Order failed" });
    } finally { setBusy(false); setTimeout(() => setToast(null), 6000); }
  }

  const score = risk?.score ?? 0;
  const scoreColor = score >= 60 ? "#fb5b6b" : score >= 35 ? "#fbbf24" : "#22c55e";

  return (
    <div className="glass p-4">
      <div className="mb-3 flex items-center justify-between">
        <div className="panel-title">Order Ticket</div>
        {sec && <div className="text-[11px] text-ink-400">{sec.symbol} · LTP <span className="tnum text-ink-200">{nf(sec.ltp)}</span></div>}
      </div>

      {/* BUY / SELL */}
      <div className="grid grid-cols-2 gap-2">
        {(["BUY", "SELL"] as const).map((s) => (
          <button key={s} onClick={() => setSide(s)}
            className={`rounded-xl py-2.5 text-sm font-bold tracking-wide transition-all
              ${side === s
                ? s === "BUY" ? "bg-bull/20 text-bull shadow-[0_0_0_1px_rgba(34,197,94,0.4)]"
                              : "bg-bear/20 text-bear shadow-[0_0_0_1px_rgba(251,91,107,0.4)]"
                : "bg-surface/[0.05] text-ink-500 hover:text-ink-300"}`}>
            {s}
          </button>
        ))}
      </div>

      <div className="mt-3 grid grid-cols-2 gap-2">
        <Select label="Type" value={type} onChange={setType} options={TYPES} />
        <Select label="Window" value={tradeWindow} onChange={setWindow} options={WINDOWS} />
      </div>

      {isBond && (
        <div className="mt-2">
          <label className="panel-title">Quote by</label>
          <div className="mt-1 flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
            {(["PRICE", "YIELD"] as const).map((b) => (
              <button key={b} type="button" onClick={() => setPriceBasis(b)}
                className={`flex-1 rounded-lg py-1.5 text-xs font-semibold transition-all ${
                  priceBasis === b ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
                {b === "PRICE" ? "Price" : "Yield (YTM)"}
              </button>
            ))}
          </div>
        </div>
      )}

      <div className="mt-2 grid grid-cols-2 gap-2">
        <Field label="Quantity">
          <input type="number" className="field" value={qty} min={1}
            onChange={(e) => setQty(parseInt(e.target.value) || 0)} />
        </Field>
        {isBond && priceBasis === "YIELD" ? (
          <Field label="Yield % (YTM)">
            <input type="number" step="0.01" className="field" value={yieldPct}
              onChange={(e) => setYieldPct(parseFloat(e.target.value) || 0)} />
          </Field>
        ) : (
          <Field label={type === "MARKET" ? "Price (mkt)" : isBond ? "Clean Price" : "Price"}>
            <input type="number" step="0.1" className="field" value={type === "MARKET" ? "" : price}
              placeholder={type === "MARKET" ? "Market" : ""} disabled={type === "MARKET"}
              onChange={(e) => setPrice(parseFloat(e.target.value) || 0)} />
          </Field>
        )}
      </div>
      {isBond && bondQ && (
        <div className="mt-1 rounded-lg bg-surface/[0.05] px-2 py-1.5 text-[11px] tnum text-ink-400">
          Clean <span className="text-ink-200">{nf(bondQ.cleanPrice)}</span> · Accrued <span className="text-ink-200">{nf(bondQ.accrued)}</span> · Dirty <span className="text-ink-200">{nf(bondQ.dirtyPrice)}</span> · YTM <span className="text-aurora-cyan">{nf(bondQ.yield)}%</span>
        </div>
      )}

      <div className="mt-2 grid grid-cols-2 gap-2">
        <Select label="Validity" value={validity} onChange={setValidity} options={VALIDITY} />
        {type.startsWith("STOP") ? (
          <Field label="Stop Price">
            <input type="number" step="0.1" className="field" value={stop}
              onChange={(e) => setStop(parseFloat(e.target.value) || 0)} />
          </Field>
        ) : (
          <Field label="Est. Value">
            <div className="field tnum flex items-center text-ink-300">৳{nf(notional)}</div>
          </Field>
        )}
      </div>

      {/* AI risk preview */}
      <div className="mt-3 rounded-xl border border-line/[0.1] bg-obsidian-900/50 p-3">
        <div className="flex items-center justify-between">
          <span className="panel-title">AI Pre-Trade Risk</span>
          {risk && (
            <span className={`chip ${risk.pass ? "bg-bull/15 text-bull" : "bg-bear/15 text-bear"}`}>
              {risk.pass ? "Cleared" : "Blocked"}
            </span>
          )}
        </div>
        <div className="mt-2 flex items-center gap-3">
          <div className="relative h-12 w-12 shrink-0">
            <svg viewBox="0 0 36 36" className="h-12 w-12 -rotate-90">
              <circle cx="18" cy="18" r="15.5" fill="none" stroke="rgba(255,255,255,0.07)" strokeWidth="3.5" />
              <circle cx="18" cy="18" r="15.5" fill="none" stroke={scoreColor} strokeWidth="3.5"
                strokeDasharray={`${(score / 100) * 97.4} 97.4`} strokeLinecap="round"
                style={{ transition: "stroke-dasharray 0.4s ease" }} />
            </svg>
            <div className="absolute inset-0 flex items-center justify-center tnum text-xs font-bold" style={{ color: scoreColor }}>
              {Math.round(score)}
            </div>
          </div>
          <div className="min-w-0 flex-1">
            <div className="text-[11px] text-ink-500">Risk score (0 = calm · 100 = severe)</div>
            <ul className="mt-0.5 space-y-0.5">
              {(risk?.flags || ["Enter an order to preview risk"]).slice(0, 3).map((f, i) => (
                <li key={i} className="truncate text-[11px] text-ink-300">• {f}</li>
              ))}
            </ul>
          </div>
        </div>
        {risk && !risk.pass && <div className="mt-2 text-[11px] font-medium text-bear">{risk.reason}</div>}
      </div>

      <button onClick={place} disabled={busy || !sec || !accountId || (risk ? !risk.pass : false)}
        className={`mt-3 w-full rounded-xl py-3 text-sm font-bold tracking-wide transition-all disabled:opacity-40
          ${side === "BUY" ? "bg-gradient-to-r from-bull/80 to-emerald-500 text-obsidian-950 hover:brightness-110"
                           : "bg-gradient-to-r from-bear to-rose-500 text-white hover:brightness-110"}`}>
        {busy ? "Routing…" : `${side} ${sec?.symbol || ""}`}
      </button>

      <AnimatePresence>
        {toast && (
          <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0 }}
            className={`mt-3 rounded-lg px-3 py-2 text-[12px] ${toast.ok ? "bg-bull/10 text-bull" : "bg-bear/10 text-bear"}`}>
            {toast.msg}
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="panel-title">{label}</label>
      <div className="mt-1">{children}</div>
    </div>
  );
}
function Select({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: string[] }) {
  return (
    <Field label={label}>
      <select className="field" value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => <option key={o} value={o} className="bg-obsidian-850">{o.replace(/_/g, " ")}</option>)}
      </select>
    </Field>
  );
}
