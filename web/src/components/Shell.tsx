import { createContext, useContext, useState, type ReactNode } from 'react';
import {
  Alert, AppBar, Badge, Box, Button, Chip, Divider, Drawer, IconButton, List, ListItemButton, ListItemIcon, ListItemText,
  Stack, Toolbar, Tooltip, Typography, useMediaQuery, useTheme,
} from '@mui/material';
import DashboardIcon from '@mui/icons-material/SpaceDashboardOutlined';
import AreasIcon from '@mui/icons-material/HexagonOutlined';
import IncidentsIcon from '@mui/icons-material/ReportProblemOutlined';
import UnitsIcon from '@mui/icons-material/GroupsOutlined';
import AlertsIcon from '@mui/icons-material/NotificationsNoneOutlined';
import MoreIcon from '@mui/icons-material/OpenInNew';
import MenuIcon from '@mui/icons-material/Menu';
import DarkIcon from '@mui/icons-material/DarkModeOutlined';
import LightIcon from '@mui/icons-material/LightModeOutlined';
import LogoutIcon from '@mui/icons-material/Logout';
import { useTranslation } from 'react-i18next';
import { setPref } from '../api/client';
import { LiveContext } from '../api/live';
import { useOverview } from '../api/queries';
import { useAction } from './ui';

export type View = 'overview' | 'areas' | 'incidents' | 'units' | 'alerts';

/** Theme mode, shared by the header button and the app. */
export const ModeContext = createContext<{ mode: 'light' | 'dark'; toggle: () => void }>({ mode: 'dark', toggle: () => {} });

export function Logo({ size = 32 }: { size?: number }) {
  return (
    <Box component="span" sx={{ width: size, height: size, borderRadius: 2, bgcolor: 'primary.main', display: 'inline-grid', placeItems: 'center', flex: 'none' }}>
      <svg width={size * 0.6} height={size * 0.6} viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" aria-hidden="true">
        <circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="3" /><path d="M12 2v3M12 19v3M2 12h3M19 12h3" />
      </svg>
    </Box>
  );
}

export function LanguageButton() {
  const { i18n } = useTranslation();
  const ar = i18n.language === 'ar';
  return (
    <Button size="small" color="inherit" onClick={() => { const l = ar ? 'en' : 'ar'; setPref('lang', l); i18n.changeLanguage(l); }} lang={ar ? 'en' : 'ar'}>
      {ar ? 'English' : 'العربية'}
    </Button>
  );
}

const NAV: { view: View; label: string; icon: ReactNode }[] = [
  { view: 'overview', label: 'Overview', icon: <DashboardIcon /> },
  { view: 'areas', label: 'Areas and crowd', icon: <AreasIcon /> },
  { view: 'incidents', label: 'Incidents', icon: <IncidentsIcon /> },
  { view: 'units', label: 'Units and scouts', icon: <UnitsIcon /> },
  { view: 'alerts', label: 'Alerts', icon: <AlertsIcon /> },
];
const NAV_WIDTH = 240;

function LiveBadge() {
  const { t } = useTranslation();
  const live = useContext(LiveContext);
  const color = live === 'live' ? 'success' : live === 'connecting' ? 'warning' : 'error';
  return (
    <Tooltip title={t(live === 'live' ? 'Updates are pushed by the server' : 'Live updates are down - asking every 3 seconds')}>
      <Chip size="small" color={color} variant="outlined" label={t(live === 'live' ? 'LIVE' : live === 'connecting' ? 'CONNECTING' : 'OFFLINE')}
        icon={<Box component="span" sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: color + '.main', ml: 1, animation: live === 'live' ? 'cwpulse 2s infinite' : undefined }} />} />
    </Tooltip>
  );
}

function Banners({ onView }: { onView: (v: View) => void }) {
  const { t } = useTranslation();
  const { data: o, isError } = useOverview();
  const checkin = useAction('/api/checkin');
  return (
    <Stack spacing={1} sx={{ px: { xs: 2, md: 3 }, pt: 2 }}>
      {isError && <Alert severity="error">{t('The server is not answering. The figures below are from the last good update.')}</Alert>}
      {o?.drill && <Alert severity="info" variant="filled">{t('DRILL - training server: nothing here is real, nothing is saved, the real venue is not affected.')}</Alert>}
      {o?.evacuation && (
        <Alert severity="error" variant="filled" action={<Button color="inherit" size="small" href="/legacy">{t('Open in the classic console')}</Button>}>
          {t('EVACUATION of {{zone}} in progress. Public routes avoid the zone.', { zone: o.evacuation })}
        </Alert>
      )}
      {o?.proposal && (
        <Alert severity="warning" action={<Button color="inherit" size="small" href="/legacy">{t('Open in the classic console')}</Button>}>
          {t('EVACUATION PROPOSED for {{zone}}: {{reason}}. You decide.', { zone: o.proposal.zone, reason: o.proposal.reason })}
        </Alert>
      )}
      {o?.overdue && (
        <Alert severity="warning" action={<Button color="inherit" size="small" onClick={() => checkin.mutate({})} disabled={checkin.isPending}>{t('Check in now')}</Button>}>
          {o.officer ? t('Officer check-in is OVERDUE: {{status}}', { status: o.officerStatus }) : t('No security officer is on watch.')}
        </Alert>
      )}
      {o && o.review > 0 && (
        <Alert severity="info" action={<Button color="inherit" size="small" onClick={() => onView('incidents')}>{t('Incidents')}</Button>}>
          {t('Awaiting review')}: {o.review} {t('automatic dispatches')}
        </Alert>
      )}
    </Stack>
  );
}

