"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import { post, API } from "@/lib/api";
import { getSession } from "@/lib/session";

type Msg = { role: "user" | "assistant"; text: string; source?: string; ai?: boolean; route?: string };

const SUGGESTIONS = [
  "Where do I find market depth?",
  "How do I place a buy order?",
  "Is GP in a good position today?",
  "Which sector is doing well?",
  "Am I in profit or loss?",
  "Top pharmaceutical stocks to consider",
  "Show me Z-category companies",
  "Is BRAC Bank good to buy?",
  "What are the different buy/sell order types?",
  "How much buying power do I have?",
];

/**
 * Split an answer for progressive TTS: a SMALL first chunk (fast start) then LARGER
 * chunks (fewer requests → far less chance of hitting Gemini's rate limit mid-answer).
 */
function splitChunks(text: string, firstMax: number, restMax: number): string[] {
  const sentences = text.split(/(?<=[।.!?\n])\s+/).filter((s) => s.trim());
  const out: string[] = [];
  let cur = "";
  let cap = firstMax;
  const flush = () => { if (cur.trim()) { out.push(cur.trim()); cur = ""; cap = restMax; } };
  for (let s of sentences) {
    while (s.length > cap) { if (cur) flush(); out.push(s.slice(0, cap)); s = s.slice(cap); cap = restMax; }
    if (cur && (cur + " " + s).length > cap) flush();
    cur = cur ? cur + " " + s : s;
  }
  flush();
  return out.filter((c) => c.trim().length > 0);
}

