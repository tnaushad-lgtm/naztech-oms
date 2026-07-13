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

type Props = {
  sec: Sec | null;
  accountId: number | null;
  dealerId: number | null;
  pickedPrice?: number;
  onPlaced?: () => void;
};

/**
 * Order entry, in two pieces.
 *
 * <p><b>{@link QuickTrade}</b> — a small card with the symbol and two large BUY/SELL buttons. It is
 * deliberately short: it has to sit in a narrow column, or in a dockable panel a dealer may have
 * shrunk to a sliver, and still work.
 *
 * <p><b>{@link OrderWindow}</b> — the full ticket, opened over the app. Every field is visible at
 * once and the confirm button lives in a fixed footer.
 *
 * <p>The ticket used to live in the right-hand column, which meant the most important control in the
 * product — the button that actually sends the order — sat below the fold whenever the form grew (a
 * bond adds a yield row; a stop adds a stop price; a blocked order adds a warning). Making it float
 * only moved the problem on top of the blotter. A dealer should never scroll to buy: the window is
 * the fix, and it works the same however narrow the panel behind it is.
 */
export function QuickTrade({ sec, accountId, dealerId, pickedPrice, onPlaced }: Props) {
  const [open, setOpen] = useState<null | "BUY" | "SELL">(null);

  return (
    <>
      <div className="glass p-4">
        <div className="mb-3 flex items-center justify-between">
          <div className="panel-title">Order Ticket</div>
          {sec && (
            <div className="text-[11px] text-ink-400">
              {sec.symbol} · LTP <span className="tnum text-ink-200">{nf(sec.ltp)}</span>
            </div>
          )}
        </div>

        <div className="grid grid-cols-2 gap-3">
          <button onClick={() => setOpen("BUY")} disabled={!sec || !accountId}
            className="rounded-xl bg-gradient-to-r from-bull/80 to-emerald-500 py-4 text-sm font-bold tracking-wide
                       text-obsidian-950 transition-all hover:brightness-110 active:scale-[0.98] disabled:opacity-40">
            BUY {sec?.symbol || ""}
          </button>
          <button onClick={() => setOpen("SELL")} disabled={!sec || !accountId}
            className="rounded-xl bg-gradient-to-r from-bear to-rose-500 py-4 text-sm font-bold tracking-wide
                       text-white transition-all hover:brightness-110 active:scale-[0.98] disabled:opacity-40">
            SELL {sec?.symbol || ""}
          </button>
        </div>

        <div className="mt-2.5 text-center text-[11px] text-ink-600">
          Opens the full order window — quantity, price, validity and pre-trade risk.
        </div>
      </div>

      <AnimatePresence>
        {open && sec && (
          <OrderWindow sec={sec} accountId={accountId} dealerId={dealerId} pickedPrice={pickedPrice}
            initialSide={open} onClose={() => setOpen(null)}
            onPlaced={() => { onPlaced?.(); setOpen(null); }} />
        )}
      </AnimatePresence>
    </>
  );
}

