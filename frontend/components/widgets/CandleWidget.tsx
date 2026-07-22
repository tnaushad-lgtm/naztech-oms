"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { useDash } from "@/lib/dashboardData";
import { API } from "@/lib/api";
import { chartBase, candleColours, cssRGB, readIndicators } from "@/lib/chartTheme";
import { INDICATORS, compute, IndicatorId, Candle } from "@/lib/indicators";

const TFS = ["1m", "5m", "15m", "1d"];

export function CandleWidget() {
  const { watch } = useDash();
  const equities = useMemo(() => watch.filter((r) => r.assetClass !== "INDEX"), [watch]);
  const [symbol, setSymbol] = useState<string>("");
  const [tf, setTf] = useState("5m");
  const [on, setOn] = useState<string[]>([]);
  useEffect(() => { setOn(readIndicators()); }, []);
  const wrapRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<HTMLDivElement>(null);

  // default the symbol once data arrives
  useEffect(() => { if (!symbol && equities.length) setSymbol(equities[0].symbol); }, [equities, symbol]);
  const sec = equities.find((e) => e.symbol === symbol);

  useEffect(() => {
    if (!sec) return;
    let chart: any, ro: ResizeObserver | null = null, disposed = false;
    (async () => {
      const lib = await import("lightweight-charts");
      if (disposed || !chartRef.current) return;
      let candles: any[] = [];
      try { candles = await (await fetch(`${API}/api/market/${sec.securityId}/candles?tf=${tf}&limit=140`)).json(); } catch {}
      if (!Array.isArray(candles) || !candles.length) return;
      chartRef.current.innerHTML = "";
      const h = wrapRef.current ? Math.max(140, wrapRef.current.clientHeight - 34) : 200;
      chart = lib.createChart(chartRef.current, { ...chartBase(lib, tf !== "1d"), height: h });
      const cs = chart.addCandlestickSeries(candleColours());
      cs.setData(candles.map((c) => ({ time: c.time, open: c.open, high: c.high, low: c.low, close: c.close })));
      const vs = chart.addHistogramSeries({ priceFormat: { type: "volume" }, priceScaleId: "" });
      vs.priceScale().applyOptions({ scaleMargins: { top: 0.84, bottom: 0 } });
      vs.setData(candles.map((c) => ({
        time: c.time, value: c.volume,
        color: c.close >= c.open ? cssRGB("--bull", 0.4) : cssRGB("--bear", 0.4),
      })));

      // Same indicator preference as the terminal chart, so turning on EMA there turns it on here.
      // Price-pane overlays only: a 0..100 RSI would flatten this widget's price axis into a line,
      // and there is no room for a second pane at dashboard-widget height.
      for (const ind of INDICATORS) {
        if (ind.pane !== "price" || !on.includes(ind.id)) continue;
        for (const series of compute(ind.id as IndicatorId, candles as Candle[])) {
          if (!series.length) continue;
          const ls = chart.addLineSeries({
            color: ind.colour, lineWidth: ind.id === "BB20" ? 1 : 2,
            priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false,
          });
          ls.setData(series);
        }
      }
      chart.timeScale().fitContent();
      const resize = () => {
        if (!chartRef.current || !wrapRef.current) return;
        chart.applyOptions({ width: chartRef.current.clientWidth, height: Math.max(140, wrapRef.current.clientHeight - 34) });
      };
      resize();
      ro = new ResizeObserver(resize); if (wrapRef.current) ro.observe(wrapRef.current);
    })();
    return () => { disposed = true; ro?.disconnect(); chart?.remove?.(); };
  }, [sec?.securityId, tf, on]);

  return (
    <div ref={wrapRef} className="flex h-full flex-col">
      <div className="mb-1 flex items-center justify-between gap-2">
        <select className="field max-w-[55%] py-1 text-[11px]" value={symbol} onChange={(e) => setSymbol(e.target.value)}>
          {equities.map((e) => <option key={e.securityId} value={e.symbol} className="bg-obsidian-850">{e.symbol}</option>)}
        </select>
        <div className="flex rounded-lg border border-line/[0.12] bg-surface/[0.05] p-0.5">
          {TFS.map((x) => (
            <button key={x} onClick={() => setTf(x)}
              className={`rounded-md px-2 py-0.5 text-[10px] font-semibold ${tf === x ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white" : "text-ink-400 hover:text-ink-100"}`}>
              {x.toUpperCase()}
            </button>
          ))}
        </div>
      </div>
      <div ref={chartRef} className="min-h-0 flex-1" />
    </div>
  );
}
