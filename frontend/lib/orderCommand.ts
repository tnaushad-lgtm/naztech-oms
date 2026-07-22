/**
 * The shorthand order command grammar:  B|S  <qty>  <TICKER>  @  [price]
 *
 * Extracted from the AI Order Bot page so the order grid can offer the same command box without a
 * second implementation. Two parsers for one grammar drift — one screen starts accepting something
 * the other rejects, and the trader learns a syntax that only works in half the app.
 *
 * This function is PURE: no fetch, no dispatch, no side effects. It cannot place an order, which is
 * the property that makes it safe to run on every keystroke while someone is typing. Placing stays
 * a separate, explicitly triggered call.
 */

export type ParsedCommand = {
  raw: string;
  ok: boolean;
  side?: "BUY" | "SELL";
  symbol?: string;
  securityId?: number;
  qty?: number;
  price?: number | null;
  market?: boolean;
  error?: string;
  /** True when the instrument list has not arrived yet, so a ticker cannot be judged. */
  pending?: boolean;
};

/**
 * Bengali-Indic digits to ASCII.
 *
 * A Bangladeshi keyboard set to Bangla produces ০-৯, and the grammar previously rejected those as
 * "Quantity must be digits only" — telling a Dhaka dealer that the digits on their own keyboard are
 * not digits. The numerals are unambiguous, so normalising them costs nothing and removes a
 * confusing dead end. Words are deliberately NOT translated here: the Bangla speech path already
 * produces an ASCII command before it reaches this function.
 */
const BN_DIGITS = "০১২৩৪৫৬৭৮৯";
export function normaliseDigits(s: string): string {
  let out = "";
  for (const ch of s) {
    const i = BN_DIGITS.indexOf(ch);
    out += i >= 0 ? String(i) : ch;
  }
  return out;
}

/**
 * Parse one command line.
 *
 * `secMap` maps an upper-cased symbol to its securityId. When it is empty the instrument list has
 * not loaded, and every line would otherwise report `Unknown ticker` — which reads as "your ticker
 * is wrong" when the truth is "the app is not ready yet".
 */
export function parseCommand(raw: string, secMap: Map<string, number>): ParsedCommand {
  const line = normaliseDigits(raw.trim());
  const parts = line.split(/\s+/);

  const sideTok = (parts[0] || "").toUpperCase();
  if (sideTok !== "B" && sideTok !== "S") {
    return { raw, ok: false, error: "Must start with B (buy) or S (sell)" };
  }
  const side: "BUY" | "SELL" = sideTok === "S" ? "SELL" : "BUY";

  if (parts.length < 2) return { raw, ok: false, side, error: `Need a quantity after ${sideTok}` };
  if (!/^\d+$/.test(parts[1])) return { raw, ok: false, side, error: "Quantity must be digits only" };
  const qty = parseInt(parts[1], 10);
  if (qty <= 0) return { raw, ok: false, side, error: "Quantity must be greater than 0" };

  if (parts.length < 3) return { raw, ok: false, side, qty, error: "Need a ticker after the quantity" };
  const sym = parts[2].toUpperCase();
  if (secMap.size === 0) {
    return { raw, ok: false, side, qty, pending: true, error: "Loading instruments…" };
  }
  if (!secMap.has(sym)) return { raw, ok: false, side, qty, error: `Unknown ticker "${parts[2]}"` };

  const rest = parts.slice(3).join("");
  if (!rest.startsWith("@")) return { raw, ok: false, side, qty, symbol: sym, error: "Missing '@' before the price" };

  const priceStr = rest.slice(1).trim();
  const market = priceStr === "";
  if (!market && !/^\d+(\.\d+)?$/.test(priceStr)) {
    return { raw, ok: false, side, qty, symbol: sym, error: "Price must be a number (or leave blank for @ market)" };
  }
  const price = market ? null : parseFloat(priceStr);
  if (!market && (price as number) <= 0) {
    return { raw, ok: false, side, qty, symbol: sym, error: "Price must be greater than 0" };
  }

  return { raw, ok: true, side, symbol: sym, securityId: secMap.get(sym) as number, qty, price, market };
}

/** Render an order back into a command string — the inverse of parseCommand, for echoing. */
export const formatCommand = (o: {
  side?: string; quantity?: number; qty?: number; symbol?: string; price?: number | null;
}): string => {
  const q = o.quantity ?? o.qty ?? 0;
  return `${o.side === "SELL" ? "S" : "B"} ${q} ${o.symbol} @${o.price != null ? " " + o.price : ""}`;
};

export const COMMAND_EXAMPLES = ["B 100 GP @ 120", "S 50 BRACBANK @", "B 200 SQURPHARMA @ 220"];
