"use client";

/**
 * LANTERN — multi-row order entry for a trader working a list of client instructions.
 *
 * Twenty orders are a ledger, and exactly one of them is lit. Every order is a 28px line carrying
 * what a trader transcribes from an instruction — client, ticker, side, quantity, price — plus an
 * ORDER TERMS column spelling out the rest. The row being edited grows a second band holding the
 * enumerated fields as labelled segmented controls and the bond maths. Moving the lantern is -28px on
 * one row and +28px on the next, so the list height never changes and no row below shifts.
 *
 * Four laws, each of which exists because breaking it loses money or loses the reader:
 *
 *  1. THE BAND HIDES CONTROLS, NEVER VALUES. A sleeping row still states its type, market, validity
 *     and basis in the terms column. Line two only restores the means to edit them.
 *  2. DEFAULTS ARE QUIET, EXCEPTIONS ARE LOUD — and NOTHING IS INVISIBLE. An earlier version printed
 *     nothing at all for default values, which scans beautifully for an expert and leaves a newcomer
 *     unable to tell an unset field from a defaulted one. Defaults are dim lower-case, changes are
 *     bright and hued. Nothing on this screen is dimmer than ink-400: it is the screen the desk lives
 *     on, and a value that must be squinted at is a value that will be misread.
 *  3. A DEFAULT IS NOT A DECISION, where no safe default exists. Client and side carry provenance and
 *     block the send until chosen — that is what stops forty pasted rows becoming forty confident
 *     orders to one wrong BO account. Price does not block; seeding from LTP is what every terminal
 *     does, and warning on it every time only teaches people to click past warnings.
 *  4. NOTHING MOVES THAT THE TRADER DID NOT MOVE. Risk verdicts never change a row's height; a
 *     blocked row washes red inside a fixed-width column. A forty-row paste resolving over eight
 *     seconds produces zero layout shift.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ComboBox, ComboItem } from "@/components/ComboBox";
import { SegGroup } from "@/components/grid/SegGroup";
import { useLive } from "@/lib/useLive";
import { get, post } from "@/lib/api";
import { getSession } from "@/lib/session";
import { nf, money } from "@/lib/format";

type Sec = { securityId: number; symbol: string; name: string; ltp: number; assetClass: string; category?: string };
type Acct = {
  id: number; name: string; boId: string; brokerId: number; status?: string;
  cashBalance?: number; buyingPower?: number;
};
type Risk = { pass: boolean; reason?: string; score?: number; flags?: string[] };

/** unset = never given a value · defaulted = we assumed one · confirmed = the trader chose it. */
type Prov = "unset" | "defaulted" | "confirmed";

type Row = {
  key: string;
  sel: boolean;
  accountId: number | null;   accountProv: Prov;
  securityId: number | null;  symbolText: string;
  side: "BUY" | "SELL";       sideProv: Prov;
  type: "LIMIT" | "MARKET" | "STOP" | "STOP_LIMIT";
  qty: number | null;
  price: number | null;       priceProv: Prov;
  stop: number | null;
  basis: "PRICE" | "YIELD";
  orderYield: number | null;
  window: string;
  validity: string;
  risk: Risk | null;
  checking: boolean;
  sent: { id?: number; status?: string; error?: string } | null;
};

const DEF = { window: "NORMAL", validity: "DAY", type: "LIMIT", basis: "PRICE" } as const;

const SIDE_SEGS = [
  { value: "BUY", label: "Buy", hue: "bull" as const },
  { value: "SELL", label: "Sell", hue: "bear" as const },
];
const TYPE_SEGS = [
  { value: "LIMIT", label: "Limit" },
  { value: "MARKET", label: "Market", hue: "cyan" as const },
  { value: "STOP", label: "Stop", hue: "cyan" as const },
  { value: "STOP_LIMIT", label: "S-Limit", hue: "cyan" as const },
];
const WINDOW_SEGS = [
  { value: "NORMAL", label: "Normal" },
  { value: "SPOT", label: "Spot", hue: "teal" as const },
  { value: "BLOCK", label: "Block", hue: "cyan" as const },
  { value: "ODD_LOT", label: "Odd-lot", hue: "violet" as const },
  { value: "FOREIGN", label: "Foreign", hue: "indigo" as const },
];
const VALID_SEGS = [
  { value: "DAY", label: "Day" },
  { value: "GTC", label: "GTC", hue: "violet" as const },
  { value: "GTD", label: "GTD", hue: "violet" as const },
  { value: "GTS", label: "GTS", hue: "violet" as const },
];
const BASIS_SEGS = [
  { value: "PRICE", label: "Price" },
  { value: "YIELD", label: "Yield", hue: "cyan" as const },
];

let seq = 0;
const newRow = (o: Partial<Row> = {}): Row => ({
  key: `r${Date.now()}_${seq++}`,
  sel: false,
  accountId: null, accountProv: "unset",
  securityId: null, symbolText: "",
  side: "BUY", sideProv: "unset",
  type: "LIMIT",
  qty: null,
  price: null, priceProv: "unset",
  stop: null,
  basis: "PRICE",
  orderYield: null,
  window: DEF.window,
  validity: DEF.validity,
  risk: null, checking: false, sent: null,
  ...o,
});

const isBond = (s?: Sec | null) => !!s && /BOND|SUKUK/i.test(s.assetClass || "");

