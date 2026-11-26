import { useMemo, useState, type FormEvent } from 'react';
import {
  Button, Card, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Skeleton, Stack, Tab, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, Tabs, TextField, Typography,
} from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import { useTranslation } from 'react-i18next';
import { can } from '../api/client';
import { useIncidents, useVenue } from '../api/queries';
import type { IncidentRow } from '../api/types';
import { StateChip, useAction } from '../components/ui';
import { PRIORITY_COLOR, STATUS_COLOR } from '../theme';

type Filter = 'OPEN' | 'WAITING' | 'ASSIGNED' | 'ON_SCENE' | 'REVIEW';
const FILTERS: { f: Filter; label: string; test: (i: IncidentRow) => boolean }[] = [
  { f: 'OPEN', label: 'Open', test: () => true },
  { f: 'WAITING', label: 'Waiting', test: (i) => i.status === 'WAITING' },
  { f: 'ASSIGNED', label: 'Assigned', test: (i) => i.status === 'ASSIGNED' },
  { f: 'ON_SCENE', label: 'On scene', test: (i) => i.status === 'ON_SCENE' },
  { f: 'REVIEW', label: 'Needs review', test: (i) => i.review },
];

export function Incidents({ onIncident }: { onIncident: (id: number) => void }) {
  const { t } = useTranslation();
  const { data: incidents, isLoading } = useIncidents();
  const [filter, setFilter] = useState<Filter>('OPEN');
  const [report, setReport] = useState(false);
  const shown = useMemo(() => (incidents ?? []).filter(FILTERS.find((x) => x.f === filter)!.test), [incidents, filter]);

  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ alignItems: 'center' }}>
        <Typography variant="h5" sx={{ fontWeight: 700, flex: 1 }}>{t('Incidents')}</Typography>
        {can('OPERATOR') && <Button variant="contained" startIcon={<AddIcon />} onClick={() => setReport(true)}>{t('Report incident')}</Button>}
      </Stack>
      <Tabs value={filter} onChange={(_, v) => setFilter(v)} variant="scrollable" allowScrollButtonsMobile>
        {FILTERS.map((x) => <Tab key={x.f} value={x.f} label={`${t(x.label)} (${(incidents ?? []).filter(x.test).length})`} />)}
      </Tabs>
      {isLoading ? <Skeleton variant="rounded" height={300} /> : (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead>
              <TableRow>
                <TableCell>ID</TableCell>
                <TableCell>{t('Priority')}</TableCell>
                <TableCell>{t('Category')}</TableCell>
                <TableCell>{t('Area')}</TableCell>
                <TableCell>{t('Status')}</TableCell>
                <TableCell>{t('Unit')}</TableCell>
                <TableCell>{t('Device')}</TableCell>
                <TableCell align="right">{t('Open for')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map((i) => (
                <TableRow key={i.id} hover onClick={() => onIncident(i.id)} sx={{ cursor: 'pointer' }}>
                  <TableCell sx={{ fontWeight: 700 }}>I{i.id}</TableCell>
                  <TableCell><StateChip label={i.priority} color={PRIORITY_COLOR[i.priority]} /></TableCell>
                  <TableCell>{i.label}{i.automatic && <Typography component="span" variant="caption" color="text.secondary"> · {t('automatic')}</Typography>}</TableCell>
                  <TableCell>{i.area}</TableCell>
                  <TableCell><StateChip label={i.status} color={STATUS_COLOR[i.status]} />{i.review && <> <StateChip label="Needs review" color="#d97706" /></>}</TableCell>
                  <TableCell>{i.unit ?? '-'}</TableCell>
                  <TableCell sx={{ color: !i.acknowledged && i.unit ? 'warning.main' : undefined }}>{i.device}</TableCell>
                  <TableCell align="right">{i.minutesOpen} {t('min')}</TableCell>
                </TableRow>
              ))}
              {shown.length === 0 && <TableRow><TableCell colSpan={8}><Typography color="text.secondary">{t('No incidents here.')}</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <ReportDialog open={report} onClose={() => setReport(false)} />
    </Stack>
  );
}

function ReportDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const { data: venue } = useVenue();
  const [f, setF] = useState({ category: '', area: '', details: '', contact: '', language: '' });
  const send = useAction('/api/incidents', () => { setF({ category: '', area: '', details: '', contact: '', language: '' }); onClose(); });
  const set = (k: keyof typeof f) => (e: { target: { value: string } }) => setF({ ...f, [k]: e.target.value });
  function submit(e: FormEvent) {
    e.preventDefault();
    send.mutate({ category: f.category, area: f.area, details: f.details, contact: f.contact || null, language: f.language || null });
  }
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm">
      <form onSubmit={submit}>
        <DialogTitle>{t('Report incident')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <TextField select label={t('Category')} value={f.category} onChange={set('category')} required>
              {(venue?.categories ?? []).map((c) => <MenuItem key={c.code} value={c.code}>{c.label} ({t(c.priority)})</MenuItem>)}
            </TextField>
            <TextField select label={t('Area')} value={f.area} onChange={set('area')} required>
              {(venue?.areas ?? []).map((a) => <MenuItem key={a} value={a}>{a}</MenuItem>)}
            </TextField>
            <TextField label={t('Details')} value={f.details} onChange={set('details')} multiline minRows={2} required />
            <Stack direction="row" spacing={2}>
              <TextField label={t('Contact (optional)')} value={f.contact} onChange={set('contact')} sx={{ flex: 1 }} />
              <TextField label={t('Language (optional)')} value={f.language} onChange={set('language')} sx={{ flex: 1 }} />
            </Stack>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{t('Cancel')}</Button>
          <Button type="submit" variant="contained" disabled={send.isPending}>{t('Report')}</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
