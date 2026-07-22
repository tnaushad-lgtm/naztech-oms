"use client";

/**
 * Standalone RSI-14 oscillator.
 *
 * RSI is deliberately excluded from the candlestick widget: it is a 0-100 series, so overlaying it
 * on a price axis flattens the candles into a line, and a dashboard widget is too short to carry a
 * second pane inside itself. As its own widget it gets the height it needs — and a trader watching
 * momentum on three names can pin three of these without three price charts.
 *
 * Wilder's smoothing, shared with the terminal chart via lib/indicators, so the number here and the
 * number on the Trader Terminal are the same number.
 */

import { useEffect, useMemo, useRef, useState } from "react";
import { useDash } from "@/lib/dashboardData";
import { API } from "@/lib/api";
import { chartBase, cssRGB } from "@/lib/chartTheme";
import { rsi, Candle } from "@/lib/indicators";
import { nf } from "@/lib/format";

const TFS = ["1m", "5m", "15m", "1d"];
const RSI_COLOUR = "#e879f9";

export function RsiWidget() {
  const { watch } = useDash();
  const equities = useMemo(() => watch.filter((r) => r.assetClass !== "INDEX"), [watch]);
  const [symbol, setSymbol] = useState("");
  const [tf, setTf] = useState("5m");
  const [last, setLast] = useState<number | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<HTMLDivElement>(null);

  useEffect(() => { if (!symbol && equities.length) setSymbol(equities[0].symbol); }, [equities, symbol]);
  const sec = equities.find((e) => e.symbol === symbol);

  useEffect(() => {
    if (!sec) return;
    let chart: any, ro: ResizeObserver | null = null, disposed = false;
    (async () => {
      const lib = await import("lightweight-charts");
      if (disposed || !chartRef.current) return;
      let candles: Candle[] = [];
      try { candles = await (await fetch(`${API}/api/market/${sec.securityId}/candles?tf=${tf}&limit=250`)).json(); } catch {}
      if (!Array.isArray(candles) || candles.length < 20) { setLast(null); return; }

      const series = rsi(candles, 14);
      if (!series.length) { setLast(null); return; }
      setLast(series[series.length - 1].value);

      chartRef.current.innerHTML = "";
      const h = wrapRef.current ? Math.max(110, wrapRef.current.clientHeight - 34) : 150;
      chart = lib.createChart(chartRef.current, {
        ...chartBase(lib, tf !== "1d"),
        height: h,
        rightPriceScale: { borderColor: cssRGB("--ink-600", 0.25), scaleMargins: { top: 0.08, bottom: 0.08 } },
      });
      const s = chart.addLineSeries({ color: RSI_COLOUR, lineWidth: 2, priceLineVisible: false, lastValueVisible: true });
      s.setData(series);
      // 70/30 are what make an RSI readable at a glance; without them it is just a wiggly line.
      for (const lvl of [70, 30]) {
        s.createPriceLine({
          price: lvl, color: cssRGB("--ink-600", 0.7), lineWidth: 1, lineStyle: 2,
          axisLabelVisible: true, title: String(lvl),
        });
      }
      chart.timeScale().fitContent();
      const resize = () => {
        if (!chartRef.current || !wrapRef.current) return;
        chart.applyOptions({ width: chartRef.current.clientWidth, height: Math.max(110, wrapRef.current.clientHeight - 34) });
      };
      resize();
      ro = new ResizeObserver(resize);
      if (wrapRef.current) ro.observe(wrapRef.current);
    })();
    return () => { disposed = true; ro?.disconnect(); chart?.remove?.(); };
  }, [sec?.securityId, tf]);

  const tone = last == null ? "text-ink-400" : last >= 70 ? "text-bear" : last <= 30 ? "text-bull" : "text-ink-200";
  const verdict = last == null ? "" : last >= 70 ? "overbought" : last <= 30 ? "oversold" : "neutral";

  return (
    <div ref={wrapRef} className="flex h-full flex-col">
      <div className="mb-1 flex items-center justify-between gap-2">
        <select className="field max-w-[45%] py-1 text-[11px]" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
          {equities.map((e) => <option key={e.securityId} value={e.symbol} className="bg-obsidian-850">{e.symbol}</option>)}
        </select>
        {last != null && (
          <span className={`text-[12px] font-semibold tabular-nums ${tone}`} title={`RSI 14 — ${verdict}`}>
            {nf(last, 1)} <span className="text-[10px] font-normal">{verdict}</span>
          </span>
        )}
        <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5">
          {TFS.map((x) => (
            <button key={x} onClick={() => setTf(x)}
              className={`rounded-md px-1.5 py-0.5 text-[10px] font-semibold ${tf === x ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
              {x.toUpperCase()}
            </button>
          ))}
        </div>
      </div>
      <div ref={chartRef} className="min-h-0 flex-1" />
    </div>
  );
}
