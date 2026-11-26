import { useContext, useState, type FormEvent, type ReactNode } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Checkbox, Chip, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Link, MenuItem, Stack,
  Switch, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { can, currentSession, download, get, getText, openPage, post, saveBlob } from '../api/client';
import { useOverview, useUnits, useVenue } from '../api/queries';
import type { AuditEntry, Me, PrivacyResult, User } from '../api/types';
import { ModeContext } from '../components/Shell';
import { StateChip, fmt, useAction, useToast } from '../components/ui';
import { PRIORITY_COLOR } from '../theme';

function Section({ title, sub, action, children }: { title: string; sub?: ReactNode; action?: ReactNode; children?: ReactNode }) {
  return (
    <Card><CardContent>
      <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', mb: sub || children ? 1.5 : 0 }}>
        <Box sx={{ flex: 1 }}>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{title}</Typography>
          {sub && <Typography variant="body2" color="text.secondary">{sub}</Typography>}
        </Box>
        {action}
      </Stack>
      {children}
    </CardContent></Card>
  );
}

/** Label and value pairs. */
function Facts({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'minmax(140px, auto) 1fr' }, columnGap: 2, rowGap: 0.75 }}>
      {rows.map(([k, v]) => [
        <Typography key={k + 'k'} variant="body2" color="text.secondary">{k}</Typography>,
        <Typography key={k + 'v'} variant="body2" component="div">{v}</Typography>,
      ])}
    </Box>
  );
}

/** A code to read out or type on a phone, in big letters, with a dialog around it. */
function CodeDialog({ code, title, text, onClose }: { code: string | null; title: string; text: string; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Dialog open={code != null} onClose={onClose} fullWidth maxWidth="xs">
      <DialogTitle>{title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>{text}</Typography>
        <Typography sx={{ fontFamily: 'monospace', fontSize: 32, fontWeight: 800, letterSpacing: 4, textAlign: 'center' }}>{code}</Typography>
      </DialogContent>
      <DialogActions><Button variant="contained" onClick={onClose}>{t('Close')}</Button></DialogActions>
    </Dialog>
  );
}

export function Settings({ onSignOut }: { onSignOut: () => void }) {
  const { t } = useTranslation();
  const { data: v } = useVenue();
  const { data: o } = useOverview();
  const { mode, toggle } = useContext(ModeContext);
  const checkin = useAction('/api/checkin');
  const automation = useAction('/api/automation');
  if (!v || !o) return null;
  const s = currentSession();

  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Venue settings')}</Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 2fr' } }}>
        <Section title={t('Supervision')}>
          <Facts rows={[[t('On watch'), o.officer ?? t('nobody')], [t('Status'), o.officerStatus], [t('Check-in every'), `${v.supervisionMinutes} ${t('min')}`]]} />
          {can('OFFICER') && (
            <Stack spacing={1} sx={{ mt: 2 }}>
              <Box><Button variant="contained" disabled={checkin.isPending} onClick={() => checkin.mutate({})}>{t('Check in as {{name}}', { name: s.officer })}</Button></Box>
              <FormControlLabel control={<Switch checked={o.automation} disabled={automation.isPending} onChange={(e) => automation.mutate({ on: e.target.checked ? 1 : 0 })} />}
                label={t(o.automation ? 'Automatic dispatch ON' : 'Automatic dispatch OFF')} />
            </Stack>
          )}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{o.autoRule ? t('Rule: send {{rule}}', { rule: o.autoRule }) : t('This venue has no automatic dispatch rule.')} {t('Switching it is logged with your name.')}</Typography>
        </Section>
        <Section title={`${v.name} (${v.code})`} sub={t('These settings come from the venue file. Edit it and restart to change them.')}>
          <Facts rows={[
            [t('Crowd levels'), t('MODERATE from {{a}}%, HIGH from {{b}}%, CRITICAL from {{c}}%', { a: v.thresholds[0], b: v.thresholds[1], c: v.thresholds[2] })],
            [t('Acknowledge within'), t('{{n}} min, then the task is sent again', { n: v.ackMinutes })],
            [t('Archive after'), t('{{n}} min resolved', { n: v.archiveMinutes })],
            [t('Radio gateway'), t(v.gateway ? 'configured' : 'not configured')],
            [t('Exits'), v.exits.join(', ') || '-'],
            [t('Sectors'), v.sectors.join(', ')],
          ]} />
        </Section>
      </Box>
      <Rules />
      <Section title={t('Incident categories')}>
        <TableContainer>
          <Table size="small">
            <TableHead><TableRow><TableCell>{t('Code')}</TableCell><TableCell>{t('Label')}</TableCell><TableCell>{t('Priority')}</TableCell><TableCell>{t('Skill')}</TableCell></TableRow></TableHead>
            <TableBody>{v.categories.map((c) => (
              <TableRow key={c.code}><TableCell sx={{ fontFamily: 'monospace' }}>{c.code}</TableCell><TableCell>{c.label}</TableCell><TableCell><StateChip label={c.priority} color={PRIORITY_COLOR[c.priority]} /></TableCell><TableCell>{c.skill ?? '-'}</TableCell></TableRow>
            ))}</TableBody>
          </Table>
        </TableContainer>
      </Section>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: 'repeat(auto-fit, minmax(300px, 1fr))' } }}>
        <Weather />
        <HelpPage />
        {s.token && <Password />}
      </Box>
      <Access />
      {can('ADMIN') && <Privacy />}
      <Section title={t('This browser')}>
        <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
          <Button variant="outlined" onClick={toggle}>{t(mode === 'dark' ? 'Light' : 'Dark')}</Button>
          <Button variant="outlined" onClick={onSignOut}>{t('Sign out and forget the key')}</Button>
          <Button variant="outlined" href="/classic">{t('Plain dashboard')}</Button>
          <Button variant="outlined" href="/legacy">{t('Classic console')}</Button>
        </Stack>
      </Section>
    </Stack>
  );
}