/** Structurally complete — has every value the venue needs. Says nothing about provenance. */
function filled(r: Row): boolean {
  if (!r.accountId || !r.securityId || !r.qty || r.qty <= 0) return false;
  if (r.type === "MARKET") return true;
  if (r.basis === "YIELD") return !!r.orderYield && r.orderYield > 0;
  if (!r.price || r.price <= 0) return false;
  if (r.type.startsWith("STOP") && (!r.stop || r.stop <= 0)) return false;
  return true;
}
/**
 * Law 3: an assumed value is not a decision — but only where no safe default exists.
 *
 * CLIENT and SIDE block the send. Neither has a defensible default: there is no "probably right" BO
 * account among 506, and a side guessed from a blank spreadsheet column is a coin toss on which way
 * the money moves. Both are unrecoverable once filled.
 *
 * PRICE deliberately does NOT block. Seeding the ticket from the last traded price is what every
 * trading terminal does and what a dealer expects; demanding confirmation on every row would be
 * non-standard and would train people to click past the warning — which is worse than no warning.
 * A seeded price is shown in amber italics so it is visibly inherited, and a genuinely wrong one is
 * caught downstream by the RMS price band and fat-finger checks rather than by nagging here.
 */
function confirmed(r: Row): boolean {
  return r.accountProv === "confirmed" && r.sideProv === "confirmed";
}
/**
 * Combinations the venue would take but that mean nothing, or mean something the trader did not
 * intend. Caught at entry rather than at the exchange, because a rejection that arrives from nFIX
 * thirty seconds later costs a trader their place in the queue and tells them far less.
 *
 * Note the backend does NOT check these — OrderService.setValidity simply uppercases whatever it is
 * given — so without this the contradiction goes out on the wire.
 */
function conflicts(r: Row, sec?: Sec | null): string[] {
  const out: string[] = [];
  if (r.type === "MARKET" && r.validity !== "DAY") {
    out.push(`A market order fills immediately, so ${r.validity} has nothing to be good till`);
  }
  if (r.type === "MARKET" && r.basis === "YIELD") {
    out.push("A yield is a price instruction; it cannot be combined with a market order");
  }
  if (r.window === "ODD_LOT" && sec) {
    // Every DSE equity currently has a market lot of 1, so an odd lot is a contradiction in terms.
    out.push(`${sec.symbol} trades in single shares, so there is no odd lot — use the normal market`);
  }
  if (r.window === "BLOCK" && r.qty && r.price && r.qty * r.price < 500000) {
    out.push("Block market has a Tk 5,00,000 floor — below it the trade belongs in the normal market");
  }
  if (r.basis === "YIELD" && sec && !isBond(sec)) {
    out.push(`${sec.symbol} is not a debt instrument, so it cannot be entered on a yield basis`);
  }
  return out;
}

/** Law 4 + the bug this replaces: `risk?.pass !== false` let every UNCHECKED row through. */
function sendable(r: Row, sec?: Sec | null): boolean {
  return filled(r) && confirmed(r) && conflicts(r, sec).length === 0 && r.risk?.pass === true && !r.sent?.id;
}

/**
 * Colour a venue status by what it MEANS, not by "did the POST succeed".
 *
 * PENDING_RISK used to render green, which reads as done when it is the opposite: the order has been
 * accepted by us and we are still waiting on the exchange. Green must mean the exchange agreed.
 * In flight is amber, working-on-the-book is cyan, and anything that ended badly is red.
 */
function statusTone(s?: string): string {
  switch ((s || "").toUpperCase()) {
    case "FILLED":                       return "text-bull font-semibold";
    case "PARTIALLY_FILLED": case "PARTIAL": return "text-bull";
    case "OPEN": case "ACCEPTED":        return "text-aurora-cyan";
    case "REJECTED": case "EXCH_REJECTED": return "text-bear font-semibold";
    case "CANCELLED": case "EXPIRED":    return "text-ink-400";
    default:                             return "text-amber-300";   // PENDING_RISK, ROUTING, unknown
  }
}
/** Plain words. "PENDING_RISK" is our internal state name, not something a trader should decode. */
function statusText(s?: string): string {
  switch ((s || "").toUpperCase()) {
    case "PENDING_RISK": return "sending…";
    case "OPEN":         return "working";
    case "FILLED":       return "filled";
    case "PARTIALLY_FILLED": case "PARTIAL": return "part filled";
    case "REJECTED":     return "rejected";
    case "CANCELLED":    return "cancelled";
    case "EXPIRED":      return "expired";
    default:             return (s || "").toLowerCase().replace(/_/g, " ");
  }
}

const num = (s: string): number | null => {
  const v = parseFloat(String(s).replace(/,/g, ""));
  return Number.isFinite(v) ? v : null;
};

/**
 * A named group of controls on the qualifier band.
 *
 * The name is permanent, not focus-revealed: a trader learning the screen must be able to tell which
 * set of pills is the order type and which is the settlement market, and hovering each group to find
 * out is not learning, it is guessing.
 *
 * The label is also drawn in a different VISUAL REGISTER from the values it names — cyan-blue, bold,
 * letterspaced, on its own tinted plate, inside a bordered box that encloses the whole group. An
 * earlier version set the label in the same small grey as the unselected pills, which left the eye
 * to work out that "MARKET" was a heading while "NORMAL SPOT BLOCK" beside it were choices. Label and
 * value must never be separable only by knowing which is which.
 */
function Group({ name, hint, children }: { name: string; hint: string; children: React.ReactNode }) {
  return (
    <span
      title={hint}
      className="flex items-stretch overflow-hidden rounded-md border border-aurora-cyan/25 bg-obsidian-950/40"
    >
      <span className="flex select-none items-center border-r border-aurora-cyan/25 bg-aurora-cyan/[0.12] px-2 text-[12px] font-bold uppercase tracking-[0.12em] text-aurora-cyan">
        {name}
      </span>
      <span className="flex items-center gap-1 px-1.5 py-0.5">{children}</span>
    </span>
  );
}