export function AiAdvisor() {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [msgs, setMsgs] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [lang, setLang] = useState<"en" | "bn">("en");
  const [hdVoice, setHdVoice] = useState(true);
  const [listening, setListening] = useState(false);
  const [image, setImage] = useState<{ b64: string; mime: string; name: string } | null>(null);
  const [speaking, setSpeaking] = useState(false);
  const recRef = useRef<any>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const session = typeof window !== "undefined" ? getSession() : null;

  useEffect(() => { scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: "smooth" }); }, [msgs, busy]);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  const audiosRef = useRef<HTMLAudioElement[]>([]);
  const tokenRef = useRef(0);

  const stopSpeaking = () => {
    tokenRef.current++;                       // invalidate pending chunk callbacks
    audiosRef.current.forEach((a) => { try { a.pause(); a.src = ""; } catch {} });
    audiosRef.current = [];
    audioRef.current = null;
    setSpeaking(false);
  };

  // On-demand playback (from a click). Streams the answer as short chunks and prefetches a
  // few ahead in parallel, so even the realistic Gemini (HD) voice starts in ~4-5s and then
  // plays smoothly. HD off uses the instant Google voice.
  const playAudio = (t: string) => {
    stopSpeaking();
    const token = ++tokenRef.current;
    const clean = t.replace(/[*#•>`_]/g, " ").replace(/\s+/g, " ").trim().slice(0, 2400);
    if (!clean) return;
    const chunks = splitChunks(clean, hdVoice ? 110 : 200, hdVoice ? 300 : 400);
    if (!chunks.length) return;
    setSpeaking(true);
    const buf: (HTMLAudioElement | null)[] = new Array(chunks.length).fill(null);
    const ensure = (idx: number) => {
      if (idx >= chunks.length || buf[idx]) return;
      const a = new Audio(`${API}/api/ai/tts?lang=${lang}&hd=${hdVoice}&text=${encodeURIComponent(chunks[idx])}`);
      a.preload = "auto"; try { a.load(); } catch {}
      buf[idx] = a; audiosRef.current.push(a);
    };
    ensure(0); ensure(1);                      // prime first 2 only → keeps concurrency low
    let i = 0;
    const playNext = () => {
      if (tokenRef.current !== token) return;
      if (i >= chunks.length) { setSpeaking(false); return; }
      const a = buf[i]!;
      audioRef.current = a;
      ensure(i + 2);                           // keep ~2 chunks in flight (low rate-limit pressure)
      i++;
      a.onended = playNext;
      a.onerror = playNext;                   // skip a failed chunk, keep going
      a.play().catch(playNext);
    };
    playNext();
  };

  const closeDrawer = () => { stopSpeaking(); if (listening) recRef.current?.stop(); setOpen(false); };

  function onPasteImage(e: React.ClipboardEvent) {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (let i = 0; i < items.length; i++) {
      if (items[i].type.startsWith("image/")) {
        const f = items[i].getAsFile();
        if (f) { onFile(f); e.preventDefault(); }
        break;
      }
    }
  }

  useEffect(() => () => { try { window.speechSynthesis?.cancel(); } catch {} }, []);

  async function send(text?: string, action?: string) {
    const message = (text ?? input).trim();
    if (!message && !action && !image) return;
    const shownUser = action === "DSE_STATUS" ? "📊 DSE market status, please" : (message || "Analyse this screenshot");
    setMsgs((m) => [...m, { role: "user", text: shownUser + (image ? "  🖼️" : "") }]);
    setInput(""); setBusy(true);
    const history = msgs.slice(-6).map((m) => ({ role: m.role, text: m.text }));
    const img = image; setImage(null);
    try {
      const r: any = await post("/api/ai/advisor", {
        message, accountId: session?.defaultAccountId, action, lang,
        imageBase64: img?.b64, imageMime: img?.mime, history,
      });
      const ans = r.answer || r.error || "Sorry, I couldn't get an answer.";
      setMsgs((m) => [...m, { role: "assistant", text: ans, source: r.source, ai: r.ai, route: r.route }]);
    } catch (e: any) {
      setMsgs((m) => [...m, { role: "assistant", text: "Error: " + (e.message || "request failed") }]);
    } finally { setBusy(false); }
  }

  function toggleMic() {
    const SR = (window as any).webkitSpeechRecognition || (window as any).SpeechRecognition;
    if (!SR) { alert("Voice input needs Chrome/Edge. You can still type your question."); return; }
    if (listening) { recRef.current?.stop(); return; }
    const rec = new SR();
    rec.lang = lang === "bn" ? "bn-BD" : "en-US";
    rec.interimResults = false; rec.maxAlternatives = 1;
    rec.onresult = (e: any) => { const t = e.results[0][0].transcript; setInput(t); setTimeout(() => send(t), 150); };
    rec.onend = () => setListening(false);
    rec.onerror = () => setListening(false);
    recRef.current = rec; setListening(true); rec.start();
  }

  function onFile(f?: File) {
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => { const b64 = String(reader.result).split(",")[1]; setImage({ b64, mime: f.type || "image/png", name: f.name }); };
    reader.readAsDataURL(f);
  }

  return (
    <>
      {/* floating launcher */}
      <button onClick={() => setOpen(true)}
        className="fixed bottom-5 right-5 z-40 flex items-center gap-2 rounded-full bg-gradient-to-r from-aurora-violet to-aurora-indigo px-4 py-3 text-sm font-semibold text-white shadow-glow hover:brightness-110 active:scale-95 transition-all">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
          <path d="M12 3l1.8 4.6L18 9l-4.2 1.4L12 15l-1.8-4.6L6 9l4.2-1.4z" strokeLinejoin="round" />
          <path d="M19 14l.7 1.8L21.5 16.5l-1.8.7L19 19l-.7-1.8L16.5 16.5l1.8-.7z" strokeLinejoin="round" />
        </svg>
        AI Advisor
      </button>

      <AnimatePresence>
        {open && (
          <motion.div initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}
            className="fixed inset-0 z-50 flex justify-end bg-obsidian-950/50 backdrop-blur-sm" onClick={closeDrawer}>
            <motion.div initial={{ x: 40, opacity: 0 }} animate={{ x: 0, opacity: 1 }} exit={{ x: 40, opacity: 0 }}
              transition={{ type: "spring", stiffness: 320, damping: 32 }}
              className="glass flex h-full w-full max-w-[880px] flex-col rounded-none rounded-l-2xl p-0"
              onClick={(e) => e.stopPropagation()}>
              {/* header */}
              <div className="flex items-center gap-2 border-b border-line/[0.1] px-4 py-3">
                <span className="grid h-8 w-8 place-items-center rounded-xl bg-gradient-to-br from-aurora-violet to-aurora-cyan text-white">✦</span>
                <div className="min-w-0">
                  <div className="text-sm font-bold text-ink-100">AI Investment Advisor</div>
                  <div className="text-[10px] text-ink-500">Grounded in your live portfolio &amp; market data</div>
                </div>
                <div className="ml-auto flex items-center gap-1">
                  <button onClick={() => setLang((l) => (l === "en" ? "bn" : "en"))} title="Language for answers & voice (English / বাংলা)"
                    className="ghost-btn px-2 py-1 text-[11px]">{lang === "en" ? "EN" : "বাং"}</button>
                  <button onClick={() => { stopSpeaking(); setHdVoice((v) => !v); }}
                    title={hdVoice ? "Voice: HD (Gemini, realistic). Tap for instant voice." : "Voice: Fast (instant). Tap for realistic HD voice."}
                    className={`ghost-btn px-2 py-1 text-[10px] font-bold ${hdVoice ? "text-aurora-cyan" : "text-ink-400"}`}>{hdVoice ? "HD🔊" : "FAST"}</button>
                  <button onClick={closeDrawer} className="ghost-btn px-2 py-1">✕</button>
                </div>
              </div>

              {/* messages */}
              <div ref={scrollRef} className="flex-1 space-y-3 overflow-auto p-4">
                {msgs.length === 0 && (
                  <div className="rounded-2xl border border-line/[0.08] bg-surface/[0.04] p-3 text-[13px] text-ink-300">
                    👋 Ask me about any DSE/CSE stock, your portfolio &amp; P&amp;L, sectors, share categories, or order types —
                    by text or 🎤 voice (English/বাংলা). You can also upload a trading screenshot or tap <b>DSE Status</b>.
                    Use the suggestion chips below the input anytime.
                    <div className="mt-1 text-[11px] text-ink-500">Informational only — not licensed financial advice.</div>
                  </div>
                )}
                {msgs.map((m, i) => (
                  <div key={i} className={`flex ${m.role === "user" ? "justify-end" : "justify-start"}`}>
                    <div className={`max-w-[88%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-[13px] leading-relaxed ${
                      m.role === "user" ? "bg-gradient-to-r from-aurora-violet to-aurora-indigo text-white"
                                        : "border border-line/[0.08] bg-surface/[0.05] text-ink-200"}`}>
                      {m.text}
                      {m.role === "assistant" && (
                        <div className="mt-1.5 flex flex-wrap items-center gap-2">
                          {/* When the answer is "it is on this screen", take them there — an answer a
                              user still has to go hunting for is only half an answer. */}
                          {m.route && (
                            <button onClick={() => { setOpen(false); router.push(m.route!); }}
                              className="flex items-center gap-1 rounded-md bg-gradient-to-r from-aurora-violet to-aurora-indigo px-2 py-0.5 text-[10px] font-semibold text-white hover:brightness-110">
                              Take me there →
                            </button>
                          )}
                          <button onClick={() => playAudio(m.text)}
                            className="flex items-center gap-1 rounded-md bg-surface/[0.06] px-1.5 py-0.5 text-[10px] text-ink-400 hover:text-aurora-cyan">
                            🔊 Listen
                          </button>
                          {m.source && <span className="text-[9.5px] text-ink-600">{m.ai ? "✦ " : "⚙ "}{m.source}</span>}
                        </div>
                      )}
                    </div>
                  </div>
                ))}
                {busy && <div className="flex justify-start"><div className="rounded-2xl border border-line/[0.08] bg-surface/[0.05] px-3 py-2 text-[13px] text-ink-400 animate-pulseDot">thinking…</div></div>}
              </div>

              {/* image chip */}
              {image && (
                <div className="mx-4 mb-1 flex items-center gap-2 rounded-lg border border-line/[0.1] bg-surface/[0.05] px-3 py-1.5 text-[11px] text-ink-400">
                  🖼️ {image.name}<button onClick={() => setImage(null)} className="ml-auto hover:text-bear">remove</button>
                </div>
              )}

              {/* actions + input */}
              <div className="border-t border-line/[0.1] p-3">
                <div className="mb-2 flex gap-2 overflow-x-auto pb-1">
                  {SUGGESTIONS.map((s) => (
                    <button key={s} onClick={() => send(s)}
                      className="chip shrink-0 whitespace-nowrap border border-line/[0.1] bg-surface/[0.05] text-ink-300 hover:bg-surface/[0.1]">{s}</button>
                  ))}
                </div>
                <div className="mb-2 flex items-center gap-2">
                  <button onClick={() => send(undefined, "DSE_STATUS")} className="aurora-btn px-3 py-1.5 text-xs">📊 DSE Status</button>
                  <button onClick={() => fileRef.current?.click()} className="ghost-btn px-3 py-1.5 text-xs">🖼️ Screenshot</button>
                  <input ref={fileRef} type="file" accept="image/*" className="hidden" onChange={(e) => onFile(e.target.files?.[0])} />
                  {speaking && (
                    <button onClick={stopSpeaking}
                      className="ml-auto flex items-center gap-1.5 rounded-xl bg-bear/20 px-3 py-1.5 text-xs font-semibold text-bear animate-pulseDot hover:bg-bear/30">
                      <span className="text-[10px]">⏹</span> Stop voice
                    </button>
                  )}
                </div>
                <div className="flex items-end gap-2">
                  <textarea value={input} onChange={(e) => setInput(e.target.value)} rows={1}
                    onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
                    onPaste={onPasteImage}
                    placeholder={lang === "bn" ? "প্রশ্ন লিখুন, 🎤 চাপুন বা স্ক্রিনশট পেস্ট (Ctrl+V) করুন…" : "Ask anything, tap 🎤, or paste a screenshot (Ctrl+V)…"}
                    className="field max-h-28 flex-1 resize-none py-2" />
                  <button onClick={toggleMic} title="Voice input"
                    className={`grid h-10 w-10 place-items-center rounded-xl border border-line/[0.12] ${listening ? "bg-bear/20 text-bear animate-pulseDot" : "bg-surface/[0.05] text-ink-300 hover:bg-surface/[0.1]"}`}>🎤</button>
                  <button onClick={() => send()} disabled={busy} className="aurora-btn h-10 w-10 p-0">→</button>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
