"use client";

/**
 * The indicator catalogue — what the picker offers, and how an applied indicator is described.
 *
 * This exists because of one flaw in the original registry: `INDICATORS` in ./indicators.ts bakes
 * the period into the id ("SMA20", "EMA9"), so a period cannot be changed without inventing a new
 * union member, and you cannot run two EMAs of your own choosing. MoM §20 asks for "Configurable
 * Indicators"; four fixed buttons are not that.
 *
 * Here an indicator is a TYPE with declared parameters, and what a trader adds to a chart is an
 * INSTANCE of it — its own periods, its own colour, removable independently. EMA(9) and EMA(21)
 * are two instances of one indicator, not two indicators.
 *
 * ./indicators.ts is left exactly as it was: PriceChart and CandleWidget consume its INDICATORS
 * array and compute() directly, and this file adds a layer beside them rather than under them.
 */

import {
  Candle, Point,
  sma, ema, wma, rsi, bollinger, macd, vwap, atr, stddev,
  stochastic, adx, obv, cci, williamsR, roc, donchian, keltner, supertrend, pivotPoints,
} from "./indicators";

/** A tunable parameter. Ranges are enforced by the editor, so a period can never reach the maths as 0. */
export type ParamSpec = {
  key: string;
  label: string;
  def: number;
  min: number;
  max: number;
  /** Non-integer parameters (band multipliers) need a fractional step. */
  step?: number;
};

/** One drawable output of an indicator. `dashed`/`width` let a band read as subordinate to its centre. */
export type Series = {
  name: string;
  points: ({ time: number | string; value?: number })[];
  kind?: "line" | "histogram";
  /** Index into the instance's colour ramp; omitted means the instance's own colour. */
  tone?: "primary" | "secondary" | "muted" | "bull" | "bear" | "signed";
  width?: number;
  dashed?: boolean;
};

export type Output = {
  series: Series[];
  /** Horizontal reference levels drawn on the axis (pivots, RSI 70/30). */
  levels?: { label: string; value: number; muted?: boolean }[];
  /** Fixed scale for a sub-pane whose range is known in advance (0..100 oscillators). */
  range?: { min: number; max: number };
};

export type Category = "Moving averages" | "Bands & channels" | "Momentum" | "Volatility" | "Volume" | "Levels";

export type CatalogEntry = {
  id: string;
  label: string;
  category: Category;
  /** "price" overlays the price axis; "sub" needs its own scale and gets its own pane. */
  pane: "price" | "sub";
  params: ParamSpec[];
  hint: string;
  /** Extra search terms — a trader hunting "stoch" or "bands" should find it. */
  keywords?: string;
  compute: (candles: Candle[], p: Record<string, number>) => Output;
};

const P = (key: string, label: string, def: number, min: number, max: number, step?: number): ParamSpec =>
  ({ key, label, def, min, max, step });

/** Read a parameter, falling back to the declared default rather than trusting persisted state. */
const num = (p: Record<string, number>, spec: ParamSpec): number => {
  const v = p?.[spec.key];
  return typeof v === "number" && Number.isFinite(v) ? Math.min(spec.max, Math.max(spec.min, v)) : spec.def;
};

const PERIOD = (def: number, max = 400) => P("period", "Period", def, 1, max);