function Rules() {
  const { t } = useTranslation();
  const { data: v } = useVenue();
  const { data: o } = useOverview();
  const toast = useToast();
  const [handover, setHandover] = useState<string | null>(null);
  const reset = useAction('/api/countcheck/reset');
  if (!v || !o) return null;
  const r = v.rules;
  const on = (b: boolean) => t(b ? 'on' : 'off');
  const cc = o.countCheck;
  return (
    <Section title={t('Automatic rules')} sub={t('They act while automatic dispatch is ON (sensor checks, shifts and handover reports always run).')}
      action={<Button variant="outlined" onClick={() => getText('/api/handover').then(setHandover).catch((e: Error) => toast(e.message, false))}>{t('Handover report now')}</Button>}>
      <Facts rows={[
        [t('Sensor checks'), t('stale after {{a}} min without data; suspect if a count jumps more than {{b}}% of capacity within a minute', { a: r.stale, b: r.jump })],
        [t('Follow-through'), t('reminder {{a}} min after acknowledging without arriving, backup at {{b}} min; stand-in after {{c}} min waiting; automatic crowd incidents close after {{d}} min below HIGH', { a: r.onscene, b: 2 * r.onscene, c: r.maxwait, d: r.autoclose })],
        [t('Count check'), cc ? (
          <Stack direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap' }}>
            <span>{t('areas {{a}}, gates {{b}} (difference {{c}})', { a: fmt(cc.counted), b: fmt(cc.expected), c: fmt(cc.difference) })}{cc.ok ? '' : ' - '}{!cc.ok && <b>{t('do not add up')}</b>}</span>
            {can('OFFICER') && <Button size="small" onClick={() => { if (confirm(t('Reset the count check? Only do this after a full recount: the total now becomes the new starting point.'))) reset.mutate({}); }}>{t('Reset after a recount')}</Button>}
          </Stack>) : t('not in use (no gate counter sends people in or out of the venue)')],
        [t('Reinforcement'), r.reinforceCritical ? t('a crowded area gets {{h}} units at HIGH and {{c}} at CRITICAL ({{skills}} first; at HIGH only units within {{max}}, at CRITICAL any distance); they return to their posts {{hold}} min after it calms', {
          h: r.reinforceHigh, c: r.reinforceCritical, skills: (r.reinforceSkills.length ? r.reinforceSkills : ['crowd-control', 'security']).join(', '),
          max: r.reinforceMax ? `${r.reinforceMax} ${t('min walk')}` : t('any distance'), hold: r.reinforceHold }) : t('off')],
        [t('Crowd flow'), t('hold flow {{a}}; units sent ahead {{b}}; sector cover {{c}}', { a: on(r.holdflow), b: r.preposition ? t('for areas CRITICAL within {{n}} min', { n: r.preposition }) : t('off'), c: on(r.cover) })],
        [t('Evacuation'), t('proposed after {{n}} min CRITICAL and rising, or on an alarm; only an officer starts it', { n: r.propose })],
        [t('Lost persons'), t('search {{a}} min walk, widening every {{b}} min; meeting point {{c}}', { a: r.lostRadius, b: r.lostWiden, c: r.meeting ?? t('where reported') })],
        [t('Public messages'), t('drafts {{a}} (approval needed)', { a: on(r.signs) })],
        [t('Scheduled peaks'), r.events.length ? r.events.map((e) => `${e.time} ${e.name} (${e.zone}, ${e.minutes} min)`).join('; ') : t('none')],
        [t('Shift changes'), `${r.shifts.join(', ') || t('none')} - ${t('a handover report is written at each')}`],
      ]} />
      <Dialog open={handover != null} onClose={() => setHandover(null)} fullWidth maxWidth="md">
        <DialogTitle>{t('Handover report')}</DialogTitle>
        <DialogContent><Box component="pre" sx={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 13, m: 0 }}>{handover}</Box></DialogContent>
        <DialogActions>
          <Button onClick={() => setHandover(null)}>{t('Close')}</Button>
          <Button variant="contained" onClick={() => saveBlob(new Blob([handover ?? ''], { type: 'text/plain' }), 'handover.txt')}>{t('Download')}</Button>
        </DialogActions>
      </Dialog>
    </Section>
  );
}

