"use client";

/**
 * Theme colours for lightweight-charts.
 *
 * The charts hard-coded hex values ("#9aa3c4", "#22c55e"), which made them the only components the
 * theme switcher could not reach — visibly wrong on the light `daylight` theme. Everything else in
 * the app is CSS-variable driven; the charts have to resolve those variables at draw time because
 * lightweight-charts wants concrete colour strings, not `var(--x)`.
 *
 * Shared so the terminal chart and the dashboard candle widget cannot drift apart again.
 */

const FALLBACK = "148,163,184";

/**
 * Read a `--ink-400`-style variable and return a colour lightweight-charts will accept.
 *
 * The variables hold space-separated channels ("251 91 107") for Tailwind's `<alpha-value>` syntax.
 * lightweight-charts parses colours itself and rejects CSS Color Level 4 spaces outright —
 * "Cannot parse color: rgb(251 91 107)" — so the channels are always joined with commas.
 */
export function cssRGB(varName: string, alpha = 1): string {
  if (typeof window === "undefined") return `rgba(${FALLBACK},${alpha})`;
  const raw = getComputedStyle(document.documentElement).getPropertyValue(varName).trim();
  const ch = (raw || FALLBACK).split(/[\s,]+/).filter(Boolean).join(",");
  if (ch.split(",").length < 3) return `rgba(${FALLBACK},${alpha})`;
  return alpha === 1 ? `rgb(${ch})` : `rgba(${ch},${alpha})`;
}

/** The chart options every candle chart in the app shares, resolved against the active theme. */
export function chartBase(lib: any, timeVisible: boolean) {
  const line = cssRGB("--ink-600", 0.25);
  return {
    layout: { background: { type: lib.ColorType.Solid, color: "transparent" }, textColor: cssRGB("--ink-400"), fontSize: 11 },
    grid: { vertLines: { color: line }, horzLines: { color: line } },
    rightPriceScale: { borderColor: line },
    timeScale: { borderColor: line, timeVisible, secondsVisible: false },
  };
}

export const candleColours = () => ({
  upColor: cssRGB("--bull"),
  downColor: cssRGB("--bear"),
  borderVisible: false,
  wickUpColor: cssRGB("--bull"),
  wickDownColor: cssRGB("--bear"),
});

/** Indicator selection is shared across every chart, so turning on RSI turns it on everywhere. */
export const INDICATOR_STORE = "oms_chart_indicators";

export function readIndicators(): string[] {
  if (typeof window === "undefined") return [];
  try {
    const raw = localStorage.getItem(INDICATOR_STORE);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}