export const CATALOGUE: CatalogEntry[] = [
  // ------------------------------------------------------------------ moving averages
  {
    id: "SMA", label: "Simple Moving Average", category: "Moving averages", pane: "price",
    params: [PERIOD(20)], keywords: "ma average mean",
    hint: "The unweighted mean of the last N closes — the plainest trend line there is.",
    compute: (c, p) => ({ series: [{ name: `SMA ${num(p, PERIOD(20))}`, points: sma(c, num(p, PERIOD(20))) }] }),
  },
  {
    id: "EMA", label: "Exponential Moving Average", category: "Moving averages", pane: "price",
    params: [PERIOD(21)], keywords: "ma average exponential",
    hint: "Weights recent closes more heavily, so it turns sooner than an SMA of the same length.",
    compute: (c, p) => ({ series: [{ name: `EMA ${num(p, PERIOD(21))}`, points: ema(c, num(p, PERIOD(21))) }] }),
  },
  {
    id: "WMA", label: "Weighted Moving Average", category: "Moving averages", pane: "price",
    params: [PERIOD(20)], keywords: "ma average linear weighted",
    hint: "Linear weights 1..N — between an SMA and an EMA in how fast it reacts.",
    compute: (c, p) => ({ series: [{ name: `WMA ${num(p, PERIOD(20))}`, points: wma(c, num(p, PERIOD(20))) }] }),
  },
  {
    id: "VWAP", label: "VWAP", category: "Moving averages", pane: "price",
    params: [], keywords: "volume weighted average price benchmark",
    hint: "Volume-weighted average price from the first bar shown — the benchmark a fill is judged against.",
    compute: (c) => ({ series: [{ name: "VWAP", points: vwap(c) }] }),
  },

  // ------------------------------------------------------------------ bands & channels
  {
    id: "BB", label: "Bollinger Bands", category: "Bands & channels", pane: "price",
    params: [PERIOD(20), P("mult", "Std deviations", 2, 0.5, 5, 0.1)], keywords: "bands volatility envelope",
    hint: "An SMA with bands at ±N standard deviations — they widen as the market gets noisier.",
    compute: (c, p) => {
      const b = bollinger(c, num(p, PERIOD(20)), num(p, P("mult", "", 2, 0.5, 5, 0.1)));
      return { series: [
        { name: "Upper", points: b.upper, width: 1 },
        { name: "Basis", points: b.mid, tone: "muted", width: 1, dashed: true },
        { name: "Lower", points: b.lower, width: 1 },
      ] };
    },
  },
  {
    id: "KC", label: "Keltner Channel", category: "Bands & channels", pane: "price",
    params: [PERIOD(20), P("mult", "ATR multiple", 2, 0.5, 5, 0.1)], keywords: "bands channel atr envelope",
    hint: "An EMA with ATR-scaled bands — reacts to trading range rather than to dispersion.",
    compute: (c, p) => {
      const k = keltner(c, num(p, PERIOD(20)), num(p, P("mult", "", 2, 0.5, 5, 0.1)));
      return { series: [
        { name: "Upper", points: k.upper, width: 1 },
        { name: "Basis", points: k.mid, tone: "muted", width: 1, dashed: true },
        { name: "Lower", points: k.lower, width: 1 },
      ] };
    },
  },
  {
    id: "DC", label: "Donchian Channel", category: "Bands & channels", pane: "price",
    params: [PERIOD(20)], keywords: "bands channel breakout highest lowest",
    hint: "The highest high and lowest low of the last N bars — the classic breakout frame.",
    compute: (c, p) => {
      const d = donchian(c, num(p, PERIOD(20)));
      return { series: [
        { name: "Upper", points: d.upper, width: 1 },
        { name: "Mid", points: d.mid, tone: "muted", width: 1, dashed: true },
        { name: "Lower", points: d.lower, width: 1 },
      ] };
    },
  },
  {
    id: "ST", label: "Supertrend", category: "Bands & channels", pane: "price",
    params: [PERIOD(10, 100), P("mult", "ATR multiple", 3, 0.5, 10, 0.1)], keywords: "trend atr stop flip",
    hint: "An ATR band that flips sides when price closes through it — green below in an uptrend, red above in a downtrend.",
    compute: (c, p) => {
      const s = supertrend(c, num(p, PERIOD(10, 100)), num(p, P("mult", "", 3, 0.5, 10, 0.1)));
      return { series: [
        { name: "Up", points: s.up, tone: "bull", width: 2 },
        { name: "Down", points: s.down, tone: "bear", width: 2 },
      ] };
    },
  },

  // ------------------------------------------------------------------ momentum
  {
    id: "RSI", label: "RSI", category: "Momentum", pane: "sub",
    params: [PERIOD(14, 200)], keywords: "relative strength index momentum overbought oversold wilder",
    hint: "Relative Strength Index, Wilder — above 70 is stretched, below 30 is washed out.",
    compute: (c, p) => ({
      series: [{ name: `RSI ${num(p, PERIOD(14, 200))}`, points: rsi(c, num(p, PERIOD(14, 200))) }],
      levels: [{ label: "70", value: 70, muted: true }, { label: "30", value: 30, muted: true }],
      range: { min: 0, max: 100 },
    }),
  },
  {
    id: "MACD", label: "MACD", category: "Momentum", pane: "sub",
    params: [P("fast", "Fast EMA", 12, 1, 200), P("slow", "Slow EMA", 26, 2, 400), P("signal", "Signal", 9, 1, 100)],
    keywords: "convergence divergence momentum histogram crossover",
    hint: "The gap between a fast and a slow EMA, with its own signal line — the histogram is the gap between those two.",
    compute: (c, p) => {
      const m = macd(c, num(p, P("fast", "", 12, 1, 200)), num(p, P("slow", "", 26, 2, 400)), num(p, P("signal", "", 9, 1, 100)));
      return { series: [
        { name: "Histogram", points: m.histogram, kind: "histogram", tone: "signed" },
        { name: "MACD", points: m.macd, width: 2 },
        { name: "Signal", points: m.signal, tone: "secondary", width: 1 },
      ] };
    },
  },
  {
    id: "STOCH", label: "Stochastic", category: "Momentum", pane: "sub",
    params: [P("k", "%K length", 14, 1, 200), P("smooth", "%K smoothing", 3, 1, 50), P("d", "%D smoothing", 3, 1, 50)],
    keywords: "stochastic oscillator %k %d overbought oversold",
    hint: "Where the close sits in its recent range — above 80 near the top of it, below 20 near the bottom.",
    compute: (c, p) => {
      const s = stochastic(c, num(p, P("k", "", 14, 1, 200)), num(p, P("smooth", "", 3, 1, 50)), num(p, P("d", "", 3, 1, 50)));
      return {
        series: [{ name: "%K", points: s.k, width: 2 }, { name: "%D", points: s.d, tone: "secondary", width: 1 }],
        levels: [{ label: "80", value: 80, muted: true }, { label: "20", value: 20, muted: true }],
        range: { min: 0, max: 100 },
      };
    },
  },
  {
    id: "ADX", label: "ADX / DMI", category: "Momentum", pane: "sub",
    params: [PERIOD(14, 200)], keywords: "adx dmi directional movement trend strength wilder di",
    hint: "Trend STRENGTH, not direction — above 25 means a real trend; +DI over -DI says which way.",
    compute: (c, p) => {
      const a = adx(c, num(p, PERIOD(14, 200)));
      return {
        series: [
          { name: "ADX", points: a.adx, width: 2 },
          { name: "+DI", points: a.plusDI, tone: "bull", width: 1 },
          { name: "-DI", points: a.minusDI, tone: "bear", width: 1 },
        ],
        levels: [{ label: "25", value: 25, muted: true }],
      };
    },
  },
  {
    id: "CCI", label: "CCI", category: "Momentum", pane: "sub",
    params: [PERIOD(20, 200)], keywords: "commodity channel index momentum",
    hint: "How far price has strayed from its average, scaled so ±100 covers most readings.",
    compute: (c, p) => ({
      series: [{ name: `CCI ${num(p, PERIOD(20, 200))}`, points: cci(c, num(p, PERIOD(20, 200))), width: 2 }],
      levels: [{ label: "100", value: 100, muted: true }, { label: "-100", value: -100, muted: true }],
    }),
  },
  {
    id: "WILLR", label: "Williams %R", category: "Momentum", pane: "sub",
    params: [PERIOD(14, 200)], keywords: "williams percent range overbought oversold",
    hint: "The same idea as the stochastic, on a 0 to -100 scale — above -20 stretched, below -80 washed out.",
    compute: (c, p) => ({
      series: [{ name: `%R ${num(p, PERIOD(14, 200))}`, points: williamsR(c, num(p, PERIOD(14, 200))), width: 2 }],
      levels: [{ label: "-20", value: -20, muted: true }, { label: "-80", value: -80, muted: true }],
      range: { min: -100, max: 0 },
    }),
  },
  {
    id: "ROC", label: "Rate of Change", category: "Momentum", pane: "sub",
    params: [PERIOD(12, 200)], keywords: "roc momentum percent change",
    hint: "Percentage move against the close N bars ago — zero is flat, and the sign is the direction.",
    compute: (c, p) => ({
      series: [{ name: `ROC ${num(p, PERIOD(12, 200))}`, points: roc(c, num(p, PERIOD(12, 200))), width: 2 }],
      levels: [{ label: "0", value: 0, muted: true }],
    }),
  },

  // ------------------------------------------------------------------ volatility
  {
    id: "ATR", label: "ATR", category: "Volatility", pane: "sub",
    params: [PERIOD(14, 200)], keywords: "average true range volatility wilder stop sizing",
    hint: "Average True Range, Wilder — the typical bar size, and what a sane stop distance is measured in.",
    compute: (c, p) => ({ series: [{ name: `ATR ${num(p, PERIOD(14, 200))}`, points: atr(c, num(p, PERIOD(14, 200))), width: 2 }] }),
  },
  {
    id: "STDDEV", label: "Standard Deviation", category: "Volatility", pane: "sub",
    params: [PERIOD(20, 200)], keywords: "standard deviation volatility dispersion sigma",
    hint: "Dispersion of closes around their mean — the quantity Bollinger Bands are built from.",
    compute: (c, p) => ({ series: [{ name: `SD ${num(p, PERIOD(20, 200))}`, points: stddev(c, num(p, PERIOD(20, 200))), width: 2 }] }),
  },

  // ------------------------------------------------------------------ volume
  {
    id: "OBV", label: "On-Balance Volume", category: "Volume", pane: "sub",
    params: [], keywords: "obv volume flow accumulation distribution",
    hint: "Running total of volume signed by the day's direction — divergence from price is the signal.",
    compute: (c) => ({ series: [{ name: "OBV", points: obv(c), width: 2 }] }),
  },

  // ------------------------------------------------------------------ levels
  {
    id: "PIVOT", label: "Pivot Points (floor)", category: "Levels", pane: "price",
    params: [], keywords: "pivot floor support resistance levels r1 s1 daily",
    hint: "Classic floor pivots from the previous session — the support and resistance a Dhaka desk quotes intraday.",
    compute: (c) => ({
      series: [],
      levels: pivotPoints(c).map((l) => ({ label: l.label, value: l.value, muted: l.label !== "P" })),
    }),
  },
];

