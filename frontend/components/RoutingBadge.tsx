"use client";

import { useEffect, useState } from "react";
import { get } from "@/lib/api";

type Status = { mode?: string; fix?: { enabled?: boolean; loggedOn?: boolean; targetCompId?: string } };

/**
 * Always-visible indicator of where orders actually route.
 *   exchange.mode=dse-cert/dse-prod → out over FIX to FIXSIM
 *   exchange.mode=simulator (default) → matched in-house, NOT sent to FIX
 * This prevents the "FIX session is green but my order never reached FIXSIM" confusion:
 * fix.enabled can log the session on even while matching stays on the simulator.
 */
export function RoutingBadge() {
  const [st, setSt] = useState<Status | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => get<Status>("/api/admin/connectivity/status").then((d) => { if (alive) setSt(d); }).catch(() => {});
    load();
    const t = setInterval(load, 10000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  if (!st) return null;
  const mode = (st.mode || "simulator").toLowerCase();
  const fixRouting = mode === "dse-cert" || mode === "dse-prod";
  const loggedOn = !!st.fix?.loggedOn;
  const target = st.fix?.targetCompId || "FIXSIM";

  const s = fixRouting
    ? (loggedOn
        ? { c: "#22c55e", label: `Orders → ${target}`, title: `Live FIX routing: orders are sent over FIX to ${target} (session logged on).` }
        : { c: "#f5b43c", label: `FIX → ${target} · logon…`, title: `FIX routing is selected but the session is not logged on yet — orders will queue until logon completes.` })
    : { c: "#f5b43c", label: "SIMULATOR · local match", title: "exchange.mode=simulator — orders are matched by the in-house engine and are NOT sent to FIXSIM. Restart the backend with connect-dse-sim.bat (exchange.mode=dse-cert) to route orders over FIX." };

  return (
    <div title={s.title}
      className="hidden sm:flex items-center gap-1.5 rounded-full border px-3 py-1.5"
      style={{ borderColor: s.c + "66", background: s.c + "1a", color: s.c }}>
      <span className="h-2 w-2 rounded-full" style={{ background: s.c }} />
      <span className="text-[11px] font-semibold tracking-tight">{s.label}</span>
    </div>
  );
}
