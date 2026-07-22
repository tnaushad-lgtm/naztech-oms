"use client";

/**
 * A floating window with real window management: move, resize from any edge or corner, minimise,
 * maximise, restore, reset, and close.
 *
 * Built for the order grid, so a trader can pull entry over the depth ladder or the blotter without
 * navigating away. Deliberately a floating window rather than a modal — a modal would black out the
 * very prices the trader opened entry to trade against.
 *
 * Three states, and the title bar with its controls is rendered in ALL of them. An earlier version
 * lost its controls once maximised, which is the one state you cannot escape without them.
 *
 *   normal     — remembered geometry, drag to move, eight resize handles
 *   maximised  — fills the viewport, restores to the exact previous rectangle
 *   minimised  — collapses to the title bar alone and parks bottom-left, out of the way but visible
 *
 * Geometry persists per `id` and is clamped back into view on mount: a window left at x=1800 on an
 * office monitor must not be invisible on a laptop the next morning.
 *
 * RENDERED THROUGH A PORTAL, and that is load-bearing. The launcher is mounted inside Shell's header,
 * which carries `backdrop-blur-xl` — and any ancestor with a backdrop-filter (or transform, or
 * filter) becomes the containing block for `position: fixed` descendants. Left in place, "fixed"
 * was measured from the header rather than the viewport: maximise produced x=238 w=1664 instead of
 * x=0 w=1680, and minimise parked the window near the top of the screen instead of the bottom.
 * Portalling to document.body puts it back in the viewport's coordinate space.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";

type Box = { x: number; y: number; w: number; h: number };
type Mode = "normal" | "max" | "min";
/** Which edge(s) a drag is resizing. "" means the drag is a move, not a resize. */
type Edge = "" | "n" | "s" | "e" | "w" | "ne" | "nw" | "se" | "sw";

const MIN_VISIBLE = 160;

function clampToView(b: Box, minW: number, minH: number): Box {
  if (typeof window === "undefined") return b;
  const vw = window.innerWidth, vh = window.innerHeight;
  const w = Math.max(minW, Math.min(b.w, vw - 24));
  const h = Math.max(minH, Math.min(b.h, vh - 24));
  return {
    w, h,
    x: Math.min(Math.max(b.x, -w + MIN_VISIBLE), vw - MIN_VISIBLE),
    y: Math.min(Math.max(b.y, 0), vh - 44),
  };
}

