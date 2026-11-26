import { useState, type FormEvent } from 'react';
import {
  Alert, Box, Button, Card, CardContent, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Table, TableBody, TableCell, TableContainer,
  TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import { useTranslation } from 'react-i18next';
import { can } from '../api/client';
import { useAnnouncements, useEvacuation, useLayout, useOverview, useVenue } from '../api/queries';
import { areaItems, zoneItems } from '../components/Dialogs';
import { LevelLegend, VenueMap } from '../components/VenueMap';
import { StateChip, fmt, useAction } from '../components/ui';

export function Emergency() {
  const { t } = useTranslation();
  const { data: o } = useOverview();
  const { data: venue } = useVenue();
  const { data: layout } = useLayout();
  const [zone, setZone] = useState('');
  const [confirm, setConfirm] = useState(false);
  const active = o?.evacuation ?? null;
  const { data: plan } = useEvacuation(active ?? zone, !!(active || zone));
  const [sos, setSos] = useState({ area: '', text: '' });
  const [alarm, setAlarm] = useState({ area: '', text: 'Fire alarm' });
  const [bc, setBc] = useState('');
  const sendSos = useAction('/api/sos', () => setSos({ area: '', text: '' }));
  const evacuate = useAction('/api/evacuate', () => setConfirm(false));
  const endEvac = useAction('/api/evacuate/end', () => setZone(''));
  const proposal = useAction('/api/proposal');
  const raise = useAction('/api/alarm');
  const broadcast = useAction('/api/broadcast', () => setBc(''));
  const officer = can('OFFICER');
  const rows = plan ? plan.rows.filter((r) => r.inZone || parseInt(r.people, 10) > 0) : [];

  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('SOS and evacuation')}</Typography>
      {o?.proposal && (
        <Alert severity="error" variant="outlined" action={officer && (
          <Stack direction="row" spacing={1}>
            <Button color="error" variant="contained" size="small" disabled={proposal.isPending} onClick={() => proposal.mutate({ decision: 'start' })}>{t('Start evacuation')}</Button>
            <Button color="inherit" size="small" disabled={proposal.isPending} onClick={() => proposal.mutate({ decision: 'dismiss' })}>{t('Dismiss')}</Button>
          </Stack>)}>
          <b>{t('Evacuation PROPOSED for {{zone}}', { zone: o.proposal.zone })}</b> — {o.proposal.reason} ({o.proposal.time}). {t('The system never starts an evacuation by itself.')}
        </Alert>
      )}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 2fr' } }}>
        <Card sx={{ bgcolor: 'error.main', color: 'error.contrastText' }}><CardContent>
          <Typography variant="subtitle1" sx={{ fontWeight: 800 }}>{t('SOS - someone needs help now')}</Typography>
          <Typography variant="body2" sx={{ opacity: 0.9, mb: 2 }}>{t('Creates a CRITICAL incident and sends the nearest free units straight away.')}</Typography>
          <Stack component="form" spacing={1.5} onSubmit={(e: FormEvent) => { e.preventDefault(); sendSos.mutate({ area: sos.area, text: sos.text || 'SOS from the control room' }); }}
            sx={{ '& .MuiInputBase-root': { bgcolor: 'background.paper' } }}>
            <TextField select size="small" label={t('Where')} value={sos.area} onChange={(e) => setSos({ ...sos, area: e.target.value })} required>{areaItems(venue?.areas)}</TextField>
            <TextField size="small" placeholder={t('e.g. person collapsed near gate A4')} value={sos.text} onChange={(e) => setSos({ ...sos, text: e.target.value })} slotProps={{ htmlInput: { maxLength: 300 } }} />
            <Button type="submit" variant="contained" sx={{ bgcolor: '#fff', color: 'error.main', '&:hover': { bgcolor: '#fee2e2' } }} disabled={sendSos.isPending}>{t('Send SOS')}</Button>
          </Stack>
        </CardContent></Card>

        <Card><CardContent>
          <Stack direction="row" sx={{ alignItems: 'center', mb: 1 }}>
            <Typography variant="subtitle1" sx={{ fontWeight: 700, flex: 1 }}>{t('Evacuation plan')}</Typography>
            {active && <StateChip label={t('IN PROGRESS')} color="#ea580c" />}
          </Stack>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {active ? t('Evacuation of {{zone}} is in progress. Units in the zone guide people to their exit; others keep exits clear.', { zone: active })
              : t('Preview first: every public area is sent to its nearest exit outside the zone, by a route that avoids the zone.')}
          </Typography>
          {active ? (
            officer && <Button variant="contained" color="success" disabled={endEvac.isPending} onClick={() => endEvac.mutate({})}>{t('End evacuation')}</Button>
          ) : (
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1}>
              <TextField select size="small" label={t('Zone')} value={zone} onChange={(e) => setZone(e.target.value)} sx={{ minWidth: 240 }}>{zoneItems(venue, t)}</TextField>
              {officer && <Button variant="contained" color="error" disabled={!zone} onClick={() => setConfirm(true)}>{t('Start evacuation')}</Button>}
            </Stack>
          )}
          {plan && (active || zone) && (
            <Box sx={{ mt: 2 }}>
              {plan.rows.length === 0 ? <Alert severity="info">{t('No plan: the venue has no exits defined or the zone is unknown.')}</Alert> : (
                <Stack spacing={2}>
                  {plan.clearMinutes > 0 && <Alert severity="info">{t('Estimated time until everyone in the zone is out:')} <b>{plan.clearMinutes} {t('min')}</b></Alert>}
                  {layout?.map && <><VenueMap rows={plan.rows} maxHeight={460} /><LevelLegend t={t} /></>}
                  <TableContainer>
                    <Table size="small">
                      <TableHead><TableRow><TableCell>{t('From')}</TableCell><TableCell>{t('Exit')}</TableCell><TableCell>{t('Route')}</TableCell><TableCell>{t('Walk')}</TableCell><TableCell align="right">{t('People')}</TableCell><TableCell align="right">{t('All out')}</TableCell></TableRow></TableHead>
                      <TableBody>
                        {rows.map((r, k) => (
                          <TableRow key={k}>
                            <TableCell><b>{r.from}</b> {r.inZone && <StateChip label={t('in zone')} color="#ea580c" />}</TableCell>
                            <TableCell>{r.exit}</TableCell>
                            <TableCell sx={{ color: 'text.secondary' }}>{String(r.path).replace(/^\[|\]$/g, '')}</TableCell>
                            <TableCell>{r.min} {t('min')}</TableCell>
                            <TableCell align="right">{fmt(parseInt(r.people, 10) || 0)}</TableCell>
                            <TableCell align="right">{r.clear && r.clear !== '-' && plan.clearMinutes ? `~${r.clear} ${t('min')}` : '-'}</TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </TableContainer>
                </Stack>
              )}
            </Box>
          )}
        </CardContent></Card>
      </Box>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <PublicMessages />
        <Card><CardContent>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Alarm input')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('For a fire or other alarm panel without a connection. A unit is sent to check at once and an evacuation of the sector is proposed.')}</Typography>
          {can('OPERATOR') && (
            <Stack component="form" spacing={1.5} onSubmit={(e: FormEvent) => { e.preventDefault(); raise.mutate(alarm); }}>
              <TextField select size="small" label={t('Where')} value={alarm.area} onChange={(e) => setAlarm({ ...alarm, area: e.target.value })} required>{areaItems(venue?.areas)}</TextField>
              <TextField size="small" label={t('Alarm')} value={alarm.text} onChange={(e) => setAlarm({ ...alarm, text: e.target.value })} slotProps={{ htmlInput: { maxLength: 120 } }} />
              <Box><Button type="submit" variant="contained" color="error" disabled={raise.isPending}>{t('Raise alarm')}</Button></Box>
            </Stack>
          )}
        </CardContent></Card>
      </Box>

      <Card><CardContent>
        <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1 }}>{t('Broadcast to every unit')}</Typography>
        {officer && (
          <Stack component="form" direction="row" spacing={1} onSubmit={(e: FormEvent) => { e.preventDefault(); broadcast.mutate({ text: bc }); }}>
            <TextField size="small" placeholder={t('Sent to every device and the radio gateway')} value={bc} onChange={(e) => setBc(e.target.value)} required sx={{ flex: 1 }} slotProps={{ htmlInput: { maxLength: 300 } }} />
            <Button type="submit" variant="contained" disabled={broadcast.isPending}>{t('Send broadcast')}</Button>
          </Stack>
        )}
      </CardContent></Card>

      <Dialog open={confirm} onClose={() => setConfirm(false)}>
        <DialogTitle>{t('Start the evacuation of {{zone}}?', { zone })}</DialogTitle>
        <DialogContent><Typography>{t('Every unit is told at once, public routes avoid the zone and the screens show the way out.')}</Typography></DialogContent>
        <DialogActions>
          <Button onClick={() => setConfirm(false)}>{t('Cancel')}</Button>
          <Button variant="contained" color="error" disabled={evacuate.isPending} onClick={() => evacuate.mutate({ zone })}>{t('Start evacuation')}</Button>
        </DialogActions>
      </Dialog>
    </Stack>
  );
}

