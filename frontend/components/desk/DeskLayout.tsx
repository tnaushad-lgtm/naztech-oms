"use client";

import "flexlayout-react/style/dark.css";
import { useEffect, useRef, useState } from "react";
import { Layout, Model, TabNode, IJsonModel } from "flexlayout-react";
import { getSession } from "@/lib/session";
import {
  WatchPanel, ChartPanel, DepthPanel, TicketPanel, PortfolioPanel, NewsDeskPanel, BlotterPanel,
} from "./Panels";

const PANELS: { component: string; name: string }[] = [
  { component: "watch", name: "Market Watch" },
  { component: "chart", name: "Chart" },
  { component: "depth", name: "Order Book (Depth)" },
  { component: "ticket", name: "Order Ticket" },
  { component: "portfolio", name: "Portfolio" },
  { component: "news", name: "News" },
  { component: "blotter", name: "Order Blotter" },
];

const DEFAULT_JSON: IJsonModel = {
  global: {
    tabEnableClose: true, tabSetEnableMaximize: true, splitterSize: 6,
    tabSetHeaderHeight: 26, tabSetTabStripHeight: 30, tabEnableRename: false,
  },
  // FlexLayout has no "column" type — nested rows alternate orientation automatically
  // (root row = horizontal, child row = vertical, grandchild row = horizontal, …).
  layout: {
    type: "row",
    children: [{
      type: "row", weight: 100,                 // vertical: top area over the blotter
      children: [
        {
          type: "row", weight: 72,              // horizontal: watch | centre | right
          children: [
            { type: "tabset", weight: 22, children: [{ type: "tab", name: "Market Watch", component: "watch" }] },
            {
              type: "row", weight: 50, children: [   // vertical: chart over depth
                { type: "tabset", weight: 62, children: [{ type: "tab", name: "Chart", component: "chart" }] },
                { type: "tabset", weight: 38, children: [{ type: "tab", name: "Order Book (Depth)", component: "depth" }] },
              ],
            },
            {
              type: "row", weight: 28, children: [   // vertical: ticket over portfolio/news
                { type: "tabset", weight: 52, children: [{ type: "tab", name: "Order Ticket", component: "ticket" }] },
                {
                  type: "tabset", weight: 48, children: [
                    { type: "tab", name: "Portfolio", component: "portfolio" },
                    { type: "tab", name: "News", component: "news" },
                  ],
                },
              ],
            },
          ],
        },
        { type: "tabset", weight: 28, children: [{ type: "tab", name: "Order Blotter", component: "blotter" }] },
      ],
    }],
  },
};

const factory = (node: TabNode) => {
  switch (node.getComponent()) {
    case "watch": return <WatchPanel />;
    case "chart": return <ChartPanel />;
    case "depth": return <DepthPanel />;
    case "ticket": return <TicketPanel />;
    case "portfolio": return <PortfolioPanel />;
    case "news": return <NewsDeskPanel />;
    case "blotter": return <BlotterPanel />;
    default: return null;
  }
};

/** Namespaced so the Trader Terminal and the Trading Desk each remember their own arrangement. */
const KEY = (ns: string) => `oms_${ns}_${getSession()?.username || "guest"}`;

export function DeskLayout({ ns = "desk" }: { ns?: string }) {
  const [model, setModel] = useState<Model | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const layoutRef = useRef<Layout>(null);

  useEffect(() => {
    try {
      const saved = localStorage.getItem(KEY(ns));
      setModel(Model.fromJson(saved ? JSON.parse(saved) : DEFAULT_JSON));
    } catch { setModel(Model.fromJson(DEFAULT_JSON)); }
  }, [ns]);

  const onChange = (m: Model) => { try { localStorage.setItem(KEY(ns), JSON.stringify(m.toJson())); } catch {} };
  const reset = () => { try { localStorage.removeItem(KEY(ns)); } catch {} setModel(Model.fromJson(DEFAULT_JSON)); };
  const addPanel = (p: { component: string; name: string }) => {
    layoutRef.current?.addTabToActiveTabSet({ type: "tab", name: p.name, component: p.component });
    setAddOpen(false);
  };

  return (
    <div className="flex h-[calc(100vh-6.8rem)] flex-col">
      <div className="mb-2 flex items-center gap-2">
        <span className="panel-title">Dockable workspace</span>
        <span className="text-[11px] text-ink-600">— drag tab headers to rearrange · drag borders to resize · ⛶ maximize · ✕ close</span>
        <div className="relative ml-auto">
          <button onClick={() => setAddOpen((o) => !o)} className="aurora-btn py-1.5 text-xs">+ Add panel</button>
          {addOpen && (
            <div className="absolute right-0 z-30 mt-2 w-52 glass p-1.5">
              {PANELS.map((p) => (
                <button key={p.component} onClick={() => addPanel(p)}
                  className="block w-full rounded-lg px-3 py-1.5 text-left text-[12px] text-ink-200 hover:bg-surface/[0.08]">{p.name}</button>
              ))}
            </div>
          )}
        </div>
        <button onClick={reset} className="ghost-btn py-1.5 text-xs">Reset layout</button>
      </div>
      <div className="relative min-h-0 flex-1">
        {model && <Layout ref={layoutRef} model={model} factory={factory} onModelChange={onChange} />}
      </div>
    </div>
  );
}
