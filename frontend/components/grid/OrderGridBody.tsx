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
import { OrderDepthPanel } from "@/components/grid/OrderDepthPanel";
import { useLive } from "@/lib/useLive";
import { get, post } from "@/lib/api";
import { getSession } from "@/lib/session";
import { nf, money } from "@/lib/format";
// The SAME grammar the AI Order Bot uses. Shared rather than reimplemented, so a trader who
// learns "B 100 GP @ 120" on one screen has not learnt a dialect that only works there.
import { parseCommand, COMMAND_EXAMPLES } from "@/lib/orderCommand";

/**
 * Column widths, in one place, because the header and the row cells must agree and nothing enforces
 * it. COMPACT is the floating panel: it hovers over the book the dealer is trading against, so every
 * pixel it does not take is a pixel of market they can still see.
 */
const W = {
  compact: {
    rail: "w-[3px]", check: "w-[14px]", ord: "w-[18px]",
    client: "w-[122px]", ticker: "w-[80px]", side: "w-[46px]",
    type: "w-[42px]", validity: "w-[46px]", qty: "w-[60px]", price: "w-[66px]",
    // SEND is a word, not a glyph, and three icon buttons follow it — 64px clipped it.
    risk: "w-[70px]", act: "w-[98px]",
  },
  full: {
    rail: "w-[4px]", check: "w-[16px]", ord: "w-[22px]",
    client: "w-[210px]", ticker: "w-[190px]", side: "w-[104px]",
    type: "w-[72px]", validity: "w-[76px]", qty: "w-[74px]", price: "w-[84px]",
    risk: "w-[130px]", act: "w-[76px]",
  },
};

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
 * A numeric cell that lets you type a decimal point.
 *
 * Binding the input straight to a parsed number cannot work: typing "301." parses to 301, the value
 * re-renders as "301", the dot is gone, and the next keystroke gives "3014". 301.40 became 30140 and
 * WAS SENT to the exchange — a limit price a hundred times too high, passing the pre-trade check
 * because it is a perfectly valid number.
 *
 * So the text being typed is held here, verbatim, and only the parsed value flows outward. The
 * external value re-syncs whenever this cell is not the one being edited, so paste, command entry
 * and LTP seeding all still land.
 *
 * Also selects on focus: the price arrives pre-seeded from the last traded price, and without that
 * the caret sits at 0 and typing PREPENDS — seed 300, type 301.2, get 300301.2.
 */