/** Drafts for public screens and the PA wait for an officer; the officer can also write one. */
function PublicMessages() {
  const { t } = useTranslation();
  const { data: signs } = useAnnouncements();
  const [text, setText] = useState('');
  const decide = useAction('/api/announcement');
  const announce = useAction('/api/announce', () => setText(''));
  const pending = (signs ?? []).filter((a) => a.status === 'PROPOSED');
  const done = (signs ?? []).filter((a) => a.status !== 'PROPOSED').slice(0, 5);
  const officer = can('OFFICER');
  return (
    <Card><CardContent>
      <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Public screens and PA')}</Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('The system drafts messages (busy areas, evacuation, long-missing persons). Nothing reaches the public until an officer approves it.')}</Typography>
      <Stack spacing={1} sx={{ mb: 2 }}>
        {pending.length === 0 && <Typography variant="body2" color="text.secondary">{t('No drafts waiting.')}</Typography>}
        {pending.map((a) => (
          <Box key={a.id} sx={{ p: 1.5, borderRadius: 1, bgcolor: 'action.hover' }}>
            <Typography variant="caption" color="text.secondary">#{a.id} · {a.time} · {a.ref}</Typography>
            <Typography sx={{ fontWeight: 600, mb: 1 }}>{a.text}</Typography>
            {officer && (
              <Stack direction="row" spacing={1}>
                <Button size="small" variant="contained" color="success" disabled={decide.isPending} onClick={() => decide.mutate({ id: a.id, decision: 'approve' })}>{t('Approve and send')}</Button>
                <Button size="small" disabled={decide.isPending} onClick={() => decide.mutate({ id: a.id, decision: 'reject' })}>{t('Reject')}</Button>
              </Stack>
            )}
          </Box>
        ))}
      </Stack>
      {officer && (
        <Stack component="form" direction="row" spacing={1} onSubmit={(e: FormEvent) => { e.preventDefault(); announce.mutate({ text }); }}>
          <TextField size="small" label={t('Your own message')} placeholder={t('e.g. Gate C is closed, please use Gate B')} value={text} onChange={(e) => setText(e.target.value)} required sx={{ flex: 1 }} slotProps={{ htmlInput: { maxLength: 300 } }} />
          <Button type="submit" variant="outlined" disabled={announce.isPending}>{t('Send to screens and PA')}</Button>
        </Stack>
      )}
      {done.length > 0 && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
          {t('Recent')}: {done.map((a) => `#${a.id} ${a.status.toLowerCase()}${a.by ? ' ' + a.by : ''}`).join(' · ')}
        </Typography>
      )}
    </CardContent></Card>
  );
}
