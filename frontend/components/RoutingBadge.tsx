"use client";

import { useEffect, useRef, useState } from "react";
import { get } from "@/lib/api";

type Status = {
  mode?: string;
  fix?: { enabled?: boolean; loggedOn?: boolean; targetCompId?: string };
  itch?: { enabled?: boolean; transport?: string; feed?: { delivered?: number; healthy?: boolean; lost?: number } };
};

type Pill = { c: string; label: string; title: string };
const GREEN = "#22c55e", AMBER = "#f5b43c", RED = "#f5556d", GREY = "#8b93a5";

/**
 * The exchange link, on every screen: is order entry (FIX) up, and is market data (ITCH) flowing?
 *
 * <p>When the OMS is wired to a real venue (nFIX / DSE), the dealer needs to know at a glance whether
 * the exchange is actually there — orders route over FIX, prices arrive over ITCH, and either can drop
 * without the other. This shows both, and calls out the case that matters most: the venue is <b>off</b>
 * (FIX down and the ITCH feed has stopped advancing). The ITCH check is live-delta, not a flag: the
 * feed can hold a socket open and still be silent, so "flowing" means the message count is actually
 * going up between polls.
 */
export function RoutingBadge() {
  const [st, setSt] = useState<Status | null>(null);
  const [itchLive, setItchLive] = useState<boolean | null>(null);
  const prev = useRef<{ delivered: number; at: number }>({ delivered: -1, at: 0 });

  useEffect(() => {
    let alive = true;
    const load = () =>
      get<Status>("/api/admin/connectivity/status")
        .then((d) => {
          if (!alive) return;
          setSt(d);
          const delivered = d.itch?.feed?.delivered ?? -1;
          if (delivered >= 0) {
            // Advancing since last poll → live. Unchanged → stalled (venue quiet or down).
            if (prev.current.delivered >= 0) setItchLive(delivered > prev.current.delivered);
            prev.current = { delivered, at: Date.now() };
          } else {
            setItchLive(null);
          }
        })
        .catch(() => { if (alive) { setSt(null); setItchLive(false); } });
    load();
    const t = setInterval(load, 4000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  if (!st) return null;

  const mode = (st.mode || "simulator").toLowerCase();
  const liveVenue = mode === "dse-cert" || mode === "dse-prod";
  const target = st.fix?.targetCompId || "exchange";

  // ---- FIX pill ----
  const fixOn = !!st.fix?.loggedOn;
  const fix: Pill = !liveVenue
    ? { c: GREY, label: "SIM", title: "Simulator mode — orders match in-house, not over FIX." }
    : fixOn
      ? { c: GREEN, label: `FIX ✓ ${target}`, title: `FIX order entry logged on to ${target}. Orders route live.` }
      : { c: AMBER, label: "FIX logon…", title: `FIX order entry not logged on to ${target} yet — orders queue until it connects.` };

  // ---- ITCH pill ----
  const itchEnabled = !!st.itch?.enabled;
  const itch: Pill = !itchEnabled
    ? { c: GREY, label: "ITCH off", title: "ITCH market-data feed is disabled." }
    : itchLive === true
      ? { c: GREEN, label: "ITCH ✓ live", title: `Market data flowing (${st.itch?.transport}), ${st.itch?.feed?.delivered?.toLocaleString()} messages.` }
      : itchLive === false
        ? { c: RED, label: "ITCH stalled", title: "ITCH feed is not advancing — the venue may be down or the market is closed." }
        : { c: AMBER, label: "ITCH…", title: "Checking the ITCH feed…" };

  // ---- venue-offline call-out: both channels down on a live venue = nFIX is off ----
  const venueOff = liveVenue && !fixOn && itchLive === false;

  return (
    <div className="hidden sm:flex items-center gap-1.5">
      {venueOff && (
        <div title={`No FIX logon and the ITCH feed has stopped — ${target} appears to be OFFLINE.`}
          className="flex items-center gap-1.5 rounded-full border px-2.5 py-1.5 animate-pulseDot"
          style={{ borderColor: RED + "66", background: RED + "1a", color: RED }}>
          <span className="h-2 w-2 rounded-full" style={{ background: RED }} />
          <span className="text-[11px] font-bold tracking-tight">{target} OFFLINE</span>
        </div>
      )}
      <Chip pill={fix} />
      <Chip pill={itch} />
    </div>
  );
}

function Chip({ pill }: { pill: Pill }) {
  return (
    <div title={pill.title}
      className="flex items-center gap-1.5 rounded-full border px-2.5 py-1.5"
      style={{ borderColor: pill.c + "66", background: pill.c + "1a", color: pill.c }}>
      <span className="h-2 w-2 rounded-full" style={{ background: pill.c }} />
      <span className="text-[11px] font-semibold tracking-tight">{pill.label}</span>
    </div>
  );
}
