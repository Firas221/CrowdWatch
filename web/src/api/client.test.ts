import { beforeEach, describe, expect, it, vi } from 'vitest';

// a tiny localStorage and location for the node test environment
const mem = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => mem.get(k) ?? null,
  setItem: (k: string, v: string) => void mem.set(k, v),
  removeItem: (k: string) => void mem.delete(k),
});
vi.stubGlobal('location', { protocol: 'https:', host: 'venue.example:8443' });

const client = await import('./client');

describe('client', () => {
  beforeEach(() => mem.clear());

  it('keeps the sign-in under the same names as the classic console', () => {
    client.useSession({ token: 't1', key: '', officer: 'Khalid', role: 'OFFICER' });
    expect(mem.get('cw.token')).toBe('t1');
    expect(mem.get('cw.officer')).toBe('Khalid');
    expect(mem.has('cw.key')).toBe(false);
  });

  it('checks roles; the control key counts as ADMIN', () => {
    client.useSession({ token: 't', key: '', officer: 'a', role: 'OPERATOR' });
    expect(client.can('OPERATOR')).toBe(true);
    expect(client.can('OFFICER')).toBe(false);
    client.useSession({ token: '', key: 'k', officer: 'a', role: '' });
    expect(client.can('ADMIN')).toBe(true);
  });

  it('sends the token or the key', () => {
    expect(client.authHeaders({ token: 'abc', key: 'k', officer: '', role: '' })).toEqual({ Authorization: 'Bearer abc' });
    expect(client.authHeaders({ token: '', key: 'k', officer: '', role: '' })).toEqual({ 'X-Api-Key': 'k' });
    expect(client.authHeaders({ token: '', key: '', officer: '', role: '' })).toEqual({});
  });

  it('puts the sign-in in the WebSocket address, over wss for https', () => {
    expect(client.liveUrl({ token: 'a b', key: '', officer: '', role: '' })).toBe('wss://venue.example:8443/ws?token=a%20b');
    expect(client.liveUrl({ token: '', key: 'k&1', officer: '', role: '' })).toBe('wss://venue.example:8443/ws?key=k%261');
  });

  it('posts a form with the officer name and turns 401 into a sign-out', async () => {
    client.useSession({ token: '', key: 'k', officer: 'Sara', role: '' });
    const seen: RequestInit[] = [];
    let out = '';
    client.whenSignedOut((why) => { out = why; });
    vi.stubGlobal('fetch', async (_: string, init: RequestInit) => {
      seen.push(init);
      return new Response(seen.length === 1 ? '{"ok":true,"message":"done"}' : 'no', { status: seen.length === 1 ? 200 : 401 });
    });
    expect(await client.post('/api/resolve', { id: 7, note: 'all fine', skip: null })).toEqual({ ok: true, message: 'done' });
    expect(seen[0].body).toBe('officer=Sara&id=7&note=all+fine');
    expect((seen[0].headers as Record<string, string>)['X-Api-Key']).toBe('k');
    await expect(client.get('/api/areas')).rejects.toThrow('control key');
    expect(out).toContain('control key');
  });
});
