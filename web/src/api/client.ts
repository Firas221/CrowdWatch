// Talks to the CrowdWatch API. The sign-in is kept in localStorage under the same names as the
// classic console (cw.token, cw.key, cw.officer, cw.role), so moving between the two keeps it.

import type { ActionResult, Role } from './types';

const store = {
  get(k: string): string {
    try { return localStorage.getItem('cw.' + k) ?? ''; } catch { return ''; }
  },
  set(k: string, v: string | null) {
    try { if (v == null || v === '') localStorage.removeItem('cw.' + k); else localStorage.setItem('cw.' + k, v); } catch { /* private window */ }
  },
};

/** Who this browser is signed in as: a personal session token, or the control key with an officer name. */
export interface Session {
  token: string;
  key: string;
  officer: string;
  role: Role | '';
}

export function loadSession(): Session {
  return { token: store.get('token'), key: store.get('key'), officer: store.get('officer'), role: (store.get('role') as Role) || '' };
}

export function saveSession(s: Session) {
  store.set('token', s.token);
  store.set('key', s.key);
  store.set('officer', s.officer);
  store.set('role', s.role);
}

export function pref(k: string): string { return store.get(k); }
export function setPref(k: string, v: string) { store.set(k, v); }

let session: Session = loadSession();
let onSignedOut: (why: string) => void = () => {};

export function currentSession(): Session { return session; }
export function useSession(s: Session) { session = s; saveSession(s); }
export function whenSignedOut(f: (why: string) => void) { onSignedOut = f; }

const RANK: Record<Role, number> = { OPERATOR: 1, OFFICER: 2, ADMIN: 3 };

/** True if the user has at least this role; the control key counts as ADMIN (the server checks again). */
export function can(role: Role): boolean {
  return !session.token || (session.role !== '' && RANK[session.role] >= RANK[role]);
}

export function authHeaders(s: Session = session): Record<string, string> {
  if (s.token) return { Authorization: 'Bearer ' + s.token };
  if (s.key) return { 'X-Api-Key': s.key };
  return {};
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

function form(fields: Record<string, string | number | boolean | null | undefined>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(fields)) if (v != null) p.append(k, String(v));
  return p.toString();
}

async function call<T>(path: string, init: RequestInit): Promise<T> {
  let r: Response;
  try {
    r = await fetch(path, init);
  } catch {
    throw new ApiError('The server cannot be reached.', 0);
  }
  if (r.status === 401) {
    const why = session.token ? 'Your session has ended - please sign in again.' : 'The control key is missing or wrong.';
    onSignedOut(why);
    throw new ApiError(why, 401);
  }
  if (r.status === 429) throw new ApiError('The server says: too many requests.', 429);
  const text = await r.text();
  try {
    return JSON.parse(text) as T;
  } catch {
    if (!r.ok) throw new ApiError(text || r.statusText, r.status);
    return text as unknown as T;
  }
}

/** GET an API endpoint as JSON. */
export function get<T>(path: string): Promise<T> {
  return call<T>(path, { headers: authHeaders() });
}

/**
 * POST a change. Like the classic console, the officer's name is sent unless given; for a
 * personal login the server uses the user's own name anyway.
 */
export function post<T = ActionResult>(path: string, fields: Record<string, string | number | boolean | null | undefined> = {}): Promise<T> {
  const body = { officer: session.officer, ...fields };
  return call<T>(path, {
    method: 'POST',
    headers: { ...authHeaders(), 'Content-Type': 'application/x-www-form-urlencoded' },
    body: form(body),
  });
}

/** POST without the stored sign-in (sign-in and password reset). */
export function postOpen<T>(path: string, fields: Record<string, string | undefined>): Promise<T> {
  return call<T>(path, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: form(fields) });
}

/** The WebSocket address with this browser's sign-in (browsers cannot add headers to a WebSocket). */
export function liveUrl(s: Session = session): string {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const q = s.token ? 'token=' + encodeURIComponent(s.token) : s.key ? 'key=' + encodeURIComponent(s.key) : '';
  return proto + '//' + location.host + '/ws' + (q ? '?' + q : '');
}
