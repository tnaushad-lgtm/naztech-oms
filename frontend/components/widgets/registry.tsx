"use client";

import * as W from "./widgets";
import { CandleWidget } from "./CandleWidget";

export type WidgetDef = {
  id: string;
  title: string;
  subtitle?: string;
  category: "Market" | "Portfolio" | "Orders & Risk" | "News";
  w: number; h: number; minW: number; minH: number;
  Component: React.ComponentType;
};

export const WIDGETS: WidgetDef[] = [
  // Market
  { id: "candles", title: "Candlestick Chart", subtitle: "OHLCV · selectable symbol", category: "Market", w: 6, h: 8, minW: 4, minH: 5, Component: CandleWidget },
  { id: "sectorPerf", title: "Sector Performance", subtitle: "Avg change % by sector", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.SectorPerformance },
  { id: "breadth", title: "Market Breadth", subtitle: "Advancers vs decliners", category: "Market", w: 3, h: 6, minW: 2, minH: 4, Component: W.MarketBreadth },
  { id: "gainers", title: "Top Gainers", subtitle: "Best performers", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.TopGainers },
  { id: "losers", title: "Top Losers", subtitle: "Worst performers", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.TopLosers },
  { id: "active", title: "Most Active", subtitle: "By turnover", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.MostActive },
  { id: "turnoverAsset", title: "Turnover by Asset Class", subtitle: "Treemap", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.TurnoverByAsset },
  { id: "indexBoard", title: "Index Board", subtitle: "DSE / CSE indices", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.IndexBoard },
  { id: "sectorXtab", title: "Sector Cross-Tab", subtitle: "Sector × metrics", category: "Market", w: 5, h: 7, minW: 4, minH: 4, Component: W.SectorCrosstab },
  { id: "heatmap", title: "Market Heatmap", subtitle: "Coloured by change %", category: "Market", w: 6, h: 6, minW: 4, minH: 4, Component: W.MarketHeatmap },
  // Portfolio
  { id: "pnlTrend", title: "P&L Over Time", subtitle: "Equity curve", category: "Portfolio", w: 6, h: 7, minW: 4, minH: 5, Component: W.PnLTrend },
  { id: "pfSummary", title: "Portfolio Summary", subtitle: "P&L snapshot", category: "Portfolio", w: 4, h: 5, minW: 3, minH: 4, Component: W.PortfolioSummary },
  { id: "allocSector", title: "Allocation by Sector", category: "Portfolio", w: 3, h: 6, minW: 2, minH: 4, Component: W.AllocBySector },
  { id: "allocAsset", title: "Allocation by Asset", category: "Portfolio", w: 3, h: 6, minW: 2, minH: 4, Component: W.AllocByAsset },
  { id: "pnlByPos", title: "P&L by Position", subtitle: "Unrealized", category: "Portfolio", w: 5, h: 6, minW: 3, minH: 4, Component: W.PnLByPosition },
  { id: "holdings", title: "Holdings", category: "Portfolio", w: 5, h: 7, minW: 4, minH: 4, Component: W.HoldingsGrid },
  // Orders & Risk
  { id: "orderStatus", title: "Order Status Mix", category: "Orders & Risk", w: 3, h: 6, minW: 2, minH: 4, Component: W.OrderStatusBreakdown },
  { id: "riskDist", title: "AI Risk Distribution", subtitle: "Score buckets", category: "Orders & Risk", w: 4, h: 6, minW: 3, minH: 4, Component: W.RiskDistribution },
  { id: "blotter", title: "Order Blotter", category: "Orders & Risk", w: 5, h: 7, minW: 4, minH: 4, Component: W.OrderBlotter },
  { id: "riskAlerts", title: "AI Risk Alerts", category: "Orders & Risk", w: 4, h: 7, minW: 3, minH: 4, Component: W.RiskAlerts },
  // News
  { id: "news", title: "News & Announcements", category: "News", w: 4, h: 7, minW: 3, minH: 4, Component: W.NewsFeed },
];

export const WIDGET_MAP: Record<string, WidgetDef> = Object.fromEntries(WIDGETS.map((w) => [w.id, w]));

export const DEFAULT_LAYOUT = [
  { id: "indexBoard", x: 0, y: 0 }, { id: "breadth", x: 4, y: 0 }, { id: "sectorPerf", x: 7, y: 0 },
  { id: "gainers", x: 0, y: 6 }, { id: "losers", x: 4, y: 6 }, { id: "active", x: 8, y: 6 },
  { id: "pfSummary", x: 0, y: 12 }, { id: "allocSector", x: 4, y: 12 }, { id: "riskDist", x: 7, y: 12 },
  { id: "blotter", x: 0, y: 18 }, { id: "news", x: 5, y: 18 }, { id: "heatmap", x: 0, y: 25 },
];

/** Build a left-to-right shelf-packed layout from a list of widget ids (12-col grid). */
export function layoutFromIds(ids: string[]) {
  let x = 0, y = 0, rowMaxH = 0;
  const out: any[] = [];
  for (const id of ids) {
    const d = WIDGET_MAP[id];
    if (!d) continue;
    if (x + d.w > 12) { x = 0; y += rowMaxH; rowMaxH = 0; }
    out.push({ i: id, x, y, w: d.w, h: d.h, minW: d.minW, minH: d.minH });
    x += d.w; rowMaxH = Math.max(rowMaxH, d.h);
  }
  return out;
}

/** Built-in dashboard presets — one click to reconfigure the board for a role. */
export const PRESETS: { name: string; widgets: string[] }[] = [
  { name: "Market Overview", widgets: ["indexBoard", "breadth", "sectorPerf", "gainers", "losers", "active", "candles", "heatmap", "turnoverAsset"] },
  { name: "Trading Desk", widgets: ["candles", "indexBoard", "orderStatus", "blotter", "pfSummary", "riskAlerts", "news"] },
  { name: "Portfolio & P&L", widgets: ["pnlTrend", "pfSummary", "allocSector", "allocAsset", "pnlByPos", "holdings"] },
  { name: "Risk & Compliance", widgets: ["riskDist", "orderStatus", "riskAlerts", "blotter", "sectorXtab"] },
];