export const byId = (id: string): CatalogEntry | undefined => CATALOGUE.find((e) => e.id === id);

/** One indicator placed on a chart. Several instances of the same `id` may coexist. */
export type Applied = {
  uid: string;
  id: string;
  params: Record<string, number>;
  colour: string;
  hidden?: boolean;
};

/**
 * The palette new instances cycle through.
 *
 * Deliberately NOT the theme's bull/bear or aurora accents: on a trading screen green and red mean
 * up and down, and an EMA that happens to be drawn green would be read as a signal. These are
 * distinguishable hues that carry no directional meaning.
 */
export const PALETTE = [
  "#22d3ee", "#a78bfa", "#f59e0b", "#f472b6", "#2dd4bf",
  "#818cf8", "#facc15", "#fb923c", "#4ade80", "#38bdf8",
];

export function defaultParams(entry: CatalogEntry): Record<string, number> {
  const out: Record<string, number> = {};
  for (const p of entry.params) out[p.key] = p.def;
  return out;
}

/** A short label carrying the instance's actual settings — "EMA 21", "BB 20 · 2". */
export function describe(a: Applied): string {
  const e = byId(a.id);
  if (!e) return a.id;
  const vals = e.params.map((p) => {
    const v = a.params?.[p.key];
    return typeof v === "number" ? v : p.def;
  });
  return vals.length ? `${a.id} ${vals.join(" · ")}` : a.id;
}

