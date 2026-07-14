"use client";

import { useCallback, useRef, useState } from "react";
import { API, post, get } from "./api";

export type VoiceState = "idle" | "connecting" | "live" | "error";

export type VoiceTurn = { role: "user" | "assistant"; text: string; partial?: boolean };

/**
 * Live speech-to-speech with ChatGPT — the thing that makes the OMS feel like it is listening rather
 * than processing.
 *
 * The OMS already had a "voice" feature, and this is not a better version of it — it is a different
 * shape entirely. The old one is a relay: the browser transcribes what you said, posts the text, waits
 * for an answer, then asks a server to read it aloud. Four hops, each one waiting on the last, and you
 * cannot interrupt any of them. Here the audio goes straight from the microphone to the model over
 * WebRTC and comes back as speech; the model hears you hesitate, and it stops talking the moment you
 * start. The audio never touches our backend, because relaying it would put back exactly the delay the
 * feature exists to remove.
 *
 * What our backend DOES do is mint the credential. It exchanges our real OpenAI key for a ten-minute
 * ephemeral token scoped to one session, and only that token reaches the browser — the real key would
 * otherwise sit in the page source of every terminal on the desk.
 */
export function useRealtimeVoice() {
  const [state, setState] = useState<VoiceState>("idle");
  const [error, setError] = useState<string>("");
  const [turns, setTurns] = useState<VoiceTurn[]>([]);
  const [speaking, setSpeaking] = useState(false);   // the model is talking
  const [listening, setListening] = useState(false); // the dealer is talking

  /**
   * How loud the microphone actually is, 0–1.
   *
   * This exists because of a real hour lost: Windows had the default input device set to a VB-Audio
   * virtual cable — a loopback with nothing on it — so the browser happily captured silence. The
   * greeting played, the dealer spoke, and nothing happened. Everything was "working". A meter would
   * have said "your microphone is producing no sound" in one glance, so now there is one.
   */
  const [micLevel, setMicLevel] = useState(0);
  /** True once we have been live a while and have heard nothing at all — almost always a wrong device. */
  const [micSilent, setMicSilent] = useState(false);
  /** The microphone Windows actually gave us, so a wrong one can be recognised on sight. */
  const [micLabel, setMicLabel] = useState("");

  const pcRef = useRef<RTCPeerConnection | null>(null);
  const dcRef = useRef<RTCDataChannel | null>(null);
  const micRef = useRef<MediaStream | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const assistantRef = useRef<string>("");
  const acRef = useRef<AudioContext | null>(null);
  const rafRef = useRef<number>(0);

  /** The model asked for a live price. Answer it from the OMS's own market data, not a second source. */
  const runTool = useCallback(async (name: string, argsJson: string): Promise<string> => {
    if (name !== "get_quote") return JSON.stringify({ error: `unknown tool ${name}` });
    try {
      const { symbol } = JSON.parse(argsJson || "{}");
      const rows = await get<any[]>(`/api/market/watch?exchange=DSE`);
      const hit = rows.find((r) => r.symbol?.toUpperCase() === String(symbol || "").toUpperCase());
      if (!hit) return JSON.stringify({ error: `No instrument called ${symbol} on the DSE board.` });
      return JSON.stringify({
        symbol: hit.symbol, name: hit.name, price: hit.ltp,
        changePct: hit.changePct, volume: hit.volume, currency: "BDT",
      });
    } catch (e: any) {
      return JSON.stringify({ error: e?.message || "quote lookup failed" });
    }
  }, []);

  const send = (obj: any) => dcRef.current?.readyState === "open" && dcRef.current.send(JSON.stringify(obj));

  const stop = useCallback(() => {
    cancelAnimationFrame(rafRef.current);
    acRef.current?.close().catch(() => {});
    dcRef.current?.close();
    pcRef.current?.close();
    micRef.current?.getTracks().forEach((t) => t.stop());
    audioRef.current?.remove();
    acRef.current = null;
    dcRef.current = null;
    pcRef.current = null;
    micRef.current = null;
    audioRef.current = null;
    setState("idle");
    setSpeaking(false);
    setListening(false);
    setMicLevel(0);
    setMicSilent(false);
  }, []);

  /** Watch the microphone's actual amplitude, so "it can't hear me" becomes a thing you can see. */
  const meter = (stream: MediaStream) => {
    const ac = new AudioContext();
    acRef.current = ac;
    const analyser = ac.createAnalyser();
    analyser.fftSize = 512;
    ac.createMediaStreamSource(stream).connect(analyser);
    const buf = new Uint8Array(analyser.frequencyBinCount);

    const startedAt = Date.now();
    let peak = 0;
    const tick = () => {
      analyser.getByteTimeDomainData(buf);
      // Root-mean-square around the 128 midpoint — a fair measure of loudness, unlike peak alone.
      let sum = 0;
      for (let i = 0; i < buf.length; i++) {
        const v = (buf[i] - 128) / 128;
        sum += v * v;
      }
      const level = Math.min(1, Math.sqrt(sum / buf.length) * 4);
      setMicLevel(level);
      peak = Math.max(peak, level);

      // Ten seconds of live audio with no signal at all is not shyness — it is the wrong device.
      if (Date.now() - startedAt > 10_000) setMicSilent(peak < 0.02);

      rafRef.current = requestAnimationFrame(tick);
    };
    tick();
  };

  const start = useCallback(async (accountId?: number, lang: "en" | "bn" = "en") => {
    if (pcRef.current) return;
    setState("connecting");
    setError("");
    setTurns([]);

    try {
      // 1. Our backend mints a short-lived token. The real API key stays on the server.
      const s = await post<{ token: string; model: string; voice: string }>(
        "/api/ai/realtime/session", { accountId, lang });

      // 2. A plain WebRTC peer connection, straight to OpenAI.
      const pc = new RTCPeerConnection();
      pcRef.current = pc;

      // The model's voice arrives as a remote track.
      const audio = document.createElement("audio");
      audio.autoplay = true;
      audioRef.current = audio;
      pc.ontrack = (e) => { audio.srcObject = e.streams[0]; };

      // The dealer's microphone goes the other way.
      const mic = await navigator.mediaDevices.getUserMedia({
        // Ask for the treatment a speakerphone gets: without echo cancellation the model hears its own
        // voice through the laptop speakers and interrupts itself, which is as absurd as it sounds.
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      });
      micRef.current = mic;
      pc.addTrack(mic.getTracks()[0]);
      setMicLabel(mic.getAudioTracks()[0]?.label || "");
      meter(mic);

      // 3. Events (transcripts, tool calls) ride a data channel alongside the audio.
      const dc = pc.createDataChannel("oai-events");
      dcRef.current = dc;
      dc.onmessage = async (e) => {
        const ev = JSON.parse(e.data);
        switch (ev.type) {
          // The dealer started/stopped speaking — server-side VAD decides, not a push-to-talk button.
          case "input_audio_buffer.speech_started":
            setListening(true);
            break;
          case "input_audio_buffer.speech_stopped":
            setListening(false);
            break;

          // What the dealer said, once the model has transcribed it.
          case "conversation.item.input_audio_transcription.completed":
            if (ev.transcript?.trim()) {
              setTurns((t) => [...t, { role: "user", text: ev.transcript.trim() }]);
            }
            break;

          // What the model is saying, as it says it.
          case "response.output_audio_transcript.delta":
            setSpeaking(true);
            assistantRef.current += ev.delta || "";
            setTurns((t) => {
              const last = t[t.length - 1];
              if (last?.role === "assistant" && last.partial) {
                return [...t.slice(0, -1), { role: "assistant", text: assistantRef.current, partial: true }];
              }
              return [...t, { role: "assistant", text: assistantRef.current, partial: true }];
            });
            break;
          case "response.output_audio_transcript.done":
            setTurns((t) => {
              const last = t[t.length - 1];
              if (last?.role === "assistant" && last.partial) {
                return [...t.slice(0, -1), { role: "assistant", text: ev.transcript || assistantRef.current }];
              }
              return t;
            });
            assistantRef.current = "";
            break;

          case "response.done":
            setSpeaking(false);
            break;

          // The model wants a live price. Fetch it and hand it back, then let it carry on talking.
          case "response.function_call_arguments.done": {
            const result = await runTool(ev.name, ev.arguments);
            send({
              type: "conversation.item.create",
              item: { type: "function_call_output", call_id: ev.call_id, output: result },
            });
            send({ type: "response.create" });
            break;
          }

          case "error":
            setError(ev.error?.message || "The voice session reported an error");
            break;
        }
      };

      // 4. SDP handshake. The offer goes to OpenAI with the ephemeral token; the answer comes back
      //    as plain SDP text. The model is fixed by the token's session, not by a query parameter.
      const offer = await pc.createOffer();
      await pc.setLocalDescription(offer);

      const res = await fetch("https://api.openai.com/v1/realtime/calls", {
        method: "POST",
        body: offer.sdp,
        headers: { Authorization: `Bearer ${s.token}`, "Content-Type": "application/sdp" },
      });
      if (!res.ok) throw new Error(`OpenAI refused the call (${res.status}): ${await res.text()}`);

      await pc.setRemoteDescription({ type: "answer", sdp: await res.text() });

      pc.onconnectionstatechange = () => {
        if (pc.connectionState === "failed" || pc.connectionState === "closed") stop();
      };

      dc.onopen = () => {
        setState("live");
        // Ask for a transcript of the dealer's own speech — without this we hear the answers but
        // never see the questions, and the panel looks like it is talking to itself.
        send({
          type: "session.update",
          session: {
            type: "realtime",
            audio: { input: { transcription: { model: "gpt-realtime-whisper" } } },
          },
        });
        // Greet, so the dealer knows it is listening rather than wondering whether it connected.
        send({ type: "response.create" });
      };
    } catch (e: any) {
      setError(e?.message || "Could not start the voice session");
      setState("error");
      stop();
    }
  }, [runTool, stop]);

  /** Type instead of talk — the same session, so the model keeps the conversation's memory. */
  const sendText = useCallback((text: string) => {
    if (!text.trim()) return;
    send({
      type: "conversation.item.create",
      item: { type: "message", role: "user", content: [{ type: "input_text", text }] },
    });
    send({ type: "response.create" });
    setTurns((t) => [...t, { role: "user", text }]);
  }, []);

  return { state, error, turns, speaking, listening, start, stop, sendText, micLevel, micSilent, micLabel };
}
