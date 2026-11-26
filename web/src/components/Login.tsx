import { useEffect, useState, type FormEvent } from 'react';
import { Alert, Box, Button, Card, CardContent, Dialog, DialogActions, DialogContent, DialogTitle, Link, Stack, TextField, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { currentSession, post, postOpen, useSession, type Session } from '../api/client';
import type { ActionResult, LoginReply, Me } from '../api/types';
import { Logo, LanguageButton } from './Shell';

/** Sign-in: user name and password (with the authenticator code when asked) or, without personal logins, the officer's name and the control key. */
export function Login({ message, onSignedIn }: { message: string; onSignedIn: (s: Session, note: string) => void }) {
  const { t } = useTranslation();
  const [logins, setLogins] = useState(false);
  const [userMode, setUserMode] = useState(true);
  const [needCode, setNeedCode] = useState(false);
  const [error, setError] = useState(message);
  const [busy, setBusy] = useState(false);
  const [f, setF] = useState({ user: '', password: '', code: '', officer: currentSession().officer, key: '' });
  const [reset, setReset] = useState(false);
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });

  useEffect(() => {
    fetch('/api/me').then((r) => r.json() as Promise<Me>).then((me) => { setLogins(me.logins); setUserMode(me.logins); })
      .catch(() => { setUserMode(false); setError(t('The server is not answering. The figures below are from the last good update.')); });
  }, [t]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      if (userMode) {
        const r = await postOpen<LoginReply>('/api/login', { user: f.user.trim(), password: f.password, code: needCode ? f.code.trim() : undefined });
        if (r.needCode) { setNeedCode(true); setF({ ...f, code: '' }); }
        if (!r.ok || !r.token) { setError(r.message ?? 'Refused.'); return; }
        const s: Session = { token: r.token, key: '', officer: r.name ?? f.user, role: r.role ?? 'OPERATOR' };
        useSession(s);
        // officers check in on signing in; operators do not supervise
        const note = s.role === 'OPERATOR' ? { ok: true, message: `Signed in as ${s.officer} (${s.role})` } : await post<ActionResult>('/api/checkin', {});
        onSignedIn(s, note.message);
      } else {
        if (!f.officer.trim()) { setError(t('Officer name')); return; }
        const s: Session = { token: '', key: f.key.trim(), officer: f.officer.trim(), role: '' };
        const before = currentSession();
        useSession(s);
        const r = await post<ActionResult>('/api/checkin', { officer: s.officer });
        if (!r.ok) { useSession(before); setError(r.message); return; }
        onSignedIn(s, r.message);
      }
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', p: 2, bgcolor: 'background.default' }}>
      <Card sx={{ width: '100%', maxWidth: 400 }}>
        <CardContent component="form" onSubmit={submit}>
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 3 }}>
            <Logo size={40} />
            <Box>
              <Typography variant="h6" sx={{ fontWeight: 700, lineHeight: 1.2 }}>CrowdWatch</Typography>
              <Typography variant="body2" color="text.secondary">{t('Control room console')}</Typography>
            </Box>
          </Stack>
          <Stack spacing={2}>
            {userMode ? (
              <>
                <TextField label={t('User name')} value={f.user} onChange={set('user')} autoComplete="username" autoFocus required slotProps={{ htmlInput: { autoCapitalize: 'none' } }} />
                <TextField label={t('Password')} type="password" value={f.password} onChange={set('password')} autoComplete="current-password" required />
                {needCode && (
                  <TextField label={t('Code from your authenticator app')} value={f.code} onChange={set('code')} autoFocus required
                    slotProps={{ htmlInput: { inputMode: 'numeric', autoComplete: 'one-time-code', maxLength: 6 } }} />
                )}
              </>
            ) : (
              <>
                <TextField label={t('Officer name')} value={f.officer} onChange={set('officer')} autoComplete="name" autoFocus required />
                <TextField label={t('Control key')} type="password" value={f.key} onChange={set('key')} placeholder={t('leave empty if the server has no key')} autoComplete="current-password"
                  helperText={t('The key is kept in this browser only.')} />
              </>
            )}
            {error && <Alert severity="error">{error}</Alert>}
            <Button type="submit" variant="contained" size="large" disabled={busy}>{t(userMode ? 'Sign in' : 'Sign in and check in')}</Button>
            <Stack direction="row" sx={{ justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
              {logins && <Button size="small" onClick={() => { setUserMode(!userMode); setError(''); }}>{t(userMode ? 'Use the control key instead' : 'Use my user name and password')}</Button>}
              {userMode && <Button size="small" onClick={() => setReset(true)}>{t('Forgot password?')}</Button>}
            </Stack>
            <Stack direction="row" sx={{ justifyContent: 'space-between', alignItems: 'center' }}>
              <Typography variant="body2" color="text.secondary">
                <Link href="/scout">{t('Scout phones')}</Link> · <Link href="/legacy">{t('Classic console')}</Link>
              </Typography>
              <LanguageButton />
            </Stack>
          </Stack>
        </CardContent>
      </Card>
      <ResetDialog open={reset} user={f.user} onClose={() => setReset(false)} />
    </Box>
  );
}

function ResetDialog({ open, user, onClose }: { open: boolean; user: string; onClose: () => void }) {
  const { t } = useTranslation();
  const [f, setF] = useState({ user, code: '', password: '' });
  const [result, setResult] = useState<ActionResult | null>(null);
  useEffect(() => { if (open) { setF({ user, code: '', password: '' }); setResult(null); } }, [open, user]);
  async function submit(e: FormEvent) {
    e.preventDefault();
    try { setResult(await postOpen<ActionResult>('/api/password/reset', f)); } catch (err) { setResult({ ok: false, message: (err as Error).message }); }
  }
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={submit}>
        <DialogTitle>{t('Forgot password?')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">{t('Ask an administrator for a reset code first.')}</Typography>
            <TextField label={t('User name')} value={f.user} onChange={(e) => setF({ ...f, user: e.target.value })} required />
            <TextField label={t('Reset code')} value={f.code} onChange={(e) => setF({ ...f, code: e.target.value })} required slotProps={{ htmlInput: { autoCapitalize: 'characters' } }} />
            <TextField label={t('New password (at least 8 characters)')} type="password" value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required
              slotProps={{ htmlInput: { minLength: 8, autoComplete: 'new-password' } }} />
            {result && <Alert severity={result.ok ? 'success' : 'error'}>{result.message}</Alert>}
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{t('Cancel')}</Button>
          <Button type="submit" variant="contained">{t('Set the new password')}</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