let seq = 0;
/**
 * Instance ids are sequential, not random: they end up in localStorage and in React keys, and a
 * stable, readable id makes a persisted setup inspectable when something goes wrong.
 */
export function newApplied(id: string, colourIndex = 0): Applied | null {
  const e = byId(id);
  if (!e) return null;
  seq += 1;
  return { uid: `${id}-${seq}`, id, params: defaultParams(e), colour: PALETTE[colourIndex % PALETTE.length] };
}

/**
 * Restore a persisted set, discarding anything that no longer type-checks.
 *
 * Persisted preferences outlive the code that wrote them. An indicator can be renamed or removed
 * between releases, and a saved chart naming it must degrade to "that one is gone" rather than
 * throwing on load and costing the trader their whole layout.
 */
export function reviveApplied(raw: unknown): Applied[] {
  if (!Array.isArray(raw)) return [];
  const out: Applied[] = [];
  for (const item of raw) {
    if (!item || typeof item !== "object") continue;
    const a = item as Partial<Applied>;
    const entry = typeof a.id === "string" ? byId(a.id) : undefined;
    if (!entry || typeof a.uid !== "string") continue;
    const params: Record<string, number> = {};
    for (const p of entry.params) {
      const v = a.params?.[p.key];
      params[p.key] = typeof v === "number" && Number.isFinite(v)
        ? Math.min(p.max, Math.max(p.min, v))
        : p.def;
    }
    out.push({
      uid: a.uid,
      id: a.id as string,
      params,
      colour: typeof a.colour === "string" ? a.colour : PALETTE[out.length % PALETTE.length],
      hidden: a.hidden === true,
    });
    const n = Number(String(a.uid).split("-").pop());
    if (Number.isFinite(n) && n > seq) seq = n;      // never re-issue a uid already in use
  }
  return out;
}