function Weather() {
  const { t } = useTranslation();
  const { data: o } = useOverview();
  const [f, setF] = useState({ temp: '', humidity: '' });
  const record = useAction('/api/weather', () => setF({ temp: '', humidity: '' }));
  const h = o?.heat;
  return (
    <Section title={t('Weather and heat stress')}>
      {h ? (
        <Box sx={{ mb: 2 }}>
          <Typography sx={{ fontWeight: 700 }}>{t('Heat index {{n}}°C', { n: h.index })} - {h.band}</Typography>
          <Typography variant="body2" color="text.secondary">{h.temp}°C, {h.humidity}% · {t('Outdoor areas')}: {h.outdoor.join(', ') || '-'}</Typography>
        </Box>
      ) : <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('No reading in the last 3 hours. A weather station can send it as a count line (weather,38,40) or enter it here.')}</Typography>}
      {can('OPERATOR') && (
        <Stack component="form" direction="row" sx={{ flexWrap: 'wrap', gap: 1 }} onSubmit={(e: FormEvent) => { e.preventDefault(); record.mutate(f); }}>
          <TextField size="small" type="number" label={t('Temperature °C')} value={f.temp} onChange={(e) => setF({ ...f, temp: e.target.value })} required slotProps={{ htmlInput: { step: 0.1 } }} sx={{ flex: '1 1 120px' }} />
          <TextField size="small" type="number" label={t('Humidity %')} value={f.humidity} onChange={(e) => setF({ ...f, humidity: e.target.value })} required slotProps={{ htmlInput: { min: 0, max: 100 } }} sx={{ flex: '1 1 120px' }} />
          <Button type="submit" variant="outlined" disabled={record.isPending}>{t('Record')}</Button>
        </Stack>
      )}
    </Section>
  );
}

function HelpPage() {
  const { t } = useTranslation();
  const toast = useToast();
  return (
    <Section title={t('Visitor help page')} sub={t('Visitors scan a QR code in an area and report a lost person, someone unwell or a person they found - in English or Arabic, without an app. Print one code per area.')}>
      <Stack direction="row" spacing={1}>
        <Button variant="contained" onClick={() => openPage('/api/qr').catch((e: Error) => toast(e.message, false))}>{t('Print help QR codes')}</Button>
        <Button variant="outlined" href="/help" target="_blank" rel="noopener">{t('Open the help page')}</Button>
      </Stack>
    </Section>
  );
}