export function FloatingPanel({
  id, title, subtitle, open, onClose,
  initial = { x: 120, y: 90, w: 1320, h: 560 },
  minW = 620, minH = 200,
  children,
}: {
  id: string;
  title: string;
  subtitle?: React.ReactNode;
  open: boolean;
  onClose: () => void;
  initial?: Box;
  minW?: number;
  minH?: number;
  children: React.ReactNode;
}) {
  const KEY = `oms_float_${id}`;
  const [box, setBox] = useState<Box>(initial);
  const [mode, setMode] = useState<Mode>("normal");
  const restoreBox = useRef<Box>(initial);
  const gesture = useRef<{ edge: Edge; sx: number; sy: number; start: Box } | null>(null);

  useEffect(() => {
    try {
      const raw = localStorage.getItem(KEY);
      const b = clampToView(raw ? JSON.parse(raw) : initial, minW, minH);
      setBox(b);
      restoreBox.current = b;
    } catch {
      const b = clampToView(initial, minW, minH);
      setBox(b); restoreBox.current = b;
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const persist = useCallback((b: Box) => {
    try { localStorage.setItem(KEY, JSON.stringify(b)); } catch {}
  }, [KEY]);

  // Pointer events so pen and touch work too, listened on the window so a fast drag cannot outrun
  // the handle and strand the window mid-gesture.
  useEffect(() => {
    if (!open) return;
    const move = (e: PointerEvent) => {
      const g = gesture.current;
      if (!g) return;
      const dx = e.clientX - g.sx, dy = e.clientY - g.sy;
      const s = g.start;

      if (g.edge === "") {                                   // move
        setBox(clampToView({ ...s, x: s.x + dx, y: s.y + dy }, minW, minH));
        return;
      }
      let { x, y, w, h } = s;
      if (g.edge.includes("e")) w = Math.max(minW, s.w + dx);
      if (g.edge.includes("s")) h = Math.max(minH, s.h + dy);
      if (g.edge.includes("w")) {                            // dragging the left edge moves x too
        const nw = Math.max(minW, s.w - dx);
        x = s.x + (s.w - nw);
        w = nw;
      }
      if (g.edge.includes("n")) {
        const nh = Math.max(minH, s.h - dy);
        y = s.y + (s.h - nh);
        h = nh;
      }
      setBox({ x, y, w, h });
    };
    const up = () => {
      if (gesture.current) setBox((b) => { persist(b); restoreBox.current = b; return b; });
      gesture.current = null;
      document.body.style.userSelect = "";
      document.body.style.cursor = "";
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
    return () => { window.removeEventListener("pointermove", move); window.removeEventListener("pointerup", up); };
  }, [open, minW, minH, persist]);

  // Esc closes, but never while a field inside is focused — a reflexive Esc to dismiss a dropdown
  // must not discard a half-typed batch of orders.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      const el = document.activeElement as HTMLElement | null;
      if (el && (el.tagName === "INPUT" || el.tagName === "SELECT" || el.isContentEditable)) return;
      onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);      // portals need a DOM; SSR has none

  if (!open || !mounted) return null;

  const begin = (edge: Edge) => (e: React.PointerEvent) => {
    if (mode !== "normal") return;                           // no move/resize while max or min
    if ((e.target as HTMLElement).closest("button")) return; // buttons are not drag handles
    gesture.current = { edge, sx: e.clientX, sy: e.clientY, start: box };
    document.body.style.userSelect = "none";
  };

  const toMax = () => { if (mode === "normal") restoreBox.current = box; setMode("max"); };
  const toMin = () => { if (mode === "normal") restoreBox.current = box; setMode("min"); };
  const toNormal = () => { setBox(clampToView(restoreBox.current, minW, minH)); setMode("normal"); };
  const reset = () => {
    const b = clampToView(initial, minW, minH);
    setBox(b); restoreBox.current = b; setMode("normal"); persist(b);
  };

  const geom: React.CSSProperties =
    mode === "max" ? { left: 8, top: 8, width: "calc(100vw - 16px)", height: "calc(100vh - 16px)" }
    : mode === "min" ? { left: 12, bottom: 12, width: 340, height: "auto" }
    : { left: box.x, top: box.y, width: box.w, height: box.h };

  const btn = "rounded px-2 py-0.5 text-[13px] leading-none text-ink-300 hover:bg-white/10";

  return createPortal(
    <div
      role="dialog"
      aria-label={title}
      style={geom}
      className="fixed z-[60] flex flex-col overflow-hidden rounded-xl border border-aurora-cyan/30 bg-obsidian-900/95 shadow-[0_24px_80px_rgba(0,0,0,0.6)] backdrop-blur-xl"
    >
      {/* Title bar — present in every mode, so the controls can never become unreachable. */}
      <div
        onPointerDown={begin("")}
        onDoubleClick={() => (mode === "normal" ? toMax() : toNormal())}
        className={`flex shrink-0 items-center gap-2 border-b border-line bg-obsidian-850/90 px-3 py-2 ${
          mode === "normal" ? "cursor-move" : ""
        }`}
      >
        <span className="flex gap-1.5">
          <span className="h-2.5 w-2.5 rounded-full bg-aurora-cyan/70" />
          <span className="h-2.5 w-2.5 rounded-full bg-aurora-violet/60" />
        </span>
        <span className="whitespace-nowrap text-[12px] font-semibold uppercase tracking-wider text-ink-100">{title}</span>
        {subtitle && mode !== "min" && <span className="truncate text-[11px] text-ink-400">{subtitle}</span>}

        <span className="ml-auto flex items-center gap-0.5">
          <button onClick={reset} title="Reset size and position" className={btn}>⟲</button>
          {mode === "min" ? (
            <button onClick={toNormal} title="Restore" className={btn}>▣</button>
          ) : (
            <button onClick={toMin} title="Minimise" className={btn}>—</button>
          )}
          {mode === "max" ? (
            <button onClick={toNormal} title="Restore down" className={btn}>❐</button>
          ) : (
            <button onClick={toMax} title="Maximise" className={btn}>▢</button>
          )}
          <button onClick={onClose} title="Close (Esc)"
            className="rounded px-2 py-0.5 text-[13px] leading-none text-ink-300 hover:bg-bear/25 hover:text-bear">✕</button>
        </span>
      </div>

      {mode !== "min" && <div className="min-h-0 flex-1 overflow-hidden">{children}</div>}

      {/* Eight resize handles. Edges are 6px strips; corners are 14px squares that sit above them. */}
      {mode === "normal" && (
        <>
          <div onPointerDown={begin("n")} className="absolute left-0 right-0 top-0 h-[6px] cursor-ns-resize" />
          <div onPointerDown={begin("s")} className="absolute bottom-0 left-0 right-0 h-[6px] cursor-ns-resize" />
          <div onPointerDown={begin("w")} className="absolute bottom-0 left-0 top-0 w-[6px] cursor-ew-resize" />
          <div onPointerDown={begin("e")} className="absolute bottom-0 right-0 top-0 w-[6px] cursor-ew-resize" />
          <div onPointerDown={begin("nw")} className="absolute left-0 top-0 h-[14px] w-[14px] cursor-nwse-resize" />
          <div onPointerDown={begin("ne")} className="absolute right-0 top-0 h-[14px] w-[14px] cursor-nesw-resize" />
          <div onPointerDown={begin("sw")} className="absolute bottom-0 left-0 h-[14px] w-[14px] cursor-nesw-resize" />
          <div
            onPointerDown={begin("se")}
            title="Drag to resize"
            className="absolute bottom-0 right-0 h-[16px] w-[16px] cursor-nwse-resize"
            style={{
              background:
                "linear-gradient(135deg, transparent 0 50%, rgb(var(--ink-500) / 0.75) 50% 60%, transparent 60% 70%, rgb(var(--ink-500) / 0.75) 70% 80%, transparent 80%)",
            }}
          />
        </>
      )}
    </div>,
    document.body,
  );
}