/**
 * Migrate the flat overlay/study selection the chart screen shipped with.
 *
 * The first version persisted `{ overlays: ["EMA9","VWAP"], study: "RSI14" }` — periods encoded in
 * the id. Anyone who used that screen has one saved, and silently dropping it would reset their
 * chart for no visible reason, which reads as data loss.
 */
const LEGACY: Record<string, { id: string; params?: Record<string, number> }> = {
  EMA9:  { id: "EMA", params: { period: 9 } },
  EMA21: { id: "EMA", params: { period: 21 } },
  SMA20: { id: "SMA", params: { period: 20 } },
  SMA50: { id: "SMA", params: { period: 50 } },
  BB20:  { id: "BB", params: { period: 20, mult: 2 } },
  VWAP:  { id: "VWAP" },
  RSI14: { id: "RSI", params: { period: 14 } },
  MACD:  { id: "MACD" },
  ATR14: { id: "ATR", params: { period: 14 } },
};

export function migrateLegacy(overlays: unknown, study: unknown): Applied[] {
  const ids: string[] = [];
  if (Array.isArray(overlays)) ids.push(...overlays.filter((x): x is string => typeof x === "string"));
  if (typeof study === "string") ids.push(study);

  const out: Applied[] = [];
  for (const legacyId of ids) {
    const m = LEGACY[legacyId];
    if (!m) continue;
    const a = newApplied(m.id, out.length);
    if (!a) continue;
    if (m.params) a.params = { ...a.params, ...m.params };
    out.push(a);
  }
  return out;
}
