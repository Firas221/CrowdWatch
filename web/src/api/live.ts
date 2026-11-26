// Live updates: one WebSocket to /ws. The server says only *that* something changed; the
// console then reloads what it shows through the normal API (which checks the user's rights).
// While the socket is down, the queries fall back to asking every 3 seconds, as before.

import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { liveUrl, type Session } from './client';

export type LiveState = 'live' | 'connecting' | 'offline';

export const LiveContext = createContext<LiveState>('connecting');

/** How often queries ask the server: rarely while pushed updates arrive, every 3 s without them. */
export function usePollInterval(): number {
  return useContext(LiveContext) === 'live' ? 30_000 : 3_000;
}

/** Reloads at most this often when changes arrive in a burst. */
const RELOAD_MS = 400;

export function useLive(session: Session, onEnded: (why: string) => void): LiveState {
  const qc = useQueryClient();
  const [state, setState] = useState<LiveState>('connecting');
  const ended = useRef(onEnded);
  ended.current = onEnded;

  useEffect(() => {
    let ws: WebSocket | null = null;
    let stopped = false;
    let retry = 1000;
    let retryTimer: number | undefined;
    let ping: number | undefined;
    let reloadTimer: number | undefined;
    let failures = 0;

    const reload = () => {
      if (reloadTimer !== undefined) return;
      reloadTimer = window.setTimeout(() => {
        reloadTimer = undefined;
        qc.invalidateQueries({ queryKey: ['live'] });
      }, RELOAD_MS);
    };

    const connect = () => {
      if (stopped) return;
      ws = new WebSocket(liveUrl(session));
      ws.onopen = () => {
        retry = 1000;
        failures = 0;
        setState('live');
        // catch up on anything that changed while the socket was down
        qc.invalidateQueries({ queryKey: ['live'] });
        ping = window.setInterval(() => ws?.readyState === WebSocket.OPEN && ws.send('ping'), 25_000);
      };
      ws.onmessage = (e) => {
        try {
          const m = JSON.parse(String(e.data)) as { type: string };
          if (m.type === 'change' || m.type === 'alert') reload();
        } catch { /* not for us */ }
      };
      ws.onclose = (e) => {
        window.clearInterval(ping);
        if (stopped) return;
        if (e.code === 4401) { ended.current('Your session has ended - please sign in again.'); return; }
        failures++;
        setState(failures > 2 ? 'offline' : 'connecting');
        retryTimer = window.setTimeout(connect, retry);
        retry = Math.min(retry * 2, 15_000);
      };
    };

    connect();
    return () => {
      stopped = true;
      window.clearTimeout(retryTimer);
      window.clearTimeout(reloadTimer);
      window.clearInterval(ping);
      ws?.close();
    };
  }, [session, qc]);

  return state;
}
