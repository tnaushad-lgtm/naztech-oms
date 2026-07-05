"use client";

import { useEffect, useState } from "react";
import { useRouter, usePathname } from "next/navigation";
import Link from "next/link";
import { Brand } from "./Brand";
import { ThemeSwitcher } from "./ThemeSwitcher";
import { AiAdvisor } from "./AiAdvisor";
import { AlertToaster } from "./AlertToaster";
import { RoutingBadge } from "./RoutingBadge";
import { getSession, clearSession, Session } from "@/lib/session";

const NAV = [
  { href: "/dashboard", label: "My Dashboard", icon: "M4 4h7v7H4z M13 4h7v4h-7z M13 11h7v9h-7z M4 14h7v6H4z" },
  { href: "/terminal", label: "Trader Terminal", icon: "M3 13h4l2 5 4-12 2 7h5" },
  { href: "/order-bot", label: "AI Order Bot", icon: "M12 3a4 4 0 0 1 4 4v3a4 4 0 0 1-8 0V7a4 4 0 0 1 4-4z M5 11a7 7 0 0 0 14 0 M12 18v3" },
  { href: "/workspace", label: "Trading Desk", icon: "M4 4h7v16H4z M13 4h7v7h-7z M13 13h7v7h-7z" },
  { href: "/screener", label: "Market Screener", icon: "M3 5h18M6 12h12M10 19h4" },
  { href: "/heatmap", label: "Market Heatmap", icon: "M3 3h8v8H3z M13 3h8v5h-8z M13 10h8v11h-8z M3 13h8v8H3z" },
  { href: "/tape", label: "Trade Tape", icon: "M3 6h18M3 10h18M3 14h12M3 18h8" },
  { href: "/alerts", label: "Price Alerts", icon: "M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9 M13.7 21a2 2 0 0 1-3.4 0" },
  { href: "/portfolio", label: "Portfolio", icon: "M21 12a9 9 0 1 1-9-9v9z M12 3a9 9 0 0 1 9 9h-9z" },
  { href: "/reports", label: "Reports", icon: "M7 3h7l5 5v13H7z M14 3v5h5" },
  { href: "/rms", label: "Risk (RMS)", icon: "M12 3l8 4v5c0 5-3.5 8-8 9-4.5-1-8-4-8-9V7z" },
  { href: "/admin", label: "Exchange Admin", icon: "M4 6h16M4 12h16M4 18h10" },
  { href: "/admin/connectivity", label: "Exchange Link", icon: "M9 17H7A5 5 0 0 1 7 7h2 M15 7h2a5 5 0 0 1 0 10h-2 M8 12h8" },
  { href: "/admin/fix", label: "FIX Monitor", icon: "M4 5h16v14H4z M7 9l2 2-2 2 M12 13h5" },
];

export function Shell({
  children,
  connected,
  title,
  headerRight,
}: {
  children: React.ReactNode;
  connected?: boolean;
  title?: string;
  headerRight?: React.ReactNode;
}) {
  const router = useRouter();
  const pathname = usePathname();
  const [session, setSession] = useState<Session | null>(null);
  const [clock, setClock] = useState("");

  useEffect(() => {
    const s = getSession();
    if (!s) { router.replace("/login"); return; }
    setSession(s);
  }, [router]);

  useEffect(() => {
    const t = setInterval(
      () => setClock(new Date().toLocaleTimeString("en-GB", { hour12: false }) + " BST"),
      1000
    );
    return () => clearInterval(t);
  }, []);

  const logout = () => { clearSession(); router.replace("/login"); };

  return (
    <div className="flex h-screen overflow-hidden">
      {/* sidebar */}
      <aside className="hidden md:flex w-[230px] shrink-0 flex-col gap-1 border-r border-line/[0.1] bg-obsidian-900/50 backdrop-blur-xl px-3 py-4">
        <div className="px-2 pb-4"><Brand /></div>
        <nav className="flex flex-col gap-1">
          {NAV.map((n) => {
            const active = pathname === n.href;
            return (
              <Link key={n.href} href={n.href}
                className={`group flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm transition-all
                  ${active
                    ? "bg-gradient-to-r from-aurora-violet/20 to-aurora-indigo/10 text-ink-100 shadow-glow"
                    : "text-ink-400 hover:bg-surface/[0.07] hover:text-ink-100"}`}>
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none"
                  stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"
                  className={active ? "text-aurora-cyan" : "text-ink-500 group-hover:text-aurora-cyan"}>
                  <path d={n.icon} />
                </svg>
                <span className="font-medium">{n.label}</span>
              </Link>
            );
          })}
        </nav>

        <div className="mt-auto glass-soft p-3">
          <div className="text-[11px] text-ink-400">Signed in as</div>
          <div className="text-sm font-semibold text-ink-100 truncate">{session?.displayName}</div>
          <div className="mt-0.5 text-[11px] text-ink-500">{session?.role?.replace(/_/g, " ")}</div>
          {session?.brokerName && (
            <div className="text-[11px] text-aurora-cyan/80 truncate">{session.brokerName}</div>
          )}
          <button onClick={logout} className="ghost-btn mt-3 w-full justify-center py-1.5 text-xs">
            Sign out
          </button>
        </div>
      </aside>

      {/* main */}
      <div className="flex min-w-0 flex-1 flex-col">
        <header className="relative z-40 flex items-center gap-4 border-b border-line/[0.1] bg-obsidian-900/40 backdrop-blur-xl px-5 py-3">
          <div className="md:hidden"><Brand small /></div>
          <h1 className="text-sm font-semibold text-ink-200 tracking-tight">{title}</h1>
          <div className="ml-auto flex items-center gap-3">
            {headerRight}
            <RoutingBadge />
            <ThemeSwitcher />
            <div className="hidden sm:flex items-center gap-2 rounded-full border border-line/[0.1] bg-surface/[0.05] px-3 py-1.5">
              <span className={`h-2 w-2 rounded-full ${connected ? "bg-bull animate-pulseDot" : "bg-bear"}`} />
              <span className="text-[11px] font-medium text-ink-300">
                {connected ? "Live" : "Offline"}
              </span>
            </div>
            <div className="hidden lg:block tnum text-[12px] text-ink-400">{clock}</div>
          </div>
        </header>
        <main className="flex-1 overflow-auto p-4">{children}</main>
      </div>
      <AiAdvisor />
      <AlertToaster />
    </div>
  );
}
