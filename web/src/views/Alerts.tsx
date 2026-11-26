import { useMemo, useState } from 'react';
import { Card, MenuItem, Skeleton, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { useAlerts } from '../api/queries';
import { StateChip } from '../components/ui';

const KIND_COLOR: Record<string, string> = {
  CROWD: '#ea580c', TREND: '#7c3aed', CLEARED: '#16a34a', NO_UNIT: '#dc2626', ESCALATED: '#dc2626', SOS: '#dc2626', ALARM: '#dc2626',
  SUPERVISION: '#dc2626', PROPOSAL: '#dc2626', EVACUATION: '#ea580c', FLOW: '#ea580c', NO_ACK: '#d97706', DELIVERY: '#d97706',
  FOLLOW_UP: '#d97706', ANNOUNCE: '#d97706', AUTO_DISPATCH: '#2563eb', BROADCAST: '#2563eb', REPOSITION: '#2563eb', SHIFT: '#2563eb',
  EVENT: '#7c3aed', LOST: '#7c3aed',
};

export function Alerts() {
  const { t } = useTranslation();
  const { data: alerts, isLoading } = useAlerts();
  const [kind, setKind] = useState('');
  const kinds = useMemo(() => [...new Set((alerts ?? []).map((a) => a.kind))].sort(), [alerts]);
  const shown = (alerts ?? []).filter((a) => !kind || a.kind === kind);
  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ alignItems: 'center' }}>
        <Typography variant="h5" sx={{ fontWeight: 700, flex: 1 }}>{t('Alerts')}</Typography>
        <TextField size="small" select value={kind} onChange={(e) => setKind(e.target.value)} sx={{ minWidth: 180 }}>
          <MenuItem value="">{t('All kinds')}</MenuItem>
          {kinds.map((k) => <MenuItem key={k} value={k}>{k}</MenuItem>)}
        </TextField>
      </Stack>
      {isLoading ? <Skeleton variant="rounded" height={300} /> : (
        <TableContainer component={Card}>
          <Table size="small">
            <TableHead><TableRow><TableCell>{t('Time')}</TableCell><TableCell>{t('Kind')}</TableCell><TableCell>{t('Alert')}</TableCell></TableRow></TableHead>
            <TableBody>
              {shown.map((a, k) => (
                <TableRow key={a.time + k}>
                  <TableCell sx={{ whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{a.time.substring(11, 19)}</TableCell>
                  <TableCell><StateChip label={a.kind} color={KIND_COLOR[a.kind] ?? '#64748b'} /></TableCell>
                  <TableCell>{a.text}{a.ref && <Typography component="span" variant="caption" color="text.secondary"> · {a.ref}</Typography>}</TableCell>
                </TableRow>
              ))}
              {shown.length === 0 && <TableRow><TableCell colSpan={3}><Typography color="text.secondary">{t('No alerts yet.')}</Typography></TableCell></TableRow>}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Stack>
  );
}