/** The full order window. Everything visible at once; the confirm button never scrolls away. */
export function OrderWindow({
  sec, accountId, dealerId, pickedPrice, initialSide, onClose, onPlaced,
}: Props & { sec: Sec; initialSide: "BUY" | "SELL"; onClose: () => void }) {
  const [side, setSide] = useState<"BUY" | "SELL">(initialSide);
  const [type, setType] = useState("LIMIT");
  const [tradeWindow, setWindow] = useState("NORMAL");
  const [validity, setValidity] = useState("DAY");
  const [qty, setQty] = useState<number>(100);
  const [price, setPrice] = useState<number>(sec.ltp || 0);
  const [stop, setStop] = useState<number>(0);
  const [risk, setRisk] = useState<Risk | null>(null);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<{ ok: boolean; msg: string } | null>(null);
  const timer = useRef<any>(null);

  // ---- bonds: trade on price OR yield ----
  const isBond = /BOND|SUKUK/i.test(sec.assetClass || "");
  const [priceBasis, setPriceBasis] = useState<"PRICE" | "YIELD">("PRICE");
  const [yieldPct, setYieldPct] = useState<number>(8);
  const [bondQ, setBondQ] = useState<{ cleanPrice: number; accrued: number; dirtyPrice: number; yield: number } | null>(null);

  useEffect(() => { if (pickedPrice && pickedPrice > 0) setPrice(pickedPrice); }, [pickedPrice]);
  useEffect(() => {
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", esc);
    return () => window.removeEventListener("keydown", esc);
  }, [onClose]);

  // live bond price⇄yield quote (clean / accrued / dirty / YTM)
  useEffect(() => {
    if (!isBond) { setBondQ(null); return; }
    const value = priceBasis === "YIELD" ? yieldPct : price;
    if (!value || value <= 0) { setBondQ(null); return; }
    const t = setTimeout(async () => {
      try {
        const q = await get<any>(`/api/bonds/${sec.securityId}/quote?basis=${priceBasis}&value=${value}`);
        setBondQ({ cleanPrice: +q.cleanPrice, accrued: +q.accrued, dirtyPrice: +q.dirtyPrice, yield: +q.yield });
      } catch { setBondQ(null); }
    }, 250);
    return () => clearTimeout(t);
  }, [isBond, sec.securityId, priceBasis, yieldPct, price]);

  const body = () => ({
    accountId, securityId: sec.securityId, side, orderType: type, tradeWindow, validity,
    price: type === "MARKET" ? null : (isBond && priceBasis === "YIELD" ? (bondQ?.cleanPrice ?? null) : price),
    stopPrice: type.startsWith("STOP") ? stop : null,
    quantity: qty, dealerId,
    priceBasis: isBond ? priceBasis : "PRICE",
    orderYield: isBond && priceBasis === "YIELD" ? yieldPct : null,
  });

  // live risk preview (debounced)
  useEffect(() => {
    if (!accountId || !qty) { setRisk(null); return; }
    clearTimeout(timer.current);
    timer.current = setTimeout(async () => {
      try { setRisk(await post<Risk>("/api/ai/risk-preview", body())); } catch { setRisk(null); }
    }, 300);
  }, [accountId, side, type, tradeWindow, validity, qty, price, stop, priceBasis, yieldPct, bondQ]);

  const notional = (isBond && bondQ ? bondQ.dirtyPrice : type === "MARKET" ? sec.ltp || 0 : price) * qty;
  const score = risk?.score ?? 0;
  const scoreColor = score >= 60 ? "#fb5b6b" : score >= 35 ? "#fbbf24" : "#22c55e";
  const blocked = risk ? !risk.pass : false;

  async function place() {
    if (!accountId) return;
    setBusy(true); setToast(null);
    try {
      const r = await post<{ order: any; risk: Risk }>("/api/orders", body());
      const o = r.order;
      if (o.status === "REJECTED") {
        setToast({ ok: false, msg: `Rejected — ${o.rejectReason}` });
      } else {
        setToast({ ok: true, msg: `${o.status}: ${o.side} ${o.quantity} ${o.symbol} (ref ${o.orderRef})` });
        setTimeout(() => onPlaced?.(), 900);      // let the dealer read the confirmation
      }
    } catch (e: any) {
      setToast({ ok: false, msg: e.message || "Order failed" });
    } finally { setBusy(false); }
  }

  return (
    <motion.div className="fixed inset-0 z-50 flex items-center justify-center bg-obsidian-950/70 p-4 backdrop-blur-sm"
      initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose}>
      <motion.div onClick={(e) => e.stopPropagation()}
        initial={{ scale: 0.96, y: 12 }} animate={{ scale: 1, y: 0 }} exit={{ scale: 0.97, opacity: 0 }}
        transition={{ type: "spring", stiffness: 320, damping: 26 }}
        className="glass flex max-h-[88vh] w-full max-w-3xl flex-col overflow-hidden">

        {/* header */}
        <div className="flex shrink-0 items-center gap-3 border-b border-line/[0.1] px-5 py-3.5">
          <div>
            <div className="text-[15px] font-bold text-ink-100">{sec.symbol}</div>
            <div className="text-[11px] text-ink-500">{sec.name}</div>
          </div>
          <div className="tnum text-xl font-bold text-ink-100">{nf(sec.ltp)}</div>
          <button onClick={onClose}
            className="ml-auto rounded-lg px-2 py-1 text-lg text-ink-500 hover:bg-surface/[0.08] hover:text-ink-100">✕</button>
        </div>

        {/* body — two columns, so nothing needs scrolling on a normal screen */}
        <div className="min-h-0 flex-1 overflow-y-auto p-5">
          <div className="grid grid-cols-1 gap-5 md:grid-cols-2">
            <div className="space-y-3">
              <div className="grid grid-cols-2 gap-2">
                {(["BUY", "SELL"] as const).map((s) => (
                  <button key={s} onClick={() => setSide(s)}
                    className={`rounded-xl py-3 text-sm font-bold tracking-wide transition-all
                      ${side === s
                        ? s === "BUY" ? "bg-bull/20 text-bull shadow-[0_0_0_1px_rgba(34,197,94,0.5)]"
                                      : "bg-bear/20 text-bear shadow-[0_0_0_1px_rgba(251,91,107,0.5)]"
                        : "bg-surface/[0.05] text-ink-500 hover:text-ink-300"}`}>
                    {s}
                  </button>
                ))}
              </div>

              <div className="grid grid-cols-2 gap-2">
                <Select label="Type" value={type} onChange={setType} options={TYPES} />
                <Select label="Window" value={tradeWindow} onChange={setWindow} options={WINDOWS} />
              </div>

              {isBond && (
                <div>
                  <label className="panel-title">Quote by</label>
                  <div className="mt-1 flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
                    {(["PRICE", "YIELD"] as const).map((b) => (
                      <button key={b} type="button" onClick={() => setPriceBasis(b)}
                        className={`flex-1 rounded-lg py-1.5 text-xs font-semibold transition-all ${
                          priceBasis === b ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white"
                                           : "text-ink-400 hover:text-ink-100"}`}>
                        {b === "PRICE" ? "Price" : "Yield (YTM)"}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              <div className="grid grid-cols-2 gap-2">
                <Field label="Quantity">
                  <input type="number" className="field" value={qty} min={1} autoFocus
                    onChange={(e) => setQty(parseInt(e.target.value) || 0)} />
                </Field>
                {isBond && priceBasis === "YIELD" ? (
                  <Field label="Yield % (YTM)">
                    <input type="number" step="0.01" className="field" value={yieldPct}
                      onChange={(e) => setYieldPct(parseFloat(e.target.value) || 0)} />
                  </Field>
                ) : (
                  <Field label={type === "MARKET" ? "Price (market)" : isBond ? "Clean price" : "Price"}>
                    <input type="number" step="0.1" className="field" value={type === "MARKET" ? "" : price}
                      placeholder={type === "MARKET" ? "Market" : ""} disabled={type === "MARKET"}
                      onChange={(e) => setPrice(parseFloat(e.target.value) || 0)} />
                  </Field>
                )}
              </div>

              <div className="grid grid-cols-2 gap-2">
                <Select label="Validity" value={validity} onChange={setValidity} options={VALIDITY} />
                {type.startsWith("STOP") ? (
                  <Field label="Stop price">
                    <input type="number" step="0.1" className="field" value={stop}
                      onChange={(e) => setStop(parseFloat(e.target.value) || 0)} />
                  </Field>
                ) : (
                  <Field label="Order value">
                    <div className="field tnum flex items-center text-ink-300">৳{nf(notional)}</div>
                  </Field>
                )}
              </div>

              {isBond && bondQ && (
                <div className="rounded-lg bg-surface/[0.05] px-2.5 py-2 text-[11.5px] tnum text-ink-400">
                  Clean <span className="text-ink-200">{nf(bondQ.cleanPrice)}</span> · Accrued <span className="text-ink-200">{nf(bondQ.accrued)}</span>
                  {" "}· Dirty <span className="text-ink-200">{nf(bondQ.dirtyPrice)}</span> · YTM <span className="text-aurora-cyan">{nf(bondQ.yield)}%</span>
                </div>
              )}
            </div>

            {/* risk */}
            <div className="rounded-xl border border-line/[0.1] bg-obsidian-900/50 p-4">
              <div className="flex items-center justify-between">
                <span className="panel-title">AI Pre-Trade Risk</span>
                {risk && (
                  <span className={`chip ${risk.pass ? "bg-bull/15 text-bull" : "bg-bear/15 text-bear"}`}>
                    {risk.pass ? "Cleared" : "Blocked"}
                  </span>
                )}
              </div>
              <div className="mt-3 flex items-center gap-3">
                <div className="relative h-14 w-14 shrink-0">
                  <svg viewBox="0 0 36 36" className="h-14 w-14 -rotate-90">
                    <circle cx="18" cy="18" r="15.5" fill="none" stroke="rgba(255,255,255,0.07)" strokeWidth="3.5" />
                    <circle cx="18" cy="18" r="15.5" fill="none" stroke={scoreColor} strokeWidth="3.5"
                      strokeDasharray={`${(score / 100) * 97.4} 97.4`} strokeLinecap="round"
                      style={{ transition: "stroke-dasharray 0.4s ease" }} />
                  </svg>
                  <div className="absolute inset-0 flex items-center justify-center tnum text-sm font-bold" style={{ color: scoreColor }}>
                    {Math.round(score)}
                  </div>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="text-[11px] text-ink-500">Risk score (0 = calm · 100 = severe)</div>
                  <ul className="mt-1 space-y-0.5">
                    {(risk?.flags || ["Set a quantity and price to preview risk"]).slice(0, 4).map((f, i) => (
                      <li key={i} className="text-[11.5px] text-ink-300">• {f}</li>
                    ))}
                  </ul>
                </div>
              </div>

              {blocked && (
                <div className="mt-3 rounded-xl border border-bear/40 bg-bear/10 px-3 py-2">
                  <div className="text-[11px] font-semibold uppercase tracking-wider text-bear">Order blocked</div>
                  <div className="mt-0.5 text-[12.5px] text-ink-100">{risk!.reason}</div>
                </div>
              )}
            </div>
          </div>

          {toast && (
            <div className={`mt-4 rounded-xl px-4 py-2.5 text-[12.5px] font-medium ${
              toast.ok ? "bg-bull/15 text-bull" : "bg-bear/15 text-bear"}`}>
              {toast.msg}
            </div>
          )}
        </div>

        {/* footer — the confirm button. Fixed: it is never scrolled away, never covered. */}
        <div className="flex shrink-0 items-center gap-3 border-t border-line/[0.1] bg-obsidian-850/60 px-5 py-4">
          <div className="text-[12px] text-ink-500">
            {qty} {sec.symbol} @ {type === "MARKET" ? "market" : nf(price)}
            {" · "}<span className="tnum text-ink-200">৳{nf(notional)}</span>
          </div>
          <button onClick={onClose} className="ghost-btn ml-auto px-5 py-3 text-sm">Cancel</button>
          <button onClick={place} disabled={busy || !accountId || blocked}
            className={`min-w-[220px] rounded-xl px-6 py-3 text-sm font-bold tracking-wide transition-all disabled:opacity-40
              ${side === "BUY" ? "bg-gradient-to-r from-bull/80 to-emerald-500 text-obsidian-950 hover:brightness-110"
                               : "bg-gradient-to-r from-bear to-rose-500 text-white hover:brightness-110"}`}>
            {busy ? "Routing…" : blocked ? blockedLabel(risk?.reason) : `${side} ${qty} ${sec.symbol}`}
          </button>
        </div>
      </motion.div>
    </motion.div>
  );
}

/** The button says what stopped the order, not just that something did. */
function blockedLabel(reason?: string): string {
  const r = (reason || "").toLowerCase();
  if (r.includes("market is closed")) return "⛔ Market closed";
  if (r.includes("market is halted")) return "⛔ Market halted";
  if (r.includes("kill-switch")) return "⛔ Broker halted by RMS";
  if (r.includes("buying power")) return "⛔ Insufficient buying power";
  if (r.includes("holdings")) return "⛔ Insufficient holdings";
  if (r.includes("wash")) return "⛔ Wash-trade control";
  if (r.includes("limit exceeded")) return "⛔ Risk limit exceeded";
  return "⛔ Blocked by risk";
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="panel-title">{label}</label>
      <div className="mt-1">{children}</div>
    </div>
  );
}

function Select({ label, value, onChange, options }: {
  label: string; value: string; onChange: (v: string) => void; options: string[];
}) {
  return (
    <Field label={label}>
      <select className="field" value={value} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => <option key={o} value={o} className="bg-obsidian-850">{o.replace(/_/g, " ")}</option>)}
      </select>
    </Field>
  );
}

/** Kept for the dockable panel and anything else that imported the old name. */
export const OrderTicket = QuickTrade;
