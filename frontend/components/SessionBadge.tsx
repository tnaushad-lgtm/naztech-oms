"use client";

import { useEffect, useState } from "react";
import { get } from "@/lib/api";
import { useLive } from "@/lib/useLive";

const TONE: Record<string, string> = {
  OPEN: "border-bull/40 bg-bull/10 text-bull",
  PRE_OPEN: "border-aurora-cyan/40 bg-aurora-cyan/10 text-aurora-cyan",
  HALTED: "border-amber-400/40 bg-amber-400/10 text-amber-400",
  CLOSED: "border-bear/40 bg-bear/10 text-bear",
};

const LABEL: Record<string, string> = {
  OPEN: "Market open",
  PRE_OPEN: "Pre-open",
  HALTED: "Market halted",
  CLOSED: "Market closed",
};

/**
 * The trading phase, on every screen.
 *
 * <p>Without this, a closed market is something a dealer discovers by clicking Buy and watching
 * nothing happen — which is exactly how it was found. The exchange's state is not a detail to go
 * looking for on an admin page; it decides whether anything a trader does will work at all.
 */
export function SessionBadge() {
  const [state, setState] = useState<string | null>(null);

  // The backend pushes "session" on every transition, so a halt lands here at once.
  useLive((ev, data) => { if (ev === "session" && data?.state) setState(data.state); });

  useEffect(() => {
    const load = () => get("/api/admin/session/status?exchange=DSE")
      .then((s: any) => setState(s.state))
      .catch(() => {});
    load();
    const t = setInterval(load, 15000);   // a slow poll, in case the stream drops
    return () => clearInterval(t);
  }, []);

  if (!state) return null;

  return (
    <div className={`hidden sm:flex items-center gap-2 rounded-full border px-3 py-1.5 ${TONE[state] || ""}`}
      title={state === "PRE_OPEN" ? "Orders rest on the book; nothing trades until the opening bell" : undefined}>
      <span className={`h-2 w-2 rounded-full ${state === "OPEN" ? "animate-pulseDot" : ""} bg-current`} />
      <span className="text-[11px] font-semibold">{LABEL[state] || state}</span>
    </div>
  );
}
