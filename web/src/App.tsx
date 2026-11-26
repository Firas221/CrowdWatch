import { useCallback, useEffect, useMemo, useState } from 'react';
import { CssBaseline, Drawer, GlobalStyles, ThemeProvider } from '@mui/material';
import { CacheProvider } from '@emotion/react';
import createCache from '@emotion/cache';
import { prefixer } from 'stylis';
import rtlPlugin from 'stylis-plugin-rtl';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { authHeaders, can, currentSession, pref, setPref, useSession, whenSignedOut, type Session } from './api/client';
import { LiveContext, useLive } from './api/live';
import type { Me } from './api/types';
import { Login } from './components/Login';
import { IncidentPanel } from './components/IncidentPanel';
import { ModeContext, Shell, VIEWS, type View } from './components/Shell';
import { ToastProvider, useToast } from './components/ui';
import { makeTheme } from './theme';
import { Alerts } from './views/Alerts';
import { Areas } from './views/Areas';
import { Incidents } from './views/Incidents';
import { Overview } from './views/Overview';
import { Units } from './views/Units';
import { Lost } from './views/Lost';
import { Emergency } from './views/Emergency';
import { Reports } from './views/Reports';
import { Replay } from './views/Replay';
import { Sites } from './views/Sites';
import { Editor } from './views/Editor';
import { Settings } from './views/Settings';

const ltrCache = createCache({ key: 'cw' });
const rtlCache = createCache({ key: 'cwrtl', stylisPlugins: [prefixer, rtlPlugin] });

export function App() {
  const { i18n } = useTranslation();
  const [mode, setMode] = useState<'light' | 'dark'>(pref('theme') === 'light' ? 'light' : 'dark');
  const rtl = i18n.language === 'ar';
  const theme = useMemo(() => makeTheme(mode, rtl ? 'rtl' : 'ltr'), [mode, rtl]);
  const toggle = useCallback(() => setMode((m) => { const n = m === 'dark' ? 'light' : 'dark'; setPref('theme', n); return n; }), []);

  useEffect(() => {
    document.documentElement.dir = rtl ? 'rtl' : 'ltr';
    document.documentElement.lang = rtl ? 'ar' : 'en';
  }, [rtl]);

  return (
    <CacheProvider value={rtl ? rtlCache : ltrCache}>
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <GlobalStyles styles={{ '@keyframes cwpulse': { '0%,100%': { opacity: 1 }, '50%': { opacity: 0.35 } } }} />
        <ModeContext.Provider value={{ mode, toggle }}>
          <ToastProvider><Console /></ToastProvider>
        </ModeContext.Provider>
      </ThemeProvider>
    </CacheProvider>
  );
}

type SignIn = { state: 'checking' } | { state: 'out'; why: string } | { state: 'in'; session: Session };

function Console() {
  const qc = useQueryClient();
  const toast = useToast();
  const [signIn, setSignIn] = useState<SignIn>({ state: 'checking' });

  const signOut = useCallback((why: string) => {
    const s = currentSession();
    if (s.token) fetch('/api/logout', { method: 'POST', headers: authHeaders(s) }).catch(() => {});
    useSession({ token: '', key: '', officer: s.officer, role: '' });
    qc.clear();
    setSignIn({ state: 'out', why });
  }, [qc]);

  // a stored sign-in is checked once on opening; a 401 from any request signs out
  useEffect(() => {
    whenSignedOut((why) => { useSession({ ...currentSession(), token: '', key: '', role: '' }); qc.clear(); setSignIn({ state: 'out', why }); });
    const s = currentSession();
    fetch('/api/me', { headers: authHeaders(s) }).then((r) => r.json() as Promise<Me>).then((me) => {
      if (me.signedIn && (s.token || s.officer)) {
        const ok: Session = s.token ? { ...s, officer: me.name ?? s.officer, role: (me.role as Session['role']) ?? s.role } : s;
        useSession(ok);
        setSignIn({ state: 'in', session: ok });
      } else setSignIn({ state: 'out', why: '' });
    }).catch(() => setSignIn({ state: 'out', why: 'The server cannot be reached.' }));
  }, [qc]);

  if (signIn.state === 'checking') return null;
  if (signIn.state === 'out') return <Login message={signIn.why} onSignedIn={(s, note) => { setSignIn({ state: 'in', session: s }); toast(note, true); }} />;
  return <SignedIn session={signIn.session} onSignOut={signOut} />;
}

function SignedIn({ session, onSignOut }: { session: Session; onSignOut: (why: string) => void }) {
  const live = useLive(session, onSignOut);
  // the view shown last in this browser, unless this user may not see it
  const allowed = (v: View) => VIEWS.includes(v) && (v !== 'reports' || can('OFFICER')) && (v !== 'editor' || can('ADMIN'));
  const [view, setViewState] = useState<View>(() => (allowed(pref('view2') as View) ? (pref('view2') as View) : 'overview'));
  const [incident, setIncident] = useState<number | null>(null);
  const setView = (v: View) => { setPref('view2', v); setViewState(v); };
  return (
    <LiveContext.Provider value={live}>
      <Shell view={view} onView={setView} onSignOut={() => onSignOut('')}>
        {view === 'overview' && <Overview onView={setView} onIncident={setIncident} />}
        {view === 'areas' && <Areas />}
        {view === 'incidents' && <Incidents onIncident={setIncident} />}
        {view === 'units' && <Units onIncident={setIncident} />}
        {view === 'lost' && <Lost onIncident={setIncident} />}
        {view === 'emergency' && <Emergency />}
        {view === 'alerts' && <Alerts />}
        {view === 'reports' && <Reports />}
        {view === 'replay' && <Replay />}
        {view === 'sites' && <Sites />}
        {view === 'editor' && <Editor />}
        {view === 'settings' && <Settings onSignOut={() => onSignOut('')} />}
      </Shell>
      <Drawer anchor="right" open={incident != null} onClose={() => setIncident(null)} sx={{ zIndex: (th) => th.zIndex.modal }}>
        {incident != null && <IncidentPanel id={incident} onClose={() => setIncident(null)} />}
      </Drawer>
    </LiveContext.Provider>
  );
}
