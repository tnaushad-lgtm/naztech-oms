"use client";

import { useEffect, useRef, useState } from "react";
import { STREAM } from "./api";

type Handler = (type: string, data: any) => void;
// The browser only listens for names in this list — an event the backend publishes but that is
// missing here is silently dropped.
const EVENTS = ["hello", "trade", "order", "market", "indices", "alert", "session"];

/** Subscribes to the OMS SSE stream; calls `onEvent(type, data)` and tracks connectivity. */
export function useLive(onEvent: Handler) {
  const [connected, setConnected] = useState(false);
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    let es: EventSource | null = null;
    let stopped = false;

    const connect = () => {
      if (stopped) return;
      es = new EventSource(`${STREAM}/api/stream`);
      es.onopen = () => setConnected(true);
      es.onerror = () => {
        setConnected(false);
        es?.close();
        if (!stopped) setTimeout(connect, 2500); // auto-reconnect
      };
      EVENTS.forEach((ev) =>
        es!.addEventListener(ev, (e: MessageEvent) => {
          let parsed: any = e.data;
          try { parsed = JSON.parse(e.data); } catch {}
          handlerRef.current(ev, parsed);
        })
      );
    };
    connect();
    return () => { stopped = true; es?.close(); };
  }, []);

  return { connected };
}
