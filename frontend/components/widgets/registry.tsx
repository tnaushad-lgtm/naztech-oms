"use client";

import * as W from "./widgets";
import { CandleWidget } from "./CandleWidget";
import { RsiWidget } from "./RsiWidget";
import * as M from "./moreWidgets";
import { TickerTape } from "../TickerTape";
import { RoutingBadge } from "../RoutingBadge";
import { SessionBadge } from "../SessionBadge";
import { NewsPanel } from "../NewsPanel";

export type WidgetDef = {
  id: string;
  title: string;
  subtitle?: string;
  category: "Market" | "Portfolio" | "Orders & Risk" | "News" | "Operations" | "Reports";
  /**
   * Extra words the library search should match.
   *
   * A trader hunting the candlestick widget types "chart" or "graph", not "candlestick"; someone
   * after the equity curve types "pnl" or "profit". Titles alone make the search a spelling test.
   */
  keywords?: string;
  w: number; h: number; minW: number; minH: number;
  Component: React.ComponentType;
};

export const WIDGETS: WidgetDef[] = [
  // Market
  { id: "candles", keywords: "chart graph candlestick ohlc price volume technical rsi ema sma bollinger indicator", title: "Candlestick Chart", subtitle: "OHLCV · selectable symbol", category: "Market", w: 6, h: 8, minW: 4, minH: 5, Component: CandleWidget },
  { id: "sectorPerf", keywords: "chart graph bar sector performance industry", title: "Sector Performance", subtitle: "Avg change % by sector", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.SectorPerformance },
  { id: "breadth", keywords: "chart graph donut pie advancers decliners breadth market", title: "Market Breadth", subtitle: "Advancers vs decliners", category: "Market", w: 3, h: 6, minW: 2, minH: 4, Component: W.MarketBreadth },
  { id: "gainers", keywords: "table list top gainers movers best risers", title: "Top Gainers", subtitle: "Best performers", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.TopGainers },
  { id: "losers", keywords: "table list top losers movers worst fallers", title: "Top Losers", subtitle: "Worst performers", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.TopLosers },
  { id: "active", keywords: "table list most active turnover volume liquid", title: "Most Active", subtitle: "By turnover", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.MostActive },
  { id: "turnoverAsset", keywords: "chart treemap turnover asset class value", title: "Turnover by Asset Class", subtitle: "Treemap", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.TurnoverByAsset },
  { id: "indexBoard", keywords: "index dsex cse indices board benchmark", title: "Index Board", subtitle: "DSE / CSE indices", category: "Market", w: 4, h: 6, minW: 3, minH: 4, Component: W.IndexBoard },
  { id: "sectorXtab", keywords: "table crosstab sector metrics pivot", title: "Sector Cross-Tab", subtitle: "Sector × metrics", category: "Market", w: 5, h: 7, minW: 4, minH: 4, Component: W.SectorCrosstab },
  { id: "heatmap", keywords: "chart heatmap map colour change movers", title: "Market Heatmap", subtitle: "Coloured by change %", category: "Market", w: 6, h: 6, minW: 4, minH: 4, Component: W.MarketHeatmap },
  // Portfolio
  { id: "pnlTrend", keywords: "chart graph line equity curve pnl profit loss over time performance", title: "P&L Over Time", subtitle: "Equity curve", category: "Portfolio", w: 6, h: 7, minW: 4, minH: 5, Component: W.PnLTrend },
  { id: "pfSummary", keywords: "portfolio summary pnl snapshot value cash", title: "Portfolio Summary", subtitle: "P&L snapshot", category: "Portfolio", w: 4, h: 5, minW: 3, minH: 4, Component: W.PortfolioSummary },
  { id: "allocSector", keywords: "chart pie donut allocation sector exposure weight", title: "Allocation by Sector", category: "Portfolio", w: 3, h: 6, minW: 2, minH: 4, Component: W.AllocBySector },
  { id: "allocAsset", keywords: "chart pie donut allocation asset class exposure weight", title: "Allocation by Asset", category: "Portfolio", w: 3, h: 6, minW: 2, minH: 4, Component: W.AllocByAsset },
  { id: "pnlByPos", keywords: "chart bar pnl position unrealized profit loss", title: "P&L by Position", subtitle: "Unrealized", category: "Portfolio", w: 5, h: 6, minW: 3, minH: 4, Component: W.PnLByPosition },
  { id: "holdings", keywords: "table holdings positions stock shares qty", title: "Holdings", category: "Portfolio", w: 5, h: 7, minW: 4, minH: 4, Component: W.HoldingsGrid },
  // Orders & Risk
  { id: "orderStatus", keywords: "chart pie donut order status mix filled rejected", title: "Order Status Mix", category: "Orders & Risk", w: 3, h: 6, minW: 2, minH: 4, Component: W.OrderStatusBreakdown },
  { id: "riskDist", keywords: "chart bar risk score distribution ai buckets", title: "AI Risk Distribution", subtitle: "Score buckets", category: "Orders & Risk", w: 4, h: 6, minW: 3, minH: 4, Component: W.RiskDistribution },
  { id: "blotter", keywords: "table order blotter orders status fills", title: "Order Blotter", category: "Orders & Risk", w: 5, h: 7, minW: 4, minH: 4, Component: W.OrderBlotter },
  { id: "riskAlerts", keywords: "risk alerts ai warnings breaches", title: "AI Risk Alerts", category: "Orders & Risk", w: 4, h: 7, minW: 3, minH: 4, Component: W.RiskAlerts },

  // ---- Market ----------------------------------------------------------------
  { id: "tickerTape", keywords: "ticker tape marquee scroller crawl strip ltp indices", title: "Ticker Tape", subtitle: "Scrolling indices & movers", category: "Market", w: 12, h: 2, minW: 4, minH: 2, Component: TickerTape },
  { id: "rsi", keywords: "rsi relative strength index oscillator momentum overbought oversold indicator wilder 70 30", title: "RSI-14 Oscillator", subtitle: "Momentum · 70/30 bands", category: "Market", w: 4, h: 5, minW: 3, minH: 3, Component: RsiWidget },
  { id: "depthLadder", keywords: "depth market depth book order book ladder bid ask level 2 l2 dom liquidity spread", title: "Market Depth", subtitle: "Live order book ladder", category: "Market", w: 3, h: 8, minW: 2, minH: 5, Component: M.DepthWidget },
  { id: "timeSales", keywords: "time and sales tape prints trades executions t&s", title: "Time & Sales", subtitle: "Live market prints", category: "Market", w: 4, h: 7, minW: 3, minH: 4, Component: M.TimeAndSales },

  // ---- Orders & Risk ---------------------------------------------------------
  { id: "killSwitch", keywords: "kill switch halt panic stop trading broker firm suspend resume rms", title: "Broker Kill-Switch", subtitle: "Halt / resume a firm", category: "Orders & Risk", w: 4, h: 6, minW: 3, minH: 3, Component: M.BrokerKillSwitch },
  { id: "rmsAlerts", keywords: "risk alerts rms breaches rejects score fat finger warnings meter", title: "RMS Risk Alerts", subtitle: "Server-scored, with meter", category: "Orders & Risk", w: 4, h: 7, minW: 3, minH: 4, Component: M.RmsAlertFeed },

  // ---- Operations ------------------------------------------------------------
  { id: "linkHealth", keywords: "fix itch connectivity link venue exchange session health status offline routing", title: "Exchange Link Health", subtitle: "FIX & ITCH traffic lights", category: "Operations", w: 4, h: 3, minW: 3, minH: 2, Component: RoutingBadge },
  { id: "sessionBadge", keywords: "session badge market open closed halted phase pill status bell", title: "Market Phase", subtitle: "Open / halted / closed", category: "Operations", w: 2, h: 2, minW: 2, minH: 2, Component: SessionBadge },
  { id: "fixSession", keywords: "fix session logon compid sequence heartbeat quickfix connectivity", title: "FIX Session Detail", subtitle: "CompIDs, sequences, heartbeat", category: "Operations", w: 4, h: 7, minW: 3, minH: 5, Component: M.FixSessionDetail },
  { id: "itchFeed", keywords: "itch feed market data transport soupbintcp moldudp64 gaps sequence lost", title: "ITCH Feed Health", subtitle: "Sequence, gaps, lost", category: "Operations", w: 4, h: 7, minW: 3, minH: 5, Component: M.ItchFeedStatus },
  { id: "exchangeKpis", keywords: "kpi counts overview brokers users securities orders trades clients admin", title: "Exchange KPIs", subtitle: "Control-plane counters", category: "Operations", w: 4, h: 5, minW: 3, minH: 3, Component: M.ExchangeKpis },
  { id: "auditTrail", keywords: "audit log activity compliance trail history who did what", title: "Audit Trail", subtitle: "Recent control-plane activity", category: "Operations", w: 4, h: 7, minW: 3, minH: 4, Component: M.AuditTrail },
  { id: "trecHolders", keywords: "trec brokers firms registry members limit", title: "TREC Holders", subtitle: "Broker registry", category: "Operations", w: 4, h: 6, minW: 3, minH: 3, Component: M.TrecHolders },
  { id: "aiEngine", keywords: "ai engine minilm semantic index vectors on-prem status", title: "AI Engine Status", subtitle: "On-prem search index", category: "Operations", w: 3, h: 5, minW: 2, minH: 3, Component: M.AiEngineStatus },

  // ---- News ------------------------------------------------------------------
  { id: "newsFull", keywords: "news announcements price sensitive dividend agm halt sentiment dse notices", title: "News (full)", subtitle: "Categories & sentiment", category: "News", w: 4, h: 8, minW: 3, minH: 5, Component: NewsPanel },
  // News
  { id: "news", keywords: "news announcements price sensitive dse", title: "News & Announcements", category: "News", w: 4, h: 7, minW: 3, minH: 4, Component: W.NewsFeed },
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
