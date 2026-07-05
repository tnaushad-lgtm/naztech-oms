"use client";

import { Shell } from "@/components/Shell";
import { AiSearch } from "@/components/AiSearch";
import { TerminalProvider, useTerminal } from "@/lib/terminalStore";
import { DeskLayout } from "@/components/desk/DeskLayout";

function Desk() {
  const t = useTerminal();
  const header = (
    <div className="flex items-center gap-3">
      <AiSearch onPick={t.onPickFromSearch} />
      <div className="hidden sm:flex rounded-xl border border-line/[0.12] bg-surface/[0.05] p-0.5">
        {["DSE", "CSE"].map((ex) => (
          <button key={ex} onClick={() => t.setExchange(ex)}
            className={`rounded-lg px-3 py-1.5 text-xs font-semibold transition-all ${t.exchange === ex ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white shadow-glow" : "text-ink-400 hover:text-ink-100"}`}>
            {ex}
          </button>
        ))}
      </div>
    </div>
  );
  return (
    <Shell title="Trading Desk" connected={t.connected} headerRight={header}>
      <DeskLayout />
    </Shell>
  );
}

export default function Workspace() {
  return (
    <TerminalProvider>
      <Desk />
    </TerminalProvider>
  );
}