function NumCell({
  value, onChange, integer, disabled, className, title, placeholder, inputProps, onPaste,
}: {
  value: number | null;
  onChange: (v: number | null) => void;
  integer?: boolean;
  disabled?: boolean;
  className?: string;
  title?: string;
  placeholder?: string;
  inputProps?: Record<string, any>;
  onPaste?: (e: React.ClipboardEvent<HTMLInputElement>) => void;
}) {
  const [text, setText] = useState(value == null ? "" : String(value));
  const editing = useRef(false);

  useEffect(() => {
    if (!editing.current) setText(value == null ? "" : String(value));
  }, [value]);

  const ok = integer ? /^\d*$/ : /^\d*\.?\d*$/;

  return (
    <input
      {...inputProps}
      value={text}
      disabled={disabled}
      title={title}
      placeholder={placeholder}
      inputMode="decimal"
      autoComplete="off"
      onPaste={onPaste}
      onFocus={(e) => { editing.current = true; e.currentTarget.select(); }}
      onBlur={() => {
        editing.current = false;
        // Tidy a half-typed value on the way out: "301." settles to "301", "" stays empty.
        setText(value == null ? "" : String(value));
      }}
      onChange={(e) => {
        const t = e.target.value.replace(/,/g, "");
        if (!ok.test(t)) return;              // reject the keystroke, keep what was there
        setText(t);
        onChange(t === "" || t === "." ? null : num(t));
      }}
      onClick={(e) => e.stopPropagation()}
      className={className}
    />
  );
}

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
export function OrderGridBody({ onClose, compact = false, seed, onConnected }: {
  onClose?: () => void;
  compact?: boolean;
  /**
   * Reports the SSE connection up to whoever owns the page chrome.
   *
   * The body is the thing holding the stream, but the Live/Offline badge lives in Shell, which the
   * body does not render. Calling useLive again in the page would open a SECOND EventSource per tab
   * — useLive constructs one per call — so the state is lifted instead of duplicated.
   */
  onConnected?: (connected: boolean) => void;
  /** "Trade this" from another screen — see lib/orderIntent. Side is never seeded, only price. */
  seed?: { securityId: number; price?: number | null; side?: "BUY" | "SELL"; nonce?: number } | null;
}) {
  const [secs, setSecs] = useState<Sec[]>([]);
  const [accts, setAccts] = useState<Acct[]>([]);
  const [rows, setRows] = useState<Row[]>([newRow()]);
  const [lit, setLit] = useState<string | null>(null);
  const [audit, setAudit] = useState(false);
  const [busy, setBusy] = useState(false);
  /** Row whose client field should take focus once React has rendered it. */
  const [focusRow, setFocusRow] = useState<string | null>(null);
  /**
   * Compact hides the settings band to stay one line per order. That is right for the 95% of orders
   * that are a plain limit on the normal market — but it must not make the other 5% impossible.
   * This opens the band for ONE row on demand, so market, price-basis and stop stay reachable
   * without turning the whole window back into the full screen.
   */
  const [moreRow, setMoreRow] = useState<string | null>(null);
  /**
   * Show the book beside the order being written. On by default on the full screen, where there is
   * room; off by default in the compact floating window, where there is not — but one click away,
   * because "did my order land on the book" is the question a trader asks straight after sending.
   */
  const [showDepth, setShowDepth] = useState(!compact);
  const [toast, setToast] = useState("");
  // Clear itself after a while. Every message here is informational; none needs to persist, and one
  // that never leaves eventually reads as part of the furniture.
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(""), 6000);
    return () => clearTimeout(t);
  }, [toast]);

  const byId = useMemo(() => new Map(secs.map((s) => [s.securityId, s])), [secs]);
  const secOf = useCallback((r: Row) => (r.securityId ? byId.get(r.securityId) ?? null : null), [byId]);
  const acctById = useMemo(() => new Map(accts.map((a) => [a.id, a])), [accts]);

  const acctItems: ComboItem[] = useMemo(() => {
    if (!compact) return accts.map((a) => ({ id: a.id, primary: a.boId, secondary: a.name }));
    // Compact shows the CLIENT NAME, with the BO in the tooltip. That is only safe because of the
    // suffix below: 501 of the 506 accounts share a name with another account — "Imran Ahmed" alone
    // is nine different BO numbers — so a bare name would offer nine identical rows to choose from.
    // Where a name is unique it stays clean; where it is not, the last four BO digits ride along.
    const seen = new Map<string, number>();
    for (const a of accts) seen.set(a.name, (seen.get(a.name) || 0) + 1);
    // The LIST keeps the disambiguator and the CELL drops it. Those are different moments: choosing
    // is when a wrong account gets picked, so the list must still distinguish nine Imran Ahmeds;
    // once chosen the row is bound to an id, so the cell can be narrow and leave the BO to the
    // tooltip — which is what the product owner asked for, without giving up the safety of the pick.
    return accts.map((a) => ({
      id: a.id,
      primary: (seen.get(a.name) || 0) > 1 ? `${a.name} ·${a.boId.slice(-4)}` : a.name,
      secondary: undefined,
      display: a.name,
      extra: `${a.boId} ${a.name}`,          // still searchable by full BO number
    }));
  }, [accts, compact]);
  const secItems: ComboItem[] = useMemo(
    () => secs.map((s) => compact
      ? { id: s.securityId, primary: s.symbol, secondary: undefined, extra: `${s.name} ${s.assetClass}` }
      : { id: s.securityId, primary: s.symbol, secondary: s.name, extra: s.assetClass }),
    [secs, compact],
  );

  useEffect(() => {
    (async () => {
      const s = getSession();
      try { setSecs((await get<Sec[]>("/api/market/watch?exchange=DSE")) || []); } catch {}
      try {
        setAccts((await get<Acct[]>(s?.brokerId ? `/api/accounts?brokerId=${s.brokerId}` : "/api/accounts")) || []);
      } catch {}
      // Deliberately does NOT reset rows here.
      //
      // It used to do `setRows([newRow()])` after the fetches, which raced the "trade this" seed:
      // securities arriving triggered the seed effect, which filled a row, and this line then threw
      // that row away and replaced it with an empty one — so clicking a price on the depth ladder
      // opened the panel with nothing in it. useState already starts with one row, and the safety
      // net below lights it, so there is nothing left for this to do.
    })();
  }, []);

  // Safety net: if the lit key ever stops matching a live row, light the first one.
  useEffect(() => {
    if (rows.length && !rows.some((r) => r.key === lit)) setLit(rows[0].key);
  }, [rows, lit]);

  /**
   * Absorb a "trade this" intent from another screen. It lands in the first row that has no
   * instrument yet, or a new row if every row is spoken for — never overwriting work in progress.
   * Price arrives as `defaulted`, so it still renders as inherited rather than as a decision.
   */
  useEffect(() => {
    if (!seed?.securityId) return;
    const sec = byId.get(seed.securityId);
    if (!sec) return;
    setRows((rs) => {
      const idx = rs.findIndex((r) => !r.securityId && !r.sent?.id);
      const fill = (r: Row): Row => ({
        ...r,
        securityId: sec.securityId,
        symbolText: sec.symbol,
        price: seed.price ?? sec.ltp ?? null,
        priceProv: "defaulted",
        side: seed.side ?? r.side,
        sideProv: seed.side ? "confirmed" : r.sideProv,
        basis: isBond(sec) ? r.basis : "PRICE",
        risk: null, sent: null,
      });
      if (idx >= 0) {
        const next = [...rs];
        next[idx] = fill(next[idx]);
        setLit(next[idx].key);
        return next;
      }
      const r = fill(newRow());
      setLit(r.key);
      return [...rs, r];
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [seed?.nonce, seed?.securityId, byId]);

  useEffect(() => {
    if (!focusRow) return;
    const el = document.querySelector<HTMLInputElement>(`[data-client-row="${focusRow}"]`);
    el?.focus();
    setFocusRow(null);
  }, [focusRow, rows]);

  /**
   * Ctrl+Enter sends the row being edited.
   *
   * A dealer working by keyboard could fill a row entirely from the keys and then had no way to send
   * it — the only routes were the mouse or seven Tabs across filled fields to reach the button. The
   * guard is `sendable()`, the identical predicate the button uses, so this can never send anything
   * the button would refuse; when it refuses it says why rather than doing nothing. Plain Enter is
   * unchanged (commit and open the next row) because sending must stay a deliberate, distinct act.
   */
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (e.key !== "Enter" || !(e.ctrlKey || e.metaKey)) return;
      const r = rowsRef.current.find((x) => x.key === lit);
      if (!r) return;
      e.preventDefault();
      if (r.sent?.id) { setToast("That row has already been sent."); return; }
      const sec = r.securityId ? byId.get(r.securityId) : null;
      if (!sendable(r, sec)) {
        setToast(!filled(r) ? "Row is incomplete." : !confirmed(r) ? "Choose a client and a side first."
          : r.risk?.pass === false ? `Blocked: ${r.risk.reason}` : "Waiting for the pre-trade check.");
        return;
      }
      sendRow(r);
    };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lit, byId]);

  // F2 toggles audit mode — 22px rows, band suppressed, for checking a batch before Send All.
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "F2") { e.preventDefault(); setAudit((a) => !a); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, []);

  /**
   * KEYBOARD GRID — the dBase/COBOL property, which is that the hands never leave the keys.
   *
   * Every editable cell carries data-cell="<rowKey>:<col>", so focus can be moved by coordinate
   * rather than by tab order. Two things make it fast: ArrowUp/ArrowDown walk a COLUMN (enter every
   * quantity down a list without re-tabbing across), and choosing a value AUTO-ADVANCES to the next
   * field the way a fixed-width terminal advanced when a field filled.
   *
   * Arrows only reach this from the plain text cells. Inside a ComboBox they belong to the option
   * list, which is why qty and price are type=text — a type=number would have eaten them to
   * increment the value, which is also how it silently corrupted quantities before.
   */
  /**
   * RESPONSIVE DENSITY.
   *
   * The row is a line of fixed-width cells, so dragging the panel narrower used to push the action
   * buttons past the right edge and out of sight — the trader could still see the order but had no
   * way to send or delete it. Two things fix that: the least load-bearing columns fold away as space
   * runs out, and the actions are pinned to the right so they are reachable at ANY width.
   *
   * What folds is chosen by what a trader can still see elsewhere. A MARKET order already prints
   * "mkt" in its price cell, and a non-default validity is both in the row tooltip and one click away
   * in the row band — so no VALUE is lost when a CONTROL folds, which is the standing rule here.
   */
  const gridRef = useRef<HTMLDivElement>(null);
  const [gridW, setGridW] = useState(9999);
  useEffect(() => {
    const el = gridRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => setGridW(el.clientWidth));
    ro.observe(el);
    setGridW(el.clientWidth);
    return () => ro.disconnect();
  }, []);

  const showType = compact && gridW >= 560;
  const showValidity = compact && gridW >= 690;
  /** Below this the risk column keeps its glyph and drops its words into the tooltip. */
  const tightRisk = compact && gridW < 500;

  const COLS = compact
    ? ["client", "ticker", "side",
       ...(showType ? ["type"] : []),
       ...(showValidity ? ["validity"] : []),
       "qty", "price"]
    : ["client", "ticker", "side", "qty", "price"];

  /** Column index by name — indices shift as columns fold, so nothing may hard-code them. */
  const ci = (name: string) => Math.max(0, COLS.indexOf(name));

  /** Column widths for this mode. Header and cells read the same object so they cannot drift. */
  const w = compact ? W.compact : W.full;

  const focusCell = useCallback((rowKey: string, col: number) => {
    const c = Math.max(0, Math.min(COLS.length - 1, col));
    // Deferred: the caller is usually mid-render (a pick just changed state), and the target may
    // not exist yet on this tick.
    requestAnimationFrame(() => {
      document.querySelector<HTMLElement>(`[data-cell="${rowKey}:${c}"]`)?.focus();
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [COLS.length]);

  const cellProps = (rowKey: string, col: number) => ({ "data-cell": `${rowKey}:${col}` } as any);

  /**
   * Advance to the first field of this row that still needs a value, not blindly to the next column.
   *
   * When the command box has already supplied ticker, side, quantity and price, walking the trader
   * into those filled cells makes them re-confirm work they just did — and, before the ComboBox fix,
   * appeared to erase it. If nothing is outstanding the row is done, so focus goes nowhere and the
   * trader's next keystroke (Enter) opens the next row.
   */
  const focusNextGap = (rowKey: string, from: number) => {
    /*
     * Deferred, and read from a ref.
     *
     * This runs from a ComboBox's onPicked, which fires in the same tick as the setRows that records
     * the pick — so the `rows` captured by this render still shows the field as empty, and the
     * "first outstanding field" was the one just filled. Focus bounced straight back to the client
     * cell instead of moving on.
     *
     * setTimeout, not requestAnimationFrame: rowsRef is refreshed in a passive effect, and those run
     * AFTER paint while rAF runs before it. A frame callback would read the same stale rows.
     */
    setTimeout(() => focusNextGapNow(rowKey, from), 0);
  };

  const focusNextGapNow = (rowKey: string, from: number) => {
    const r = rowsRef.current.find((x) => x.key === rowKey);
    if (!r) return;
    const missing = (col: number): boolean => {
      switch (COLS[col]) {
        case "client": return r.accountProv !== "confirmed";
        case "ticker": return !r.securityId;
        case "side": return r.sideProv !== "confirmed";
        case "qty": return !r.qty;
        case "price": return r.type !== "MARKET" && r.price == null;
        default: return false;                    // type and validity always carry a workable default
      }
    };
    for (let c = from; c < COLS.length; c++) if (missing(c)) { focusCell(rowKey, c); return; }
    for (let c = 0; c < from; c++) if (missing(c)) { focusCell(rowKey, c); return; }
    /*
     * Nothing outstanding: the row is complete, so offer its SEND.
     *
     * This only takes when the button is enabled, and it is disabled until the pre-trade check comes
     * back — a disabled button cannot take focus, so on a freshly completed row this quietly does
     * nothing. That is why Ctrl+Enter exists (see the panel key handler): it is the keyboard route
     * to sending that does not depend on winning a race with the risk check.
     */
    requestAnimationFrame(() => {
      const btn = document.querySelector<HTMLButtonElement>(`[data-send="${rowKey}"]`);
      if (btn && !btn.disabled) btn.focus();
    });
  };

  /** ArrowUp/ArrowDown move within a column; everything else is left alone. */
  const onGridKey = (e: React.KeyboardEvent, rowKey: string) => {
    if (e.key !== "ArrowUp" && e.key !== "ArrowDown") return;
    const cell = (e.target as HTMLElement)?.getAttribute?.("data-cell");
    if (!cell) return;
    const col = Number(cell.split(":").pop());
    const i = rows.findIndex((r) => r.key === rowKey);
    const j = e.key === "ArrowDown" ? i + 1 : i - 1;
    if (i < 0 || j < 0 || j >= rows.length) return;
    e.preventDefault();
    setLit(rows[j].key);
    focusCell(rows[j].key, col);
  };

  /* ------------------------------------------------------------------ command entry
   *
   * "B 100 GP @ 120" fills the lit row and moves to its client field.
   *
   * IT FILLS. IT DOES NOT SEND. The grammar carries no client, and the whole reason this grid has
   * provenance fields is that an order attached to whichever account happened to be selected is the
   * exact accident that loses money. So the command sets side, quantity, instrument and price —
   * every one of which the trader literally typed, hence `confirmed` — and leaves the account unset,
   * which leaves the row un-sendable until a human picks one. Focus lands there next, so the fast
   * path is: type the order, Enter, type the client, Enter.
   */
  const [cmd, setCmd] = useState("");
  const secMap = useMemo(
    () => new Map(secs.filter((x) => x.assetClass !== "INDEX").map((x) => [x.symbol.toUpperCase(), x.securityId])),
    [secs],
  );
  const cmdParsed = useMemo(() => (cmd.trim() ? parseCommand(cmd, secMap) : null), [cmd, secMap]);

  const applyCommand = () => {
    const p = cmdParsed;
    /*
     * Never fail silently. On a cold panel the instrument list has not arrived, so every command is
     * unparseable — Enter did nothing, focus stayed put, and the dealer's next keystrokes (the
     * client name) were appended onto the command string. Say what is wrong, out loud.
     */
    if (!p) return;
    if (!p.ok) {
      setToast(p.pending ? "Still loading instruments — try again in a moment." : (p.error || "Cannot read that command."));
      return;
    }
    const sec = p.securityId ? byId.get(p.securityId) : null;

    // Land in the lit row when it is still free, otherwise open a new one. Never overwrite an order
    // that already has an instrument — a fast typist should not be able to clobber the row above.
    const target = rows.find((r) => r.key === lit && !r.sent?.id && !r.securityId) ?? null;
    const fill = (r: Row): Row => ({
      ...r,
      securityId: p.securityId ?? null,
      symbolText: p.symbol ?? "",
      side: p.side as Row["side"],
      sideProv: "confirmed",                       // typed as B or S — a decision, not a default
      qty: p.qty ?? null,
      type: p.market ? "MARKET" : "LIMIT",
      price: p.market ? null : p.price ?? null,
      priceProv: p.market ? "unset" : "confirmed",
      validity: p.market ? "DAY" : r.validity,     // MARKET makes anything else meaningless
      basis: isBond(sec) ? r.basis : "PRICE",
      risk: null, sent: null,
    });

    if (target) {
      setRows((rs) => rs.map((r) => (r.key === target.key ? fill(r) : r)));
      setCmd("");
      focusCell(target.key, 0);
      return;
    }
    const fresh = fill(newRow());
    setRows((rs) => [...rs, fresh]);
    setLit(fresh.key);
    setCmd("");
    focusCell(fresh.key, 0);
  };

  /** Multi-line paste — dealers keep instructions in lists, one order per line. */
  const applyCommandLines = (text: string) => {
    const lines = text.split(/[\r\n]+/).map((l) => l.trim()).filter(Boolean);
    if (lines.length < 2) return false;
    const made: Row[] = [];
    for (const line of lines) {
      const p = parseCommand(line, secMap);
      if (!p.ok) continue;                          // an unparseable line is skipped, never guessed
      const sec = p.securityId ? byId.get(p.securityId) : null;
      made.push({
        ...newRow(),
        securityId: p.securityId ?? null, symbolText: p.symbol ?? "",
        side: p.side as Row["side"], sideProv: "confirmed",
        qty: p.qty ?? null,
        type: p.market ? "MARKET" : "LIMIT",
        price: p.market ? null : p.price ?? null,
        priceProv: p.market ? "unset" : "confirmed",
        validity: p.market ? "DAY" : DEF.validity,
        basis: isBond(sec) ? "PRICE" : "PRICE",
      });
    }
    if (!made.length) return false;
    setRows((rs) => [...rs.filter((r) => r.securityId || r.sent?.id), ...made]);
    setLit(made[0].key);
    setCmd("");
    setToast(`${made.length} row${made.length === 1 ? "" : "s"} filled — each still needs a client`);
    focusCell(made[0].key, 0);
    return true;
  };

  const patch = useCallback((key: string, p: Partial<Row>) => {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, ...p, risk: null, sent: null } : r)));
  }, []);

  const addRow = (after?: string): string => {
    const r = newRow();
    setRows((rs) => {
      const i = after ? rs.findIndex((x) => x.key === after) : -1;
      return i < 0 ? [...rs, r] : [...rs.slice(0, i + 1), r, ...rs.slice(i + 1)];
    });
    setLit(r.key);
    return r.key;
  };

  /**
   * Enter commits the row and opens the next one — which the toolbar has been advertising since this
   * screen was built, without it being implemented. A trader working down a list should never have to
   * reach for the mouse to start the next order.
   *
   * On the last row it creates a new one; otherwise it moves to the row below. Either way focus lands
   * in the client field, because that is where entry starts. Enter inside an open combobox never
   * reaches here — ComboBox stops propagation when it is choosing — so picking a client does not
   * accidentally commit the row.
   */
  const commitAndNext = (key: string) => {
    const i = rows.findIndex((r) => r.key === key);
    if (i < 0) return;
    if (i === rows.length - 1) setFocusRow(addRow(key));
    else { setLit(rows[i + 1].key); setFocusRow(rows[i + 1].key); }
  };

  /** Same client, same instrument, different size — the common shape of a working order list. */
  const duplicateRow = (key: string) =>
    setRows((rs) => {
      const i = rs.findIndex((r) => r.key === key);
      if (i < 0) return rs;
      const src = rs[i];
      const copy: Row = {
        ...src,
        key: `r${Date.now()}_${seq++}`,
        sel: false,
        risk: null, checking: false,
        sent: null,          // a duplicate has not been sent, whatever the original did
      };
      setLit(copy.key);
      return [...rs.slice(0, i + 1), copy, ...rs.slice(i + 1)];
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
  const { connected } = useLive((type, data) => {
    if (type === "order" && data?.id != null && data?.status) {
      applyStatus(new Map<number, string>([[data.id, data.status]]));
    }
  });

  useEffect(() => { onConnected?.(connected); }, [connected, onConnected]);

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
    /*
     * "Held back" must mean WE REFUSED IT, not "it went earlier".
     *
     * This counted every non-ready row, so an already-sent, locked row was reported as held back —
     * "Sent 1 · held back 1" after a clean single send, sending the trader hunting for a blocked
     * order that does not exist. Rows that already have an id are simply not candidates.
     */
    const held = which.filter((r) => !r.sent?.id && !sendable(r, secOf(r))).length;
    if (!ready.length) {
      setToast(held ? `Nothing sent — ${held} row(s) unconfirmed, incomplete or blocked.` : "Nothing to send.");
      return;
    }
    setBusy(true); setToast("");
    for (const r of ready) await sendRow(r);
    setBusy(false);
    setToast(`Sent ${ready.length}${held ? ` · ${held} held back` : ""}.`);
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
    let buy = 0, sell = 0, ready = 0, held = 0, sent = 0, unsided = 0;
    for (const r of rows) {
      const sec = r.securityId ? byId.get(r.securityId) : null;
      const px = r.type === "MARKET" ? sec?.ltp || 0 : r.price || 0;
      const v = px * (r.qty || 0);
      /*
       * A row whose side was never chosen is NOT a buy. `side` holds "BUY" as its initial value, so
       * summing on it alone reported the exposure of an order nobody has decided the direction of —
       * the footer read "Buy 12,000" for a row the screen was simultaneously refusing to send as
       * unconfirmed. It is counted separately, or not at all.
       */
      if (r.sideProv !== "confirmed") unsided += v;
      else if (r.side === "BUY") buy += v;
      else sell += v;
      if (r.sent?.id) sent++;
      else if (sendable(r, secOf(r))) ready++;
      else if (filled(r)) held++;
    }
    return { buy, sell, net: buy - sell, ready, held, sent, unsided };
  }, [rows, byId, secOf]);

  const selCount = rows.filter((r) => r.sel).length;
  const litRow = rows.find((r) => r.key === lit) || null;
  const litSec = litRow?.securityId ? byId.get(litRow.securityId) : null;
  const H = audit ? "h-[22px]" : "h-[28px]";

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="relative flex h-full min-h-0 flex-col gap-2 p-3">
        {/* toolbar */}
        <div className="flex flex-wrap items-center gap-2 rounded-xl border border-line bg-obsidian-900/60 px-3 py-2">
          {/* COMMAND ENTRY — the shortest path from an instruction to a filled row. */}
          <div className="relative flex min-w-[240px] flex-1 items-center gap-2">
            <span className="shrink-0 text-[9px] font-bold uppercase tracking-[0.14em] text-aurora-cyan">Cmd</span>
            <input
              value={cmd}
              onChange={(e) => setCmd(e.target.value)}
              onPaste={(e) => {
                const t = e.clipboardData.getData("text");
                if (/[\r\n]/.test(t) && applyCommandLines(t)) e.preventDefault();
              }}
              onKeyDown={(e) => {
                if (e.key === "Enter") { e.preventDefault(); e.stopPropagation(); applyCommand(); }
                if (e.key === "Escape") { e.preventDefault(); setCmd(""); }
              }}
              placeholder="B 100 GP @ 120"
              
              spellCheck={false} autoComplete="off"
              title="Type an order and press Enter to fill a row. It fills only — the client is still yours to choose, and nothing is sent until you press Send (or Ctrl+Enter on the lit row)."
              className={`min-w-0 flex-1 rounded-lg border bg-obsidian-950/60 px-2 py-1 text-[12px] tabular-nums text-ink-100 placeholder:text-ink-500 focus:outline-none focus:ring-1 ${
                !cmd.trim() ? "border-line/70 focus:border-aurora-cyan focus:ring-aurora-cyan/40"
                  : cmdParsed?.ok ? "border-bull/60 focus:ring-bull/40"
                  : "border-amber-400/60 focus:ring-amber-400/40"}`}
            />
            {/* Live verdict, so a mistyped ticker is visible before Enter rather than after. */}
            {cmd.trim() && (
              <span className={`shrink-0 text-[10px] ${cmdParsed?.ok ? "text-bull" : cmdParsed?.pending ? "text-ink-400" : "text-amber-300"}`}>
                {cmdParsed?.ok
                  ? `✓ ${cmdParsed.side === "BUY" ? "Buy" : "Sell"} ${nf(cmdParsed.qty || 0, 0)} ${cmdParsed.symbol} ${cmdParsed.market ? "at market" : `@ ${nf(cmdParsed.price || 0)}`}`
                  : cmdParsed?.error}
              </span>
            )}
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
            <button
              onClick={() => setShowDepth((v) => !v)}
              title="Show the order book for the row you are editing, and where your own orders sit on it"
              className={`rounded-lg border px-3 py-1.5 text-[12px] ${
                showDepth ? "border-aurora-cyan/50 bg-aurora-cyan/15 text-aurora-cyan" : "border-line text-ink-200 hover:bg-white/5"}`}>
              Depth
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

        {/*
          The ledger, with the header INSIDE it.

          The header used to be a sibling of the scroll container, which broke it two ways once the
          panel narrowed: its spans had no shrink-0 so they compressed while the row cells did not
          (labels drifted up to 90px away from their columns), and it could not scroll, so scrolling
          the body slid the cells out from under fixed labels. Inside the same scroller, sticky to
          the top, it shares one horizontal offset and one width with every row.
        */}
        <div ref={gridRef} className="min-h-0 flex-1 overflow-auto rounded-lg border border-line bg-obsidian-900/40">
          <div className={`sticky top-0 z-20 flex w-max min-w-full items-center ${compact ? "gap-1.5" : "gap-2"} border-b border-line bg-obsidian-850 px-2 py-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-ink-200 [&>*]:shrink-0`}>
            <span className={w.rail} /><span className={w.check} /><span className={`${w.ord} text-right`}>#</span>
            <span className={w.client}>Client</span>
            <span className={w.ticker}>Ticker</span>
            <span className={w.side}>Side</span>
            {showType && <span className={w.type}>Type</span>}
            {showValidity && <span className={w.validity}>Valid</span>}
            <span className={`${w.qty} text-right`}>Qty</span>
            {/* pr-2 keeps PRICE off RISK, which collided into one word at the default width */}
            <span className={`${w.price} pr-2 text-right`}>Price</span>
            {!compact && <span className="w-[210px]">Order terms</span>}
            {!compact && <span className="w-[104px] text-right">Value</span>}
            <span className={tightRisk ? "w-[22px]" : `${w.risk} !min-w-[20px] !flex-1 !shrink`}>{tightRisk ? "" : "Risk"}</span>
            <span className={`${w.act} sticky right-0 bg-obsidian-850 pl-1`} />
          </div>

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

            // A folded column must not take its value with it. Anything hidden at this width is
            // spelled out here, so hovering the row always answers "what exactly is this order".
            const foldedBits: string[] = [];
            if (!showType) foldedBits.push(r.type === "MARKET" ? "Market order" : `Type ${r.type}`);
            if (!showValidity) foldedBits.push(`Validity ${r.validity}`);
            const rowTitle = foldedBits.length ? `${foldedBits.join(" · ")} — widen the panel or press ⋯ to edit` : undefined;

            return (
              <div key={r.key}
                title={rowTitle}
                onClick={() => !audit && setLit(r.key)}
                onKeyDown={(e) => {
                  if (e.key !== "Enter" || e.shiftKey) return;
                  e.preventDefault();
                  commitAndNext(r.key);
                }}
                className={`border-b border-line/40 transition-colors ${
                  blocked ? "bg-bear/[0.06]"
                    // In review mode a row that will NOT send is the thing worth seeing, so tint it.
                    : audit && !r.sent?.id && filled(r) && !sendable(r, sec) ? "bg-amber-400/[0.07]"
                    : isLit ? "bg-white/[0.035]" : "hover:bg-white/[0.02]"
                } ${locked ? "opacity-60" : ""}`}>

                {/* ---------------- line 1 : the ledger line ---------------- */}
                <div onKeyDown={(e) => onGridKey(e, r.key)}
                     className={`flex w-max min-w-full items-center ${compact ? "gap-1.5" : "gap-2"} px-2 ${H} [&>*]:shrink-0`}>
                  {/* side rail — solid = BUY, hatched = SELL, dashed amber = unconfirmed */}
                  <span className={`h-full ${w.rail} shrink-0 rounded-[2px] ${
                    r.sideProv !== "confirmed" ? "border-l-2 border-dashed border-amber-400/80"
                      : locked ? "bg-aurora-indigo" : r.side === "BUY" ? "bg-bull" : "bg-bear"}`}
                    style={r.sideProv === "confirmed" && r.side === "SELL" && !locked ? {
                      backgroundImage: "repeating-linear-gradient(45deg, transparent 0 3px, rgb(255 255 255 / 0.30) 3px 6px)",
                    } : undefined} />

                  <input type="checkbox" className={`${w.check} shrink-0 accent-aurora-cyan`} checked={r.sel} disabled={locked}
                    onClick={(e) => e.stopPropagation()}
                    onChange={(e) => setRows((rs) => rs.map((x) => (x.key === r.key ? { ...x, sel: e.target.checked } : x)))} />

                  <span className={`${w.ord} shrink-0 text-right text-[11px] tabular-nums text-ink-400`}>{i + 1}</span>

                  {/* client — all 13 BO digits always render; family accounts differ in the middle */}
                  <div className={`${w.client} shrink-0`}
                       title={acct ? `${acct.boId} · ${acct.name}` : "Client BO account"}
                       onClick={(e) => e.stopPropagation()}>
                    {locked ? (
                      <span className="text-[12px] text-ink-200">{compact ? acct?.name : `${acct?.boId} · ${acct?.name}`}</span>
                    ) : (
                      <ComboBox items={acctItems} value={r.accountId} placeholder={compact ? "client…" : "BO or name…"}
                        inputProps={{ "data-client-row": r.key, ...cellProps(r.key, ci("client")) }}
                        className={r.accountProv !== "confirmed" ? "rounded ring-1 ring-dashed ring-amber-400/70" : ""}
                        onChange={(id) => patch(r.key, { accountId: id, accountProv: id ? "confirmed" : "unset" })}
                        onPicked={() => focusNextGap(r.key, ci("ticker"))} />
                    )}
                  </div>

                  {/* instrument */}
                  <div className={`${w.ticker} shrink-0`}
                       title={sec ? `${sec.symbol} · ${sec.name}${sec.ltp ? ` · LTP ${nf(sec.ltp)}` : ""}` : "Instrument"}
                       onClick={(e) => e.stopPropagation()}>
                    {locked ? (
                      <span className="text-[12px] text-ink-200">{sec?.symbol}</span>
                    ) : (
                      <ComboBox items={secItems} value={r.securityId} placeholder={compact ? "ticker…" : "ticker or name…"}
                        inputProps={{
                          ...cellProps(r.key, ci("ticker")),
                          // The cell is 80px and SQURPHARMA is not; put the full symbol on the input
                          // itself so a clipped ticker can still be confirmed by hovering it.
                          title: sec ? `${sec.symbol} · ${sec.name}` : "Instrument",
                          className: "text-ellipsis",
                        } as any}
                        onChange={(id) => pickSecurity(r.key, id)}
                        onPicked={() => focusNextGap(r.key, ci("side"))} />
                    )}
                  </div>

                  {/*
                    SIDE — two letters, one keystroke.
                    
                    B and S are the product owner's ask and they buy real width, but letter keys were
                    banned from the qualifier band for a good reason: there, "s" means SELL, STOP and
                    SPOT across three adjacent groups. That ambiguity does not exist here. This cell
                    is focusable on its own and has exactly two values, so b and s can only mean one
                    thing each. The ban is narrowed deliberately, not forgotten.

                    Unchosen still reads as unchosen — dashed amber, neither letter filled — because a
                    side nobody picked must never render like a side somebody picked.
                  */}
                  <div className={`${w.side} shrink-0`} onClick={(e) => e.stopPropagation()}>
                    {compact ? (
                      <div
                        {...cellProps(r.key, ci("side"))}
                        tabIndex={locked ? -1 : 0}
                        role="group" aria-label="Side"
                        title={r.sideProv === "confirmed" ? (r.side === "BUY" ? "Buy" : "Sell") : "Side not chosen — press B or S"}
                        onKeyDown={(e) => {
                          if (locked) return;
                          const k = e.key.toLowerCase();
                          if (k !== "b" && k !== "s") return;
                          e.preventDefault();
                          patch(r.key, { side: k === "b" ? "BUY" : "SELL", sideProv: "confirmed" });
                          focusNextGap(r.key, ci("qty"));          // straight on to quantity
                        }}
                        className={`flex overflow-hidden rounded border text-[11px] font-bold focus:outline-none focus:ring-1 focus:ring-aurora-cyan ${
                          r.sideProv !== "confirmed" ? "border-dashed border-amber-400/80" : "border-line/70"}`}>
                        {(["BUY", "SELL"] as const).map((sd) => {
                          const on = r.sideProv === "confirmed" && r.side === sd;
                          return (
                            <button key={sd} type="button" disabled={locked} tabIndex={-1}
                              title={sd === "BUY" ? "Buy" : "Sell"}
                              onClick={() => { patch(r.key, { side: sd, sideProv: "confirmed" }); focusNextGap(r.key, ci("qty")); }}
                              className={`flex-1 py-0.5 transition-colors ${
                                on ? (sd === "BUY" ? "bg-bull text-obsidian-950" : "bg-bear text-obsidian-950")
                                   : r.sideProv !== "confirmed" ? "text-amber-300 hover:bg-white/10"
                                   : "text-ink-400 hover:bg-white/10"}`}>
                              {sd === "BUY" ? "B" : "S"}
                            </button>
                          );
                        })}
                      </div>
                    ) : (
                      <SegGroup label="Side" size="md" segs={SIDE_SEGS} value={r.side} showDigits
                        unconfirmed={r.sideProv !== "confirmed"} disabled={locked}
                        onChange={(v) => patch(r.key, { side: v as Row["side"], sideProv: "confirmed" })} />
                    )}
                  </div>

                  {showType && (
                    <div className={`${w.type} shrink-0`} onClick={(e) => e.stopPropagation()}>
                      <select
                        {...cellProps(r.key, ci("type"))}
                        disabled={locked} value={r.type}
                        onChange={(e) => {
                          const v = e.target.value as Row["type"];
                          // MARKET makes validity and yield basis meaningless — correct, do not warn.
                          patch(r.key, v === "MARKET"
                            ? { type: "MARKET", validity: "DAY", basis: "PRICE", orderYield: null }
                            : { type: v });
                        }}
                        className="w-full rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1 py-0.5 text-[11px] font-semibold text-ink-100">
                        <option value="LIMIT" className="bg-obsidian-850">LMT</option>
                        <option value="MARKET" className="bg-obsidian-850">MKT</option>
                      </select>
                    </div>
                  )}

                  {showValidity && (
                    <div className={`${w.validity} shrink-0`} onClick={(e) => e.stopPropagation()}>
                      <select
                        {...cellProps(r.key, ci("validity"))}
                        disabled={locked} value={r.validity}
                        onChange={(e) => patch(r.key, { validity: e.target.value })}
                        title="How long the order lives. Day expires at the close."
                        className={`w-full rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1 py-0.5 text-[11px] font-semibold ${
                          r.validity === DEF.validity ? "text-ink-200" : "text-aurora-violet"}`}>
                        {VALID_SEGS.map((v) => (
                          <option key={v.value} value={v.value} className="bg-obsidian-850">{v.value}</option>
                        ))}
                      </select>
                    </div>
                  )}

                  {/* qty — type=text so arrow keys navigate rows instead of decrementing the value */}
                  <NumCell
                    value={r.qty} integer disabled={locked}
                    inputProps={cellProps(r.key, ci("qty"))}
                    onPaste={(e) => onPaste(e, r.key)}
                    onChange={(v) => patch(r.key, { qty: v })}
                    className={`${w.qty} shrink-0 rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1.5 py-0.5 text-right text-[12px] tabular-nums text-ink-100`} />

                  {/* price — ~ marks a derived value that is not what gets transmitted */}
                  <div className={`relative ${w.price} shrink-0`} onClick={(e) => e.stopPropagation()}>
                    {r.basis === "YIELD" && <span className="absolute -left-1 top-1 text-[10px] text-aurora-cyan">~</span>}
                    <NumCell
                      value={r.type === "MARKET" ? null : r.basis === "YIELD" ? num(nf(r.price || 0, 2)) : r.price}
                      disabled={locked || r.type === "MARKET" || r.basis === "YIELD"}
                      inputProps={cellProps(r.key, ci("price"))}
                      placeholder={r.type === "MARKET" ? "mkt" : ""}
                      title={r.priceProv === "defaulted" ? "Seeded from the last traded price — edit it or leave it" : undefined}
                      onChange={(v) => patch(r.key, { price: v, priceProv: "confirmed" })}
                      className={`w-full rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1.5 py-0.5 text-right text-[12px] tabular-nums disabled:border-line/30 disabled:bg-transparent disabled:text-ink-500
                        ${r.priceProv === "defaulted" && r.type !== "MARKET" ? "italic text-amber-300/90" : "text-ink-100"}`} />
                  </div>

                  {/* order terms — always spelled out; defaults quiet, changes bright */}
                  {!compact && (
                  <span className="flex w-[210px] shrink-0 items-center gap-1 overflow-hidden whitespace-nowrap">
                    {terms.map((f, k) => (
                      <span key={k} title={f.title} className={`text-[11px] ${f.c}`}>
                        {k > 0 && <span className="mr-1 text-ink-500">·</span>}
                        {f.t}
                      </span>
                    ))}
                  </span>
                  )}

                  {!compact && (
                  <span className="w-[104px] shrink-0 text-right text-[12px] tabular-nums text-ink-100">
                    {value ? money(value) : ""}
                  </span>
                  )}

                  {/* risk — fixed width so a verdict never moves anything */}
                  {/* Grows with the panel instead of staying 70px: a blocked row's reason and a sent
                      row's status were both truncated to a few characters even when maximised. It
                      still never grows with its CONTENT, so a verdict arriving still shifts nothing. */}
                  <span className={`${tightRisk ? "w-[22px] shrink-0" : `${w.risk} min-w-[20px] flex-1 shrink`} truncate text-[11px]`}
                        title={r.risk?.reason || (!filled(r) ? "Incomplete — this row still needs values" : "")}>
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

                  {/* Pinned right: at the panel's minimum width the row scrolls under this, so SEND
                      and delete stay reachable instead of disappearing off the edge. */}
                  <span className={`flex ${w.act} sticky right-0 shrink-0 justify-end gap-1 bg-obsidian-900/95 pl-1 backdrop-blur-sm`}
                        onClick={(e) => e.stopPropagation()}>
                    {/*
                      SEND, on every row and labelled as such.
                      
                      It was a bare "↵" before, which reads as a hint about the Enter key rather than
                      as a button that sends an order to an exchange. The guard is unchanged:
                      sendable() requires risk.pass === true, so a row that has not passed the
                      pre-trade check cannot be sent from here any more than from Send all.
                    */}
                    <button disabled={!sendable(r, sec)} onClick={() => sendRow(r)}
                      data-send={r.key}
                      title={sendable(r, sec) ? "Send this order now" : "Not ready — see the risk column"}
                      className="rounded border border-bull/50 bg-bull/15 px-1 text-[10px] font-bold text-bull transition-colors disabled:border-line disabled:bg-transparent disabled:text-ink-500 disabled:opacity-40 hover:bg-bull/25">
                      SEND
                    </button>
                    {compact && (
                      <button
                        onClick={() => setMoreRow((k) => (k === r.key ? null : r.key))}
                        title="Market, price basis and stop for this row"
                        className={`rounded border px-1 text-[10px] ${
                          moreRow === r.key ? "border-aurora-cyan/50 bg-aurora-cyan/15 text-aurora-cyan"
                                            : "border-line text-ink-400 hover:text-ink-100"}`}>⋯</button>
                    )}
                    <button onClick={() => duplicateRow(r.key)} title="Duplicate this row"
                      className="rounded border border-line px-1 text-[10px] text-ink-400 hover:text-ink-100">⧉</button>
                    <button onClick={() => removeRow(r.key)} title="Remove this row"
                      className="rounded border border-line px-1 text-[10px] text-ink-500 hover:text-bear">✕</button>
                  </span>
                </div>

                {/* ---------------- line 2 : the qualifier band ----------------
                    Every group carries a permanent name. An unlabelled row of pills is readable only
                    to someone who already knows this screen — a trader meeting it for the first time
                    sees "NORMAL SPOT BLOCK ODD-LOT FOREIGN" and has no way to learn that those are
                    settlement markets rather than order types. The label is not decoration. */}
                {((isLit && !locked && !compact) || (compact && !locked && moreRow === r.key)) && (
                  <div className="flex min-h-[34px] flex-wrap items-center gap-x-3 gap-y-1.5 border-t border-aurora-cyan/10 bg-obsidian-950/25 px-2 py-1.5 pl-[46px]">
                    <span className="-ml-3 select-none text-ink-500">╰</span>

                    {bond && (
                      <Group name="Price basis" hint="Bonds may be entered as a clean price or as a yield (DSE BRS §1.1)">
                        <SegGroup label="Price basis" segs={BASIS_SEGS} value={r.basis} defaultValue="PRICE"
                          onChange={(v) => patch(r.key, { basis: v as Row["basis"] })} />
                        {r.basis === "YIELD" && (
                          <input value={r.orderYield ?? ""} inputMode="decimal" placeholder="yield %"
                            onChange={(e) => patch(r.key, { orderYield: num(e.target.value) })}
                            className="ml-1 w-[70px] rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1.5 py-0.5 text-right text-[11px] tabular-nums text-ink-100" />
                        )}
                      </Group>
                    )}

                    {!compact && (
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
                          className="ml-1 w-[68px] rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1.5 py-0.5 text-right text-[11px] tabular-nums text-ink-100" />
                      )}
                    </Group>
                    )}

                    {/* compact keeps type on line 1, but a STOP still needs somewhere to put its trigger */}
                    {compact && r.type.startsWith("STOP") && (
                      <Group name="Stop trigger" hint="The price at which this stop order activates">
                        <input value={r.stop ?? ""} inputMode="decimal" placeholder="trigger"
                          onChange={(e) => patch(r.key, { stop: num(e.target.value) })}
                          className="w-[76px] rounded border border-line/70 bg-white/[0.05] hover:border-aurora-cyan/40 focus:border-aurora-cyan focus:bg-white/[0.09] focus:outline-none px-1.5 py-0.5 text-right text-[11px] tabular-nums text-ink-100" />
                      </Group>
                    )}

                    <Group name="Market" hint="Which DSE market the order trades in. Normal is the public market, T+2.">
                      <SegGroup label="Market" segs={WINDOW_SEGS} value={r.window} defaultValue="NORMAL" showDigits
                        onChange={(v) => patch(r.key, { window: v })} />
                    </Group>

                    {!compact && (
                      <Group name="Validity" hint="How long the order lives. Day expires at the close.">
                        <SegGroup label="Validity" segs={VALID_SEGS} value={r.validity} defaultValue="DAY" showDigits
                          onChange={(v) => patch(r.key, { validity: v })} />
                      </Group>
                    )}

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

        {/* The book sits BELOW the ledger, not beside it. Beside, it took 300px off the grid and
            pushed the entry columns into a horizontal scroll — the two things a trader needs at once
            were competing for the same width. Stacked, both get the full width of the screen: the
            grid keeps its columns, and the ladder gets room to breathe across. */}
        {showDepth && (
          <div className={`${compact ? "h-[150px]" : "h-[190px]"} shrink-0 overflow-hidden rounded-lg border border-line bg-obsidian-900/40 px-2 py-1.5`}>
            <OrderDepthPanel
              securityId={litRow?.securityId ?? null}
              symbol={litSec?.symbol}
              side={litRow?.sideProv === "confirmed" ? litRow.side : undefined}
              price={litRow?.type === "MARKET" ? null : litRow?.price ?? null}
              levels={compact ? 4 : 5}
              compact={compact}
              wide
              // Clicking a level prices the row you are editing — the ladder is an input, not a poster.
              onPickPrice={(p) => litRow && patch(litRow.key, { price: p, priceProv: "confirmed" })}
            />
          </div>
        )}

        {/* totals */}
        <div className="flex flex-wrap items-center gap-4 rounded-xl border border-line bg-obsidian-900/60 px-3 py-2 text-[11px]">
          <span className="text-ink-400">Rows <span className="tabular-nums text-ink-200">{rows.length}</span></span>
          <span className="text-bull">Ready <span className="tabular-nums">{totals.ready}</span></span>
          {totals.held > 0 && <span className="text-amber-400">Held <span className="tabular-nums">{totals.held}</span></span>}
          {totals.sent > 0 && <span className="text-ink-300">Sent <span className="tabular-nums">{totals.sent}</span></span>}
          {/* Value that has no side yet is shown as its own figure rather than being folded into
              Buy, where it silently overstated the desk's exposure in one direction. */}
          {totals.unsided > 0 && (
            <span className="ml-auto text-amber-400" title="Rows whose side has not been chosen — not counted as buy or sell">
              No side <span className="tabular-nums">{money(totals.unsided)}</span>
            </span>
          )}
          <span className={`${totals.unsided > 0 ? "" : "ml-auto"} text-ink-400`}>Buy <span className="tabular-nums text-bull">{money(totals.buy)}</span></span>
          <span className="text-ink-400">Sell <span className="tabular-nums text-bear">{money(totals.sell)}</span></span>
          <span className="text-ink-400">Net <span className={`tabular-nums ${totals.net >= 0 ? "text-bull" : "text-bear"}`}>{money(Math.abs(totals.net))}</span></span>
        </div>

        {/* Toast — absolutely positioned so a message never costs a pixel of layout. It sits ABOVE the
            footer rather than over it: anchored to the bottom right it covered the Sell and Net
            exposure figures, and with no timer it stayed there for the rest of the session. */}
        {toast && (
          <div className="absolute bottom-12 right-4 z-40 max-w-[420px] rounded-lg border border-line bg-obsidian-850 px-3 py-2 text-[11px] text-ink-200 shadow-2xl">
            {toast}
            <button onClick={() => setToast("")} className="ml-3 text-ink-500 hover:text-ink-200">✕</button>
          </div>
        )}
      </div>
    </div>
  );
}