export function Shell({ view, onView, onSignOut, children }: { view: View; onView: (v: View) => void; onSignOut: () => void; children: ReactNode }) {
  const { t } = useTranslation();
  const theme = useTheme();
  const wide = useMediaQuery(theme.breakpoints.up('md'));
  const [open, setOpen] = useState(false);
  const { mode, toggle } = useContext(ModeContext);
  const { data: o } = useOverview();
  const checkin = useAction('/api/checkin');
  const openIncidents = o?.incidents.open ?? 0;

  const nav = (
    <Box sx={{ width: NAV_WIDTH, pt: 1 }} role="navigation">
      <List>
        {NAV.map((n) => (
          <ListItemButton key={n.view} selected={view === n.view} onClick={() => { onView(n.view); setOpen(false); }} sx={{ mx: 1, borderRadius: 2 }}>
            <ListItemIcon sx={{ minWidth: 40 }}>
              {n.view === 'incidents' ? <Badge color="error" badgeContent={openIncidents} max={99}>{n.icon}</Badge> : n.icon}
            </ListItemIcon>
            <ListItemText primary={t(n.label)} />
          </ListItemButton>
        ))}
      </List>
      <Divider sx={{ my: 1 }} />
      <List>
        <ListItemButton component="a" href="/legacy" sx={{ mx: 1, borderRadius: 2 }}>
          <ListItemIcon sx={{ minWidth: 40 }}><MoreIcon /></ListItemIcon>
          <ListItemText primary={t('More in the classic console')} secondary={t('Lost persons, evacuation, reports, replay, editor, users and settings')} />
        </ListItemButton>
      </List>
    </Box>
  );

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      <AppBar position="fixed" color="default" elevation={0} sx={{ zIndex: (th) => th.zIndex.drawer + 1, borderBottom: 1, borderColor: 'divider', bgcolor: 'background.paper' }}>
        <Toolbar sx={{ gap: 1.5 }}>
          {!wide && <IconButton edge="start" onClick={() => setOpen(true)} aria-label="menu"><MenuIcon /></IconButton>}
          <Logo />
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontWeight: 700, lineHeight: 1.1 }}>CrowdWatch</Typography>
            <Typography variant="body2" color="text.secondary" noWrap>{o?.venue}</Typography>
          </Box>
          <LiveBadge />
          {o?.drill && <Chip size="small" color="info" label="DRILL" />}
          <Box sx={{ flex: 1 }} />
          {wide && <LanguageButton />}
          <Tooltip title={t(mode === 'dark' ? 'Light' : 'Dark')}>
            <IconButton onClick={toggle} aria-label={t(mode === 'dark' ? 'Light' : 'Dark')}>{mode === 'dark' ? <LightIcon /> : <DarkIcon />}</IconButton>
          </Tooltip>
          <Tooltip title={t('Check in now')}>
            <Button color={o?.overdue ? 'warning' : 'inherit'} variant={o?.overdue ? 'outlined' : 'text'} onClick={() => checkin.mutate({})} disabled={checkin.isPending}
              sx={{ textAlign: 'start', display: 'block', lineHeight: 1.2, maxWidth: 220 }}>
              <Typography variant="body2" noWrap sx={{ fontWeight: 600 }}>{o?.officer ?? t('No officer on watch')}</Typography>
              {wide && <Typography variant="caption" color="text.secondary" noWrap component="div">{o?.officerStatus}</Typography>}
            </Button>
          </Tooltip>
          <Tooltip title={t('Sign out')}><IconButton onClick={onSignOut} aria-label={t('Sign out')}><LogoutIcon /></IconButton></Tooltip>
        </Toolbar>
      </AppBar>
      <Drawer variant={wide ? 'permanent' : 'temporary'} open={wide || open} onClose={() => setOpen(false)}
        sx={{ width: NAV_WIDTH, flexShrink: 0, '& .MuiDrawer-paper': { width: NAV_WIDTH, boxSizing: 'border-box' } }}>
        <Toolbar />
        {nav}
        {!wide && <Box sx={{ p: 2 }}><LanguageButton /></Box>}
      </Drawer>
      <Box component="main" sx={{ flex: 1, minWidth: 0 }}>
        <Toolbar />
        <Banners onView={onView} />
        <Box sx={{ p: { xs: 2, md: 3 } }}>{children}</Box>
      </Box>
    </Box>
  );
}
