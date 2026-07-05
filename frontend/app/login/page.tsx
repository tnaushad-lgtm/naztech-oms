"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { motion } from "framer-motion";
import { Brand, BrandMark } from "@/components/Brand";
import { ThemeSwitcher } from "@/components/ThemeSwitcher";
import { post } from "@/lib/api";
import { saveSession } from "@/lib/session";

const DEMO = [
  { u: "investor1", label: "Investor (Client)", desc: "The public — view portfolio, place own orders, ask AI" },
  { u: "dealer1", label: "Dealer (Broker staff)", desc: "Trade & manage orders for clients" },
  { u: "rms", label: "RMS Manager", desc: "Risk limits & alerts" },
  { u: "exchadmin", label: "Exchange Admin", desc: "Control plane" },
];

export default function Login() {
  const router = useRouter();
  const [username, setUsername] = useState("dealer1");
  const [password, setPassword] = useState("demo123");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(u = username, p = password) {
    setErr(""); setBusy(true);
    try {
      const s = await post("/api/auth/login", { username: u, password: p });
      saveSession(s);
      router.replace(s.role === "EXCHANGE_ADMIN" ? "/admin" : s.role === "RMS_MANAGER" ? "/rms" : "/dashboard");
    } catch (e: any) {
      setErr(e.message || "Login failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="relative flex min-h-screen items-center justify-center overflow-hidden px-4">
      <div className="absolute right-4 top-4 z-20"><ThemeSwitcher /></div>
      {/* animated aurora orbs */}
      <motion.div
        className="pointer-events-none absolute -top-40 -left-32 h-[28rem] w-[28rem] rounded-full bg-aurora-violet/20 blur-[120px]"
        animate={{ y: [0, 30, 0], opacity: [0.6, 0.9, 0.6] }} transition={{ duration: 9, repeat: Infinity }} />
      <motion.div
        className="pointer-events-none absolute -bottom-40 -right-24 h-[26rem] w-[26rem] rounded-full bg-aurora-cyan/20 blur-[120px]"
        animate={{ y: [0, -26, 0], opacity: [0.5, 0.85, 0.5] }} transition={{ duration: 11, repeat: Infinity }} />

      <motion.div
        initial={{ opacity: 0, y: 24, scale: 0.98 }} animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.6, ease: "easeOut" }}
        className="glass relative z-10 w-full max-w-4xl overflow-hidden p-0 md:grid md:grid-cols-2">
        {/* left: brand story */}
        <div className="relative hidden flex-col justify-between p-8 md:flex bg-gradient-to-br from-aurora-violet/10 via-transparent to-aurora-cyan/10">
          <Brand />
          <div>
            <motion.h2
              initial={{ opacity: 0 }} animate={{ opacity: 1 }} transition={{ delay: 0.25 }}
              className="text-2xl font-bold leading-snug">
              The next-generation <span className="aurora-text">Exchange-Hosted OMS</span> for Bangladesh’s capital market.
            </motion.h2>
            <p className="mt-3 text-sm text-ink-400">
              Multi-asset order lifecycle, pre-trade risk controls, a pluggable matching gateway and
              on-prem AI — built for DSE &amp; CSE, by Naztech.
            </p>
            <div className="mt-6 flex flex-wrap gap-2">
              {["Equities", "Bonds", "Sukuk", "Mutual Funds", "Derivatives", "AI Risk"].map((t) => (
                <span key={t} className="chip bg-surface/[0.1] text-ink-300 border border-line/[0.1]">{t}</span>
              ))}
            </div>
          </div>
          <div className="text-[11px] text-ink-500">License &amp; AMC Model · RFP Ref DSE/CSD/APP/2026</div>
        </div>

        {/* right: form */}
        <div className="p-8">
          <div className="mb-6 flex items-center gap-2 md:hidden"><BrandMark /><span className="font-bold aurora-text">NAZTECH OMS</span></div>
          <h1 className="text-lg font-semibold">Sign in to the terminal</h1>
          <p className="mt-1 text-sm text-ink-400">Single-session, role-based access.</p>

          <form onSubmit={(e) => { e.preventDefault(); submit(); }} className="mt-6 space-y-4">
            <div>
              <label className="panel-title">Username</label>
              <input className="field mt-1.5" value={username} onChange={(e) => setUsername(e.target.value)} autoFocus />
            </div>
            <div>
              <label className="panel-title">Password</label>
              <input type="password" className="field mt-1.5" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            {err && <div className="rounded-lg bg-bear/10 px-3 py-2 text-sm text-bear">{err}</div>}
            <button className="aurora-btn w-full py-2.5" disabled={busy}>
              {busy ? "Signing in…" : "Sign in →"}
            </button>
          </form>

          <div className="mt-6">
            <div className="panel-title mb-2">Quick demo logins</div>
            <div className="grid gap-2">
              {DEMO.map((d) => (
                <button key={d.u} onClick={() => { setUsername(d.u); setPassword("demo123"); submit(d.u, "demo123"); }}
                  className="ghost-btn justify-between px-3 py-2 text-left">
                  <span className="flex flex-col items-start">
                    <span className="text-sm font-semibold text-ink-100">{d.label}</span>
                    <span className="text-[11px] text-ink-500">{d.desc}</span>
                  </span>
                  <span className="text-[11px] text-aurora-cyan">{d.u} · demo123</span>
                </button>
              ))}
            </div>
          </div>
        </div>
      </motion.div>
    </div>
  );
}