function Password() {
  const { t } = useTranslation();
  const [f, setF] = useState({ current: '', password: '' });
  const change = useAction('/api/password', () => setF({ current: '', password: '' }));
  return (
    <Section title={t('Your password')}>
      <Stack component="form" spacing={1.5} onSubmit={(e: FormEvent) => { e.preventDefault(); change.mutate(f); }}>
        <TextField size="small" type="password" label={t('Current password')} value={f.current} onChange={(e) => setF({ ...f, current: e.target.value })} required autoComplete="current-password" />
        <TextField size="small" type="password" label={t('New password (at least 8 characters)')} value={f.password} onChange={(e) => setF({ ...f, password: e.target.value })} required autoComplete="new-password" slotProps={{ htmlInput: { minLength: 8 } }} />
        <Box><Button type="submit" variant="outlined" disabled={change.isPending}>{t('Change password')}</Button></Box>
      </Stack>
    </Section>
  );
}

/** Two-step sign-in, scout device codes, the audit log, users and password reset codes. */
function Access() {
  const { t } = useTranslation();
  const toast = useToast();
  const qc = useQueryClient();
  const s = currentSession();
  const { data: units } = useUnits();
  const me = useQuery({ queryKey: ['me'], queryFn: () => get<Me>('/api/me'), enabled: !!s.token });
  const users = useQuery({ queryKey: ['users'], queryFn: () => get<User[]>('/api/users'), enabled: can('ADMIN') });
  const [audit, setAudit] = useState<AuditEntry[] | null>(null);
  const [setup, setSetup] = useState<{ secret: string; link: string } | null>(null);
  const [code, setCode] = useState('');
  const [offCode, setOffCode] = useState('');
  const [unit, setUnit] = useState('');
  const [shown, setShown] = useState<{ code: string; title: string; text: string } | null>(null);
  const [u, setU] = useState({ name: '', role: 'OPERATOR', password: '', active: true, twoStepOff: false, directory: false });
  const [resetName, setResetName] = useState('');
  const refreshMe = () => qc.invalidateQueries({ queryKey: ['me'] });
  const confirm2 = useAction('/api/twostep/confirm', () => { setSetup(null); setCode(''); refreshMe(); });
  const off2 = useAction('/api/twostep/off', () => { setOffCode(''); refreshMe(); });
  const revoke = useAction('/api/devices/revoke');
  const saveUser = useAction('/api/users', () => { setU({ ...u, password: '' }); qc.invalidateQueries({ queryKey: ['users'] }); });
  const call = <T,>(path: string, fields: Record<string, string> = {}) => post<T & { ok: boolean; message: string }>(path, fields);
  const start2 = useMutation({ mutationFn: () => call<{ secret: string; link: string }>('/api/twostep/start'), onSuccess: (r) => (r.ok ? setSetup(r) : toast(r.message, false)) });
  const devCode = useMutation({
    mutationFn: () => call<{ unit: string; code: string }>('/api/devices/code', { unit }),
    onSuccess: (r) => (r.ok ? setShown({ code: r.code, title: t('Scout device code for {{unit}}', { unit: r.unit }), text: t('Type this code on the scout phone of {{unit}}. It replaces the unit\'s earlier code.', { unit: r.unit }) }) : toast(r.message, false)),
  });
  const resetCode = useMutation({
    mutationFn: () => call<{ code: string; minutes: number }>('/api/users/reset', { name: resetName }),
    onSuccess: (r) => (r.ok ? setShown({ code: r.code, title: t('Reset code for {{name}}', { name: resetName }), text: t('Give this code to {{name}} in person or by phone. On the sign-in page they choose "Forgot password?" and set a new password with it. It works once, for {{n}} minutes.', { name: resetName, n: r.minutes }) }) : toast(r.message, false)),
  });

  if (!s.token && !can('OFFICER')) return null;
  return (
    <Section title={t('Users and access')} sub={t('Every change made here or on the network is recorded with the name of the person who made it.')}>
      <Stack spacing={3}>
        {s.token && <Typography variant="body2">{t('Signed in as')} <b>{s.officer}</b> <Chip size="small" label={s.role} /></Typography>}
        {s.token && (
          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>{t('Two-step sign-in')}</Typography>
            {me.data?.twoStep ? (
              <Stack component="form" direction="row" spacing={1} sx={{ alignItems: 'center', flexWrap: 'wrap', gap: 1 }} onSubmit={(e: FormEvent) => { e.preventDefault(); off2.mutate({ code: offCode }); }}>
                <Typography variant="body2" color="text.secondary">{t('On: signing in also needs the code from your authenticator app.')}</Typography>
                <TextField size="small" label={t('Current code')} value={offCode} onChange={(e) => setOffCode(e.target.value)} slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 6 } }} />
                <Button type="submit" variant="outlined">{t('Switch off')}</Button>
              </Stack>
            ) : setup ? (
              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">{t('Add CrowdWatch to an authenticator app (Google Authenticator, Microsoft Authenticator, Aegis ...) with this key, or open the link on the phone, then enter the code the app shows.')}</Typography>
                <Typography sx={{ fontFamily: 'monospace', fontSize: 22, fontWeight: 800, letterSpacing: 2 }}>{setup.secret.replace(/(.{4})/g, '$1 ').trim()}</Typography>
                <Link href={setup.link}>{t('Open in the authenticator app')}</Link>
                <Stack component="form" direction="row" spacing={1} onSubmit={(e: FormEvent) => { e.preventDefault(); confirm2.mutate({ code }); }}>
                  <TextField size="small" label={t('Code from the app')} value={code} onChange={(e) => setCode(e.target.value)} slotProps={{ htmlInput: { inputMode: 'numeric', maxLength: 6 } }} />
                  <Button type="submit" variant="contained">{t('Switch on')}</Button>
                </Stack>
              </Stack>
            ) : (
              <Stack spacing={1}>
                <Typography variant="body2" color="text.secondary">{t('Off. With it on, signing in needs a 6-digit code from an app on your phone as well as the password, so a stolen password is not enough. Recommended for administrators.')}</Typography>
                <Box><Button variant="outlined" onClick={() => start2.mutate()}>{t('Set up two-step sign-in')}</Button></Box>
              </Stack>
            )}
          </Box>
        )}
        {can('OFFICER') && (
          <Box>
            <Typography variant="subtitle2">{t('Scout device codes')}</Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{t('Each scout phone gets its own code that only works for its unit. A new code cancels the old one.')}</Typography>
            <Stack component="form" direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }} onSubmit={(e: FormEvent) => { e.preventDefault(); devCode.mutate(); }}>
              <TextField select size="small" label={t('Unit')} value={unit} onChange={(e) => setUnit(e.target.value)} required sx={{ minWidth: 200 }}>
                {(units ?? []).map((x) => <MenuItem key={x.id} value={x.id}>{x.id} · {x.name}</MenuItem>)}
              </TextField>
              <Button type="submit" variant="contained">{t('Issue new code')}</Button>
              <Button variant="outlined" disabled={!unit} onClick={() => revoke.mutate({ unit })}>{t('Cancel code')}</Button>
            </Stack>
            <Box sx={{ mt: 2 }}>
              <Button variant="outlined" onClick={() => get<AuditEntry[] | { message: string }>('/api/audit').then((r) => (Array.isArray(r) ? setAudit(r) : toast(r.message, false))).catch((e: Error) => toast(e.message, false))}>{t('Show audit log')}</Button>
            </Box>
            {audit && (
              <TableContainer sx={{ mt: 1, maxHeight: 420 }}>
                <Table size="small" stickyHeader>
                  <TableHead><TableRow><TableCell>{t('Time')}</TableCell><TableCell>{t('User')}</TableCell><TableCell>{t('Role')}</TableCell><TableCell>{t('Action')}</TableCell><TableCell>{t('Result')}</TableCell><TableCell>{t('Details')}</TableCell></TableRow></TableHead>
                  <TableBody>
                    {audit.map((e, k) => (
                      <TableRow key={k}>
                        <TableCell sx={{ fontFamily: 'monospace', whiteSpace: 'nowrap' }}>{e.time.replace('T', ' ')}</TableCell><TableCell>{e.user}</TableCell><TableCell>{e.role}</TableCell>
                        <TableCell sx={{ fontFamily: 'monospace' }}>{e.action}</TableCell>
                        <TableCell sx={{ color: e.ok ? undefined : 'error.main', fontWeight: 700 }}>{e.ok ? 'OK' : t('REFUSED')}</TableCell><TableCell>{e.summary}</TableCell>
                      </TableRow>
                    ))}
                    {audit.length === 0 && <TableRow><TableCell colSpan={6}>{t('none yet')}</TableCell></TableRow>}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          </Box>
        )}
        {can('ADMIN') && (
          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1 }}>{t('Users')}</Typography>
            {users.data && (
              <TableContainer sx={{ mb: 2 }}>
                <Table size="small">
                  <TableHead><TableRow><TableCell>{t('User name')}</TableCell><TableCell>{t('Role')}</TableCell><TableCell>{t('Can sign in')}</TableCell><TableCell>{t('Two-step')}</TableCell></TableRow></TableHead>
                  <TableBody>
                    {users.data.map((x) => (
                      <TableRow key={x.name} hover onClick={() => setU({ name: x.name, role: x.role, password: '', active: x.active, twoStepOff: false, directory: x.directory })} sx={{ cursor: 'pointer' }}>
                        <TableCell sx={{ fontFamily: 'monospace' }}>{x.name} {x.directory && <Chip size="small" label={t('directory')} />}</TableCell>
                        <TableCell>{x.role} {x.role === 'ADMIN' && !x.twoStep && <Chip size="small" color="warning" variant="outlined" label={t('no two-step')} />}</TableCell>
                        <TableCell>{x.active ? 'OK' : '-'}</TableCell><TableCell>{x.twoStep ? t('on') : '-'}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
            <Stack component="form" spacing={1.5} onSubmit={(e: FormEvent) => {
              e.preventDefault();
              saveUser.mutate({ name: u.name, role: u.role, password: u.password || null, active: u.active ? '1' : '0', twostep: u.twoStepOff ? 'off' : null, directory: u.directory ? '1' : null });
            }}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
                <TextField size="small" label={t('User name')} value={u.name} onChange={(e) => setU({ ...u, name: e.target.value })} required slotProps={{ htmlInput: { autoCapitalize: 'none' } }} />
                <TextField select size="small" label={t('Role')} value={u.role} onChange={(e) => setU({ ...u, role: e.target.value })} sx={{ minWidth: 160 }}>
                  {['OPERATOR', 'OFFICER', 'ADMIN'].map((r) => <MenuItem key={r} value={r}>{r}</MenuItem>)}
                </TextField>
                <TextField size="small" type="password" label={t('Password')} placeholder={t('empty keeps the current password')} value={u.password} onChange={(e) => setU({ ...u, password: e.target.value })} autoComplete="new-password" />
              </Stack>
              <Stack direction="row" sx={{ flexWrap: 'wrap' }}>
                <FormControlLabel control={<Checkbox checked={u.active} onChange={(e) => setU({ ...u, active: e.target.checked })} />} label={t('Can sign in')} />
                <FormControlLabel control={<Checkbox checked={u.twoStepOff} onChange={(e) => setU({ ...u, twoStepOff: e.target.checked })} />} label={t('Switch two-step off (lost phone)')} />
                <FormControlLabel control={<Checkbox checked={u.directory} onChange={(e) => setU({ ...u, directory: e.target.checked })} />} label={t('Signs in with the company directory (LDAP / Active Directory)')} />
              </Stack>
              <Box><Button type="submit" variant="contained" disabled={saveUser.isPending}>{t('Save user')}</Button></Box>
            </Stack>
            <Stack component="form" direction="row" spacing={1} sx={{ mt: 2 }} onSubmit={(e: FormEvent) => { e.preventDefault(); resetCode.mutate(); }}>
              <TextField size="small" label={t('Forgot password: user name')} value={resetName} onChange={(e) => setResetName(e.target.value)} required />
              <Button type="submit" variant="outlined">{t('Make a reset code')}</Button>
            </Stack>
          </Box>
        )}
      </Stack>
      <CodeDialog code={shown?.code ?? null} title={shown?.title ?? ''} text={shown?.text ?? ''} onClose={() => setShown(null)} />
    </Section>
  );
}

/** Data-protection requests: find, export or erase a person's data (administrators). */
function Privacy() {
  const { t } = useTranslation();
  const toast = useToast();
  const [find, setFind] = useState('');
  const [result, setResult] = useState<PrivacyResult | null>(null);
  const [picked, setPicked] = useState<number[]>([]);
  const erase = useAction('/api/privacy/erase', () => { setResult(null); setPicked([]); });
  const search = (e: FormEvent) => {
    e.preventDefault();
    get<PrivacyResult & { message?: string }>('/api/privacy?find=' + encodeURIComponent(find))
      .then((r) => { if (r.incidents) { setResult(r); setPicked([]); } else toast(r.message ?? 'Refused.', false); })
      .catch((err: Error) => toast(err.message, false));
  };
  return (
    <Section title={t('Personal data requests')} sub={t('Under data-protection law (PDPL, GDPR) a person may ask for a copy of their data or for it to be deleted. Search by name, phone or e-mail; export what is found, or erase it - the rest of each record stays for statistics. Every search and erasure is in the audit log.')}>
      <Stack component="form" direction="row" spacing={1} onSubmit={search}>
        <TextField size="small" label={t('Name, phone or e-mail')} value={find} onChange={(e) => setFind(e.target.value)} required slotProps={{ htmlInput: { minLength: 3 } }} />
        <Button type="submit" variant="outlined">{t('Search')}</Button>
      </Stack>
      {result && (
        <Box sx={{ mt: 2 }}>
          <TableContainer>
            <Table size="small">
              <TableHead><TableRow><TableCell /><TableCell>{t('Incident')}</TableCell><TableCell>{t('Area')}</TableCell><TableCell>{t('Details')}</TableCell><TableCell>{t('Contact')}</TableCell></TableRow></TableHead>
              <TableBody>
                {result.incidents.map((i) => (
                  <TableRow key={i.id + i.where}>
                    <TableCell padding="checkbox"><Checkbox checked={picked.includes(i.id)} onChange={(e) => setPicked(e.target.checked ? [...picked, i.id] : picked.filter((x) => x !== i.id))} inputProps={{ 'aria-label': 'I' + i.id }} /></TableCell>
                    <TableCell>I{i.id} {i.category}<Typography variant="caption" color="text.secondary" component="div">{i.reported} · {i.where}</Typography></TableCell>
                    <TableCell>{i.area}</TableCell><TableCell>{i.details}</TableCell><TableCell>{i.contact}</TableCell>
                  </TableRow>
                ))}
                {result.incidents.length === 0 && <TableRow><TableCell colSpan={5}>{t('Nothing found in incidents.')}</TableCell></TableRow>}
              </TableBody>
            </Table>
          </TableContainer>
          <Typography variant="body2" color="text.secondary" sx={{ my: 1 }}>{t('{{n}} message(s) found as well.', { n: result.messages.length })}</Typography>
          <Stack direction="row" spacing={1}>
            <Button variant="outlined" onClick={() => download('/api/privacy/export?find=' + encodeURIComponent(result.find), 'crowdwatch-personal-data.json').catch((e: Error) => toast(e.message, false))}>{t('Export as a file')}</Button>
            <Button variant="outlined" color="error" disabled={picked.length === 0 || erase.isPending}
              onClick={() => { if (confirm(t('Erase the personal data of {{n}} incident(s)? This cannot be undone.', { n: picked.length }))) erase.mutate({ ids: picked.join(',') }); }}>
              {t('Erase the selected')}
            </Button>
          </Stack>
          {picked.length === 0 && result.incidents.length > 0 && <Alert severity="info" sx={{ mt: 1 }}>{t('Select the incidents to erase first.')}</Alert>}
        </Box>
      )}
    </Section>
  );
}
