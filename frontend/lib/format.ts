export const nf = (n: number, d = 2) =>
  (n ?? 0).toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });

export const nfInt = (n: number) => (n ?? 0).toLocaleString("en-US");

/**
 * South-Asian digit grouping: 10,00,000 — not 1,000,000.
 *
 * Bangladesh groups the last three digits, then twos. The app renders Taka with
 * toLocaleString("en-US"), which groups in threes throughout, so every figure on every screen has
 * been shown in a grouping no Bangladeshi reader uses. en-IN implements the correct convention and
 * is universally available in modern browsers.
 */
export const bdGroup = (n: number, d = 2) =>
  (n ?? 0).toLocaleString("en-IN", { minimumFractionDigits: d, maximumFractionDigits: d });

/**
 * Lakh / Crore, the units a DSE desk actually speaks in (MoM 13: "Input 1000000, display 10 Lakh").
 * Below a lakh there is nothing to abbreviate, so the plain grouped number is returned.
 */
export const lakh = (n: number): string => {
  const v = n ?? 0;
  const a = Math.abs(v);
  const sign = v < 0 ? "-" : "";
  if (a >= 1e7) return `${sign}${(a / 1e7).toFixed(a / 1e7 >= 100 ? 0 : 2)} Cr`;
  if (a >= 1e5) return `${sign}${(a / 1e5).toFixed(a / 1e5 >= 100 ? 0 : 2)} L`;
  if (a >= 1e3) return `${sign}${bdGroup(a, 0)}`;
  return `${sign}${bdGroup(a, 0)}`;
};

/** Taka in lakh/crore — for tiles and totals where the magnitude matters more than the paisa. */
export const moneyShort = (n: number) => `৳${lakh(n)}`;

export const compact = (n: number) =>
  Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 2 }).format(n ?? 0);

export const pct = (n: number) => `${n >= 0 ? "+" : ""}${nf(n, 2)}%`;

export const dirColor = (n: number) =>
  n > 0 ? "text-bull" : n < 0 ? "text-bear" : "text-ink-400";

export const dirBg = (n: number) =>
  n > 0 ? "bg-bull/10 text-bull" : n < 0 ? "bg-bear/10 text-bear" : "bg-white/5 text-ink-400";

/** Taka, grouped the Bangladeshi way. Was `nf` (en-US), which printed 10,00,000 as 1,000,000. */
export const money = (n: number) => `৳${bdGroup(n, 2)}`;

export const timeOf = (iso?: string) => {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleTimeString("en-GB", { hour12: false });
  } catch {
    return "";
  }
};

export const statusColor = (s: string) => {
  switch (s) {
    case "FILLED": return "bg-bull/15 text-bull";
    case "PARTIAL": return "bg-aurora-cyan/15 text-aurora-cyan";
    case "OPEN": return "bg-aurora-indigo/15 text-aurora-indigo";
    case "REJECTED": return "bg-bear/15 text-bear";
    case "CANCELLED": return "bg-white/8 text-ink-400";
    case "PENDING_RISK": return "bg-amber-400/15 text-amber-300";
    default: return "bg-white/8 text-ink-300";
  }
};

export const assetLabel = (a: string) => {
  const m: Record<string, string> = {
    EQUITY_MAIN: "Equity", EQUITY_SME: "SME", ATB_OTC: "ATB",
    CORP_BOND: "Corp Bond", GOVT_BOND: "Govt Bond", SUKUK: "Sukuk",
    MUTUAL_FUND: "Mutual Fund", ETF: "ETF", DERIVATIVE: "Derivative", INDEX: "Index",
  };
  return m[a] || a;
};
