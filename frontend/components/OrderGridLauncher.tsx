"use client";

/**
 * Opens the multi-row order grid as a floating window over whatever the trader is already looking at.
 *
 * Mounted in Shell, so it is available from every screen: the point of floating entry is that you can
 * pull it over the depth ladder or the blotter without navigating away and losing your place. On the
 * /order-grid route itself the launcher hides — offering to float a screen you are already standing
 * on is noise.
 *
 * The grid is lazy-loaded: it is a large component and most page loads never open it.
 */

import dynamic from "next/dynamic";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { FloatingPanel } from "./FloatingPanel";
import { ORDER_INTENT, OrderIntent } from "@/lib/orderIntent";

const OrderGridBody = dynamic(
  () => import("./grid/OrderGridBody").then((m) => m.OrderGridBody),
  { ssr: false, loading: () => <div className="p-4 text-[12px] text-ink-400">Loading order grid…</div> },
);

export function OrderGridLauncher() {
  const [open, setOpen] = useState(false);
  // `nonce` makes two clicks on the SAME price still register as two intents.
  const [seed, setSeed] = useState<(OrderIntent & { nonce: number }) | null>(null);
  const pathname = usePathname();

  useEffect(() => {
    const onIntent = (e: Event) => {
      const d = (e as CustomEvent<OrderIntent>).detail;
      if (!d?.securityId) return;
      setSeed({ ...d, nonce: Date.now() });
      setOpen(true);
    };
    window.addEventListener(ORDER_INTENT, onIntent as EventListener);
    return () => window.removeEventListener(ORDER_INTENT, onIntent as EventListener);
  }, []);

  // Ctrl+Shift+O — a chord, deliberately. A bare key would fire while typing a ticker.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey && e.shiftKey && (e.key === "O" || e.key === "o")) {
        e.preventDefault();
        setOpen((v) => !v);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  if (pathname === "/order-grid" || pathname === "/login") return null;

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        title="Multi-order entry grid — opens as a floating window over this screen (Ctrl+Shift+O)"
        className="hidden items-center gap-1.5 rounded-full border border-aurora-cyan/35 bg-aurora-cyan/10 px-3 py-1.5 text-[11px] font-semibold text-aurora-cyan hover:bg-aurora-cyan/20 sm:flex"
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" className="h-3.5 w-3.5">
          <path d="M3 5h18v14H3z M3 10h18 M3 15h18 M9 5v14 M15 5v14" />
        </svg>
        Order Grid
      </button>

      {/*
        Narrowed from 1010 to 760 once the columns shrank. The two have to move together: shrinking
        the columns alone just adds dead space inside the same window, and the point of the exercise
        is the market the dealer can still see BEHIND this panel.

        The id doubles as the geometry storage key, so it carries a version. Anyone who has opened
        this panel before has 1010x340 saved and would keep it — seeing none of the narrowing, and
        reasonably concluding nothing had changed. Bumping the id retires the old box once.
      */}
      <FloatingPanel
        id="order-grid-v2"
        title="Order Grid"
        subtitle="quick multi-order entry"
        open={open}
        onClose={() => setOpen(false)}
        initial={{ x: 140, y: 120, w: 780, h: 340 }}
        minW={600}
        minH={200}
      >
        {/* compact: client name, side, ticker, MKT/LMT, price, qty — identifiers live in tooltips */}
        <OrderGridBody onClose={() => setOpen(false)} compact seed={seed} />
      </FloatingPanel>
    </>
  );
}