/**
 * The order grid itself, with no page chrome — so the exact same component serves the /order-grid
 * route, the floating panel that can be opened over any screen, and (later) a dock tab. Extracted
 * rather than duplicated: two copies of an order-entry screen is two places for a validation rule
 * to drift, and the one that drifts is the one that sends the wrong order.
 *
 * `onClose` is only supplied when floating; the routed page passes nothing and shows no close.
 */
export function OrderGridBody({ onClose }: { onClose?: () => void }) {
  const [secs, setSecs] = useState<Sec[]>([]);
  const [accts, setAccts] = useState<Acct[]>([]);
  const [rows, setRows] = useState<Row[]>([newRow()]);
  const [lit, setLit] = useState<string | null>(null);
  const [audit, setAudit] = useState(false);
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState("");

  const byId = useMemo(() => new Map(secs.map((s) => [s.securityId, s])), [secs]);
  const secOf = useCallback((r: Row) => (r.securityId ? byId.get(r.securityId) ?? null : null), [byId]);
  const acctById = useMemo(() => new Map(accts.map((a) => [a.id, a])), [accts]);

  const acctItems: ComboItem[] = useMemo(
    () => accts.map((a) => ({ id: a.id, primary: a.boId, secondary: a.name })),
    [accts],
  );
  const secItems: ComboItem[] = useMemo(
    () => secs.map((s) => ({ id: s.securityId, primary: s.symbol, secondary: s.name, extra: s.assetClass })),
    [secs],
  );

  useEffect(() => {
    (async () => {
      const s = getSession();
      try { setSecs((await get<Sec[]>("/api/market/watch?exchange=DSE")) || []); } catch {}
      try {
        setAccts((await get<Acct[]>(s?.brokerId ? `/api/accounts?brokerId=${s.brokerId}` : "/api/accounts")) || []);
      } catch {}
      // Light the first row explicitly. Creating the row and hoping a later effect lights it does
      // not work: the effect only fires while `lit` is null, and by then `lit` holds the key of the
      // throwaway row from the initial useState — a key no row has any more. The result was a grid
      // whose entire second line never appeared until the trader happened to click a row.
      const first = newRow();
      setRows([first]);
      setLit(first.key);
    })();
  }, []);

  // Safety net: if the lit key ever stops matching a live row, light the first one.
  useEffect(() => {
    if (rows.length && !rows.some((r) => r.key === lit)) setLit(rows[0].key);
  }, [rows, lit]);

  // F2 toggles audit mode — 22px rows, band suppressed, for checking a batch before Send All.
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "F2") { e.preventDefault(); setAudit((a) => !a); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  const patch = useCallback((key: string, p: Partial<Row>) => {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...p, risk: null, sent: null } : r)));
  }, []);

  const addRow = (after?: string) =>
    setRows((rs) => {
      const r = newRow();
      const i = after ? rs.findIndex((x) => x.key === after) : -1;
      const out = i < 0 ? [...rs, r] : [...rs.slice(0, i + 1), r, ...rs.slice(i + 1)];
      setLit(r.key);
      return out;
    });

  const removeRow = (key: string) =>
    setRows((rs) => (rs.length === 1 ? [newRow()] : rs.filter((r) => r.key !== key)));

  /**
   * Drop rows that have been sent. Once an order is at the venue this screen has nothing left to say
   * about it — its life is tracked in the blotter on the Trader Terminal — and leaving it here just
   * crowds the next batch and makes the ready/held counts harder to read. Never touches unsent work.
   */
  const clearSent = () =>
    setRows((rs) => {
      const keep = rs.filter((r) => !r.sent?.id);
      return keep.length ? keep : [newRow()];
    });

  const pickSecurity = (key: string, id: number | null) => {
    const sec = id ? byId.get(id) : null;
    setRows((rs) =>
      rs.map((r) => {
        if (r.key !== key) return r;
        if (!sec) return { ...r, securityId: null, symbolText: "", risk: null, sent: null };
        return {
          ...r,
          securityId: sec.securityId,
          symbolText: sec.symbol,
          // Seed the price from LTP but mark it DEFAULTED — the trader must confirm it (Ctrl+K or type).
          price: r.priceProv === "confirmed" ? r.price : sec.ltp || null,
          priceProv: r.priceProv === "confirmed" ? "confirmed" : "defaulted",
          basis: isBond(sec) ? r.basis : "PRICE",
          orderYield: isBond(sec) ? r.orderYield : null,
          risk: null, sent: null,
        };
      }),
    );
  };

  // ---------------------------------------------------------------- per-row risk
  const timers = useRef<Record<string, any>>({});
  useEffect(() => {
    for (const r of rows) {
      if (r.sent?.id || r.risk || r.checking || !filled(r)) continue;
      clearTimeout(timers.current[r.key]);
      timers.current[r.key] = setTimeout(async () => {
        setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, checking: true } : x)));
        try {
          const risk = await post<Risk>("/api/ai/risk-preview", body(r));
          setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, risk, checking: false } : x)));
        } catch (e: any) {
          setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, risk: { pass: false, reason: e.message || "check failed" }, checking: false } : x)));
        }
      }, 320);
    }
  }, [rows]);

  const body = (r: Row) => ({
    accountId: r.accountId, securityId: r.securityId, side: r.side, orderType: r.type,
    tradeWindow: r.window, validity: r.validity, expireDate: null,
    price: r.type === "MARKET" || r.basis === "YIELD" ? null : r.price,
    stopPrice: r.type.startsWith("STOP") ? r.stop : null,
    quantity: r.qty, dealerId: null,
    priceBasis: r.basis, orderYield: r.basis === "YIELD" ? r.orderYield : null,
  });

  const sendRow = async (r: Row) => {
    if (!sendable(r, secOf(r))) return;
    setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, checking: true } : x)));
    try {
      const res = await post<any>("/api/orders", body(r));
      setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, checking: false, sent: { id: res.order?.id, status: res.order?.status } } : x)));
    } catch (e: any) {
      setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, checking: false, sent: { error: e.message || "failed" } } : x)));
    }
  };

  /**
   * Keep sent rows live.
   *
   * POST /api/orders returns the status at the instant of submission, which is almost always the
   * transient PENDING_RISK — the execution report has not come back from the venue yet. Capturing
   * that string and never updating it left every sent row frozen on PENDING_RISK for the rest of the
   * session: three orders that were actually OPEN, FILLED and OPEN at the exchange all read the same,
   * and a genuinely stuck order looked identical to a working one.
   *
   * SSE is the primary channel; the 4s poll is the fallback, because a proxy that buffers event
   * streams turns a live blotter into a dead one silently. Both stop once every sent row is terminal.
   */
  const applyStatus = useCallback((byId: Map<number, string>) => {
    setRows((rs) => {
      let changed = false;
      const next = rs.map((r) => {
        if (!r.sent?.id) return r;
        const st = byId.get(r.sent.id);
        if (st && st !== r.sent.status) { changed = true; return { ...r, sent: { ...r.sent, status: st } }; }
        return r;
      });
      return changed ? next : rs;
    });
  }, []);

  // `connected` drives the Live/Offline badge in the header. Every other screen passes it through;
  // this one did not, so the badge read "Offline" permanently regardless of the real stream state —
  // maximally misleading on the screen where you go to ask why an order is not moving.
  const { connected: _connected } = useLive((type, data) => {
    if (type === "order" && data?.id != null && data?.status) {
      applyStatus(new Map<number, string>([[data.id, data.status]]));
    }
  });

  const rowsRef = useRef<Row[]>(rows);
  useEffect(() => { rowsRef.current = rows; }, [rows]);
  useEffect(() => {
    const TERMINAL = new Set(["FILLED", "CANCELLED", "REJECTED", "EXPIRED"]);
    const t = setInterval(async () => {
      const pending = rowsRef.current.filter((r) => r.sent?.id && !TERMINAL.has(r.sent.status || ""));
      if (!pending.length) return;
      try {
        const ids = new Set(pending.map((r) => r.sent!.id));
        const list = await get<any[]>("/api/orders");
        const m = new Map<number, string>();
        (list || []).forEach((o: any) => { if (ids.has(o.id)) m.set(o.id, o.status); });
        if (m.size) applyStatus(m);
      } catch { /* transient: the next tick retries */ }
    }, 4000);
    return () => clearInterval(t);
  }, [applyStatus]);

  const sendMany = async (which: Row[]) => {
    const ready = which.filter((r) => sendable(r, secOf(r)));
    const held = which.length - ready.length;
    if (!ready.length) { setToast(`Nothing sent — ${held} row(s) unconfirmed, incomplete or blocked.`); return; }
    setBusy(true); setToast("");
    for (const r of ready) await sendRow(r);
    setBusy(false);
    setToast(`Sent ${ready.length}${held ? ` · held back ${held}` : ""}.`);
  };

  // ---------------------------------------------------------------- paste
  const onPaste = (e: React.ClipboardEvent, atKey: string) => {
    const text = e.clipboardData.getData("text/plain");
    if (!text || !/[\t\n]/.test(text)) return;
    e.preventDefault();
    const lines = text.replace(/\r/g, "").split("\n").filter((l) => l.trim());
    const bySym = new Map(secs.map((s) => [s.symbol.toUpperCase(), s]));

    const parsed = lines.map((line) => {
      const c = line.split(/\t|,/).map((x) => x.trim().replace(/^"|"$/g, ""));
      const [sym, side, type, qty, price] = c;
      const hit = bySym.get((sym || "").toUpperCase());
      const t = (type || "").toUpperCase().replace(/[\s-]/g, "_");
      const sideRaw = (side || "").toUpperCase();
      // A blank or column-shifted Side must NOT silently become BUY: forty confident buys with a
      // flawless green batch is the worst thing this screen could produce. Unrecognised => unset.
      const knownSide = sideRaw.startsWith("S") ? "SELL" : sideRaw.startsWith("B") ? "BUY" : null;
      return newRow({
        // Client is deliberately NOT seeded on paste. It must be chosen per row or bulk-applied.
        securityId: hit ? hit.securityId : null,
        symbolText: hit ? hit.symbol : (sym || "").toUpperCase(),
        side: knownSide ?? "BUY",
        sideProv: knownSide ? "confirmed" : "unset",
        type: (["LIMIT", "MARKET", "STOP", "STOP_LIMIT"] as string[]).includes(t) ? (t as Row["type"]) : "LIMIT",
        qty: num(qty || ""),
        price: num(price || "") ?? (hit ? hit.ltp : null),
        priceProv: num(price || "") != null ? "confirmed" : "defaulted",
      });
    });

    setRows((rs) => {
      const i = rs.findIndex((r) => r.key === atKey);
      const at = i < 0 ? rs.length : i;
      return [...rs.slice(0, at), ...parsed, ...rs.slice(at + 1)];
    });
    const noSide = parsed.filter((p) => p.sideProv === "unset").length;
    setToast(
      `Pasted ${parsed.length} row(s). Client is unset on every row — set it before sending.` +
      (noSide ? ` ${noSide} row(s) had no recognisable Buy/Sell.` : ""),
    );
  };

  /** Apply one field to every checked row — the fast path after a paste. */
  const bulk = (p: Partial<Row>) =>
    setRows((rs) => rs.map((r) => (r.sel && !r.sent?.id ? { ...r, ...p, risk: null } : r)));

  const totals = useMemo(() => {
    let buy = 0, sell = 0, ready = 0, held = 0, sent = 0;
    for (const r of rows) {
      const sec = r.securityId ? byId.get(r.securityId) : null;
      const px = r.type === "MARKET" ? sec?.ltp || 0 : r.price || 0;
      const v = px * (r.qty || 0);
      if (r.side === "BUY") buy += v; else sell += v;
      if (r.sent?.id) sent++;
      else if (sendable(r, secOf(r))) ready++;
      else if (filled(r)) held++;
    }
    return { buy, sell, net: buy - sell, ready, held, sent };
  }, [rows, byId, secOf]);

  const selCount = rows.filter((r) => r.sel).length;
  const H = audit ? "h-[22px]" : "h-[28px]";

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="relative flex h-full min-h-0 flex-col gap-2 p-3">
        {/* toolbar */}
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-line bg-obsidian-900/60 px-3 py-2">
          <div className="text-[11px] text-ink-300">
            <span className="uppercase tracking-wider text-ink-400">Multi-order entry</span>
            <span className="ml-2 text-ink-400">
              paste from Excel · <kbd className="rounded bg-white/10 px-1">Enter</kbd> commits &amp; opens next ·{" "}
              <kbd className="rounded bg-white/10 px-1">F2</kbd> review before sending
            </span>
          </div>
          <div className="ml-auto flex flex-wrap items-center gap-2">
            {selCount > 0 && (
              <div className="flex items-center gap-1 rounded-lg border border-line px-2 py-1">
                <span className="text-[10px] font-semibold uppercase tracking-wider text-ink-300">{selCount} sel</span>
                <SegGroup label="Bulk side" segs={SIDE_SEGS} value="" defaultValue=""
                  onChange={(v) => bulk({ side: v as Row["side"], sideProv: "confirmed" })} />
                <SegGroup label="Bulk window" segs={WINDOW_SEGS} value="" defaultValue=""
                  onChange={(v) => bulk({ window: v })} />
              </div>
            )}
            <button
              onClick={() => setAudit((a) => !a)}
              title="Compact review: shrink every row and hide the editing band so a long batch fits on one screen, and anything not ready to send is called out. Also F2."
              className={`rounded-lg border px-3 py-1.5 text-[12px] ${audit ? "border-aurora-cyan/60 bg-aurora-cyan/20 font-semibold text-aurora-cyan" : "border-line text-ink-300 hover:bg-white/5"}`}>
              {audit ? "✓ Review mode" : "Review"}
            </button>
            <button onClick={() => addRow()} className="rounded-lg border border-line px-3 py-1.5 text-[12px] text-ink-200 hover:bg-white/5">+ Row</button>
            <button
              disabled={!totals.sent}
              onClick={clearSent}
              title="Remove rows already sent. Their live status is in the blotter on the Trader Terminal — keeping them here only crowds the next batch."
              className="rounded-lg border border-line px-3 py-1.5 text-[12px] text-ink-200 disabled:opacity-40 hover:bg-white/5">
              Clear sent ({totals.sent})
            </button>
            <button disabled={busy || !selCount} onClick={() => sendMany(rows.filter((r) => r.sel))}
              className="rounded-lg border border-aurora-indigo/40 bg-aurora-indigo/15 px-3 py-1.5 text-[12px] text-ink-100 disabled:opacity-40">
              Send selected
            </button>
            <button disabled={busy || !totals.ready} onClick={() => sendMany(rows)}
              className="rounded-lg bg-gradient-to-r from-aurora-violet to-aurora-indigo px-4 py-1.5 text-[12px] font-semibold text-white disabled:opacity-40">
              {busy ? "Sending…" : `Send all (${totals.ready})`}
            </button>
          </div>
        </div>

        {/* Review mode banner. Compacting rows is a 6px change that is invisible on a short list —
            the mode looked broken when pressed with three rows on screen. What a trader actually
            wants before Send All is the answer to "what will and will not go", so say it outright. */}
        {audit && (
          <div className="flex flex-wrap items-center gap-3 rounded-lg border border-aurora-cyan/30 bg-aurora-cyan/[0.07] px-3 py-2 text-[12px]">
            <span className="font-semibold uppercase tracking-wider text-aurora-cyan">Review</span>
            <span className="text-bull">{totals.ready} will send</span>
            {totals.held > 0 && (
              <span className="text-amber-300">
                {totals.held} held — needs client &amp; side confirmed, or blocked by risk
              </span>
            )}
            {rows.filter((r) => !r.sent?.id && !filled(r)).length > 0 && (
              <span className="text-ink-400">
                {rows.filter((r) => !r.sent?.id && !filled(r)).length} incomplete
              </span>
            )}
            {totals.sent > 0 && <span className="text-ink-300">{totals.sent} already sent</span>}
            <span className="ml-auto text-ink-400">rows compacted · editing band hidden · F2 to exit</span>
          </div>
        )}

        {/* column header — fixed grid template shared by every row */}
        <div className="flex items-center gap-2 rounded-t-lg border-x border-t border-line bg-obsidian-850/95 px-2 py-1.5 text-[10px] font-semibold uppercase tracking-[0.12em] text-ink-300">
          <span className="w-[4px]" /><span className="w-[16px]" /><span className="w-[22px] text-right">#</span>
          <span className="w-[210px]">BO / Client</span>
          <span className="w-[190px]">Instrument</span>
          <span className="w-[104px]">Side</span>
          <span className="w-[74px] text-right">Qty</span>
          <span className="w-[84px] text-right">Price</span>
          <span className="w-[210px]">Order terms</span>
          <span className="w-[104px] text-right">Value</span>
          <span className="w-[130px]">Risk</span>
          <span className="w-[54px]" />
        </div>

        {/* the ledger */}
        <div className="min-h-0 flex-1 overflow-auto rounded-b-lg border border-line bg-obsidian-900/40">
          {rows.map((r, i) => {
            const sec = r.securityId ? byId.get(r.securityId) : null;
            const bond = isBond(sec);
            const acct = r.accountId ? acctById.get(r.accountId) : null;
            const isLit = lit === r.key && !audit;
            const locked = !!r.sent?.id;
            const px = r.type === "MARKET" ? sec?.ltp || 0 : r.price || 0;
            const value = px * (r.qty || 0);
            const blocked = r.risk?.pass === false;
            const rowConflicts = conflicts(r, sec);

            // ORDER TERMS. Every term is always spelled out, so a trader learning the screen can read
            // what the order actually is without opening anything. Revised from an earlier version
            // that printed nothing for default values: silence scans beautifully for an expert and is
            // unreadable for a novice, who cannot tell an unset field from a defaulted one. Defaults
            // are therefore QUIET (dim, lower-case) rather than INVISIBLE, and anything the trader
            // changed away from the default is bright and hued — so exceptions still jump out.
            const terms: { t: string; c: string; title: string }[] = [];
            terms.push(
              r.type === DEF.type
                ? { t: "limit", c: "text-ink-400", title: "Order type: Limit (default)" }
                : { t: r.type === "STOP_LIMIT" ? "STOP-LIMIT" : r.type, c: "text-aurora-cyan font-semibold", title: `Order type: ${r.type}` },
            );
            terms.push(
              r.window === DEF.window
                ? { t: "normal", c: "text-ink-400", title: "Market: Normal / public market (default)" }
                : { t: r.window.replace("_", "-"), c: "text-aurora-teal font-semibold", title: `Market: ${r.window}` },
            );
            terms.push(
              r.validity === DEF.validity
                ? { t: "day", c: "text-ink-400", title: "Validity: Day (default)" }
                : { t: r.validity, c: "text-aurora-violet font-semibold", title: `Validity: ${r.validity}` },
            );
            if (bond)
              terms.push(
                r.basis === "YIELD"
                  ? { t: `yield ${nf(r.orderYield || 0, 3)}%`, c: "text-aurora-cyan font-semibold", title: "Entered on yield basis" }
                  : { t: "price basis", c: "text-ink-400", title: "Entered on clean-price basis (default)" },
              );
            if (r.type.startsWith("STOP") && r.stop)
              terms.push({ t: `stop ${nf(r.stop, 2)}`, c: "text-aurora-teal font-semibold", title: `Stop trigger ${nf(r.stop, 2)}` });

            return (
              <div key={r.key}
                onClick={() => !audit && setLit(r.key)}
                className={`border-b border-line/40 transition-colors ${
                  blocked ? "bg-bear/[0.06]"
                    // In review mode a row that will NOT send is the thing worth seeing, so tint it.
                    : audit && !r.sent?.id && filled(r) && !sendable(r, sec) ? "bg-amber-400/[0.07]"
                    : isLit ? "bg-white/[0.035]" : "hover:bg-white/[0.02]"
                } ${locked ? "opacity-60" : ""}`}>

                {/* ---------------- line 1 : the ledger line ---------------- */}
                <div className={`flex items-center gap-2 px-2 ${H}`}>
                  {/* side rail — solid = BUY, hatched = SELL, dashed amber = unconfirmed */}
                  <span className={`h-full w-[4px] shrink-0 rounded-[2px] ${
                    r.sideProv !== "confirmed" ? "border-l-2 border-dashed border-amber-400/80"
                      : locked ? "bg-aurora-indigo" : r.side === "BUY" ? "bg-bull" : "bg-bear"}`}
                    style={r.sideProv === "confirmed" && r.side === "SELL" && !locked ? {
                      backgroundImage: "repeating-linear-gradient(45deg, transparent 0 3px, rgb(255 255 255 / 0.30) 3px 6px)",
                    } : undefined} />

                  <input type="checkbox" className="w-[16px] shrink-0 accent-aurora-cyan" checked={r.sel} disabled={locked}
                    onClick={(e) => e.stopPropagation()}
                    onChange={(e) => setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, sel: e.target.checked } : x)))} />

                  <span className="w-[22px] shrink-0 text-right text-[11px] tabular-nums text-ink-400">{i + 1}</span>

                  {/* client — all 13 BO digits always render; family accounts differ in the middle */}
                  <div className="w-[210px] shrink-0" onClick={(e) => e.stopPropagation()}>
                    {locked ? (
                      <span className="text-[12px] text-ink-200">{acct?.boId} · {acct?.name}</span>
                    ) : (
                      <ComboBox items={acctItems} value={r.accountId} placeholder="BO or name…"
                        className={r.accountProv !== "confirmed" ? "rounded ring-1 ring-dashed ring-amber-400/70" : ""}
                        onChange={(id) => patch(r.key, { accountId: id, accountProv: id ? "confirmed" : "unset" })} />
                    )}
                  </div>

                  {/* instrument */}
                  <div className="w-[190px] shrink-0" onClick={(e) => e.stopPropagation()}>
                    {locked ? (
                      <span className="text-[12px] text-ink-200">{sec?.symbol}</span>
                    ) : (
                      <ComboBox items={secItems} value={r.securityId} placeholder="ticker or name…"
                        onChange={(id) => pickSecurity(r.key, id)} />
                    )}
                  </div>

                  {/* side — one click, always visible, never a dropdown */}
                  <div className="w-[104px] shrink-0" onClick={(e) => e.stopPropagation()}>
                    <SegGroup label="Side" size="md" segs={SIDE_SEGS} value={r.side} showDigits
                      unconfirmed={r.sideProv !== "confirmed"} disabled={locked}
                      onChange={(v) => patch(r.key, { side: v as Row["side"], sideProv: "confirmed" })} />
                  </div>

                  {/* qty — type=text so arrow keys navigate rows instead of decrementing the value */}
                  <input value={r.qty ?? ""} disabled={locked} inputMode="decimal"
                    onClick={(e) => e.stopPropagation()}
                    onPaste={(e) => onPaste(e, r.key)}
                    onChange={(e) => patch(r.key, { qty: num(e.target.value) })}
                    className="w-[74px] shrink-0 rounded bg-transparent px-1 text-right text-[12px] tabular-nums text-ink-100 outline-none focus:bg-white/[0.06]" />

                  {/* price — ~ marks a derived value that is not what gets transmitted */}
                  <div className="relative w-[84px] shrink-0" onClick={(e) => e.stopPropagation()}>
                    {r.basis === "YIELD" && <span className="absolute -left-1 top-1 text-[10px] text-aurora-cyan">~</span>}
                    <input
                      value={r.type === "MARKET" ? "" : r.basis === "YIELD" ? nf(r.price || 0, 2) : r.price ?? ""}
                      disabled={locked || r.type === "MARKET" || r.basis === "YIELD"} inputMode="decimal"
                      placeholder={r.type === "MARKET" ? "mkt" : ""}
                      title={r.priceProv === "defaulted" ? "Seeded from the last traded price — edit it or leave it" : undefined}
                      onChange={(e) => patch(r.key, { price: num(e.target.value), priceProv: "confirmed" })}
                      className={`w-full rounded bg-transparent px-1 text-right text-[12px] tabular-nums outline-none focus:bg-white/[0.06] disabled:text-ink-600
                        ${r.priceProv === "defaulted" && r.type !== "MARKET" ? "italic text-amber-300/90" : "text-ink-100"}`} />
                  </div>

                  {/* order terms — always spelled out; defaults quiet, changes bright */}
                  <span className="flex w-[210px] shrink-0 items-center gap-1 overflow-hidden whitespace-nowrap">
                    {terms.map((f, k) => (
                      <span key={k} title={f.title} className={`text-[11px] ${f.c}`}>
                        {k > 0 && <span className="mr-1 text-ink-500">·</span>}
                        {f.t}
                      </span>
                    ))}
                  </span>

                  <span className="w-[104px] shrink-0 text-right text-[12px] tabular-nums text-ink-100">
                    {value ? money(value) : ""}
                  </span>

                  {/* risk — fixed width so a verdict never moves anything */}
                  <span className="w-[130px] shrink-0 truncate text-[11px]" title={r.risk?.reason || ""}>
                    {locked ? (
                      <span className="text-ink-300">
                        #{r.sent!.id} · <span className={statusTone(r.sent!.status)}>{statusText(r.sent!.status)}</span>
                      </span>
                    ) : r.sent?.error ? <span className="text-bear">✕ {r.sent.error}</span>
                      : r.checking ? <span className="text-ink-300">◐ checking</span>
                      : !filled(r) ? <span className="text-ink-400">◌ incomplete</span>
                      : rowConflicts.length ? <span className="text-bear" title={rowConflicts.join(" · ")}>✕ {rowConflicts[0]}</span>
                      : !confirmed(r) ? <span className="text-amber-400">⚠ unconfirmed</span>
                      : r.risk === null ? <span className="text-ink-300">○ unchecked</span>
                      : r.risk.pass ? <span className="text-bull">✓ pass {nf(r.risk.score || 0, 0)}</span>
                      : <span className="text-bear">✕ {r.risk.reason}</span>}
                  </span>

                  <span className="flex w-[54px] shrink-0 justify-end gap-1" onClick={(e) => e.stopPropagation()}>
                    <button disabled={!sendable(r, sec)} onClick={() => sendRow(r)}
                      className="rounded border border-line px-1 text-[10px] text-ink-300 disabled:opacity-25 hover:bg-white/5">↵</button>
                    <button onClick={() => removeRow(r.key)}
                      className="rounded border border-line px-1 text-[10px] text-ink-500 hover:text-bear">✕</button>
                  </span>
                </div>

                {/* ---------------- line 2 : the qualifier band ----------------
                    Every group carries a permanent name. An unlabelled row of pills is readable only
                    to someone who already knows this screen — a trader meeting it for the first time
                    sees "NORMAL SPOT BLOCK ODD-LOT FOREIGN" and has no way to learn that those are
                    settlement markets rather than order types. The label is not decoration. */}
                {isLit && !locked && (
                  <div className="flex min-h-[34px] flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-aurora-cyan/10 bg-obsidian-950/25 px-2 py-1.5 pl-[46px]">
                    <span className="-ml-3 select-none text-ink-500">╰</span>

                    {bond && (
                      <Group name="Price basis" hint="Bonds may be entered as a clean price or as a yield (DSE BRS §1.1)">
                        <SegGroup label="Price basis" segs={BASIS_SEGS} value={r.basis} defaultValue="PRICE"
                          onChange={(v) => patch(r.key, { basis: v as Row["basis"] })} />
                        {r.basis === "YIELD" && (
                          <input value={r.orderYield ?? ""} inputMode="decimal" placeholder="yield %"
                            onChange={(e) => patch(r.key, { orderYield: num(e.target.value) })}
                            className="ml-1 w-[70px] rounded bg-white/[0.06] px-1 text-right text-[11px] tabular-nums text-ink-100 outline-none focus:bg-white/[0.1]" />
                        )}
                      </Group>
                    )}

                    <Group name="Order type" hint="Limit = your price or better. Market = fill at the best available price.">
                      <SegGroup label="Order type" segs={TYPE_SEGS} value={r.type} defaultValue="LIMIT" showDigits
                        onChange={(v) =>
                          patch(r.key, v === "MARKET"
                            // A market order fills at once: GTC/GTD/GTS and a yield basis become
                            // meaningless, so correct them here rather than reject the order later.
                            ? { type: "MARKET", validity: "DAY", basis: "PRICE", orderYield: null }
                            : { type: v as Row["type"] })
                        } />
                      {r.type.startsWith("STOP") && (
                        <input value={r.stop ?? ""} inputMode="decimal" placeholder="trigger"
                          onChange={(e) => patch(r.key, { stop: num(e.target.value) })}
                          className="ml-1 w-[68px] rounded bg-white/[0.06] px-1 text-right text-[11px] tabular-nums text-ink-100 outline-none focus:bg-white/[0.1]" />
                      )}
                    </Group>

                    <Group name="Market" hint="Which DSE market the order trades in. Normal is the public market, T+2.">
                      <SegGroup label="Market" segs={WINDOW_SEGS} value={r.window} defaultValue="NORMAL" showDigits
                        onChange={(v) => patch(r.key, { window: v })} />
                    </Group>

                    <Group name="Validity" hint="How long the order lives. Day expires at the close.">
                      <SegGroup label="Validity" segs={VALID_SEGS} value={r.validity} defaultValue="DAY" showDigits
                        onChange={(v) => patch(r.key, { validity: v })} />
                    </Group>

                    <span className="ml-auto flex items-center gap-3 text-[12px] text-ink-400">
                      {/* Buying power of the CHOSEN client. Without it a trader sizes an order blind
                          and finds out it was too big only when the RMS rejects it. Shown against the
                          order's own value so the comparison needs no arithmetic. */}
                      {acct && (
                        <span
                          title={`${acct.name} — cash ${money(acct.cashBalance ?? 0)}`}
                          className="flex items-baseline gap-1.5 rounded-md border border-line px-2 py-0.5"
                        >
                          <span className="text-[11px] uppercase tracking-wider text-ink-400">Buying power</span>
                          <span className={`text-[14px] font-semibold tabular-nums ${
                            r.side === "BUY" && value > (acct.buyingPower ?? 0) ? "text-bear" : "text-bull"
                          }`}>
                            {money(acct.buyingPower ?? 0)}
                          </span>
                          {r.side === "BUY" && value > 0 && (
                            <span className={`text-[11px] tabular-nums ${
                              value > (acct.buyingPower ?? 0) ? "text-bear" : "text-ink-400"
                            }`}>
                              · after {money((acct.buyingPower ?? 0) - value)}
                            </span>
                          )}
                        </span>
                      )}
                      {sec && (
                        <span className="flex items-baseline gap-1.5">
                          <span className="text-[11px] uppercase tracking-wider text-ink-400">LTP</span>
                          <span className="text-[15px] font-semibold tabular-nums text-ink-100">{nf(sec.ltp)}</span>
                        </span>
                      )}
                      {sec && r.priceProv === "defaulted" && r.type !== "MARKET" && (
                        <span className="text-[11px] italic text-amber-300/80">price seeded from LTP</span>
                      )}
                    </span>
                  </div>
                )}
              </div>
            );
          })}
        </div>

        {/* totals */}
        <div className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-obsidian-900/60 px-3 py-2 text-[11px]">
          <span className="text-ink-400">Rows <span className="tabular-nums text-ink-200">{rows.length}</span></span>
          <span className="text-bull">Ready <span className="tabular-nums">{totals.ready}</span></span>
          {totals.held > 0 && <span className="text-amber-400">Held <span className="tabular-nums">{totals.held}</span></span>}
          {totals.sent > 0 && <span className="text-ink-300">Sent <span className="tabular-nums">{totals.sent}</span></span>}
          <span className="ml-auto text-ink-400">Buy <span className="tabular-nums text-bull">{money(totals.buy)}</span></span>
          <span className="text-ink-400">Sell <span className="tabular-nums text-bear">{money(totals.sell)}</span></span>
          <span className="text-ink-400">Net <span className={`tabular-nums ${totals.net >= 0 ? "text-bull" : "text-bear"}`}>{money(Math.abs(totals.net))}</span></span>
        </div>

        {/* toast — absolutely positioned so a message never costs a pixel of layout */}
        {toast && (
          <div className="absolute bottom-4 right-4 z-40 max-w-[420px] rounded-lg border border-line bg-obsidian-850 px-3 py-2 text-[11px] text-ink-200 shadow-2xl">
            {toast}
            <button onClick={() => setToast("")} className="ml-3 text-ink-500 hover:text-ink-200">✕</button>
          </div>
        )}
      </div>
    </div>
  );
}
