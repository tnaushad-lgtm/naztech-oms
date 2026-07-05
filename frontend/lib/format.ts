export const nf = (n: number, d = 2) =>
  (n ?? 0).toLocaleString("en-US", { minimumFractionDigits: d, maximumFractionDigits: d });

export const nfInt = (n: number) => (n ?? 0).toLocaleString("en-US");

export const compact = (n: number) =>
  Intl.NumberFormat("en", { notation: "compact", maximumFractionDigits: 2 }).format(n ?? 0);

export const pct = (n: number) => `${n >= 0 ? "+" : ""}${nf(n, 2)}%`;

export const dirColor = (n: number) =>
  n > 0 ? "text-bull" : n < 0 ? "text-bear" : "text-ink-400";

export const dirBg = (n: number) =>
  n > 0 ? "bg-bull/10 text-bull" : n < 0 ? "bg-bear/10 text-bear" : "bg-white/5 text-ink-400";

export const money = (n: number) => `৳${nf(n, 2)}`;

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
