import { useMemo, useState } from 'react';
import { Box, Card, CardActionArea, CardContent, MenuItem, Skeleton, Stack, TextField, Typography } from '@mui/material';
import BatteryIcon from '@mui/icons-material/Battery5Bar';
import { useTranslation } from 'react-i18next';
import { useUnits } from '../api/queries';
import type { UnitStatus } from '../api/types';
import { StateChip } from '../components/ui';
import { PRIORITY_COLOR, UNIT_COLOR } from '../theme';

const STATUSES: UnitStatus[] = ['AVAILABLE', 'BUSY', 'OFF_DUTY', 'UNREACHABLE'];

export function Units({ onIncident }: { onIncident: (id: number) => void }) {
  const { t } = useTranslation();
  const { data: units, isLoading } = useUnits();
  const [status, setStatus] = useState<UnitStatus | ''>('');
  const shown = useMemo(() => (units ?? []).filter((u) => !status || u.status === status), [units, status]);
  return (
    <Stack spacing={2}>
      <Stack direction="row" sx={{ alignItems: 'center' }}>
        <Typography variant="h5" sx={{ fontWeight: 700, flex: 1 }}>{t('Units and scouts')}</Typography>
        <TextField size="small" select value={status} onChange={(e) => setStatus(e.target.value as UnitStatus | '')} sx={{ minWidth: 160 }}>
          <MenuItem value="">{t('All statuses')}</MenuItem>
          {STATUSES.map((s) => <MenuItem key={s} value={s}>{t(s)}</MenuItem>)}
        </TextField>
      </Stack>
      {isLoading ? <Skeleton variant="rounded" height={300} /> : (
        <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))' }}>
          {shown.map((u) => {
            const body = (
              <CardContent>
                <Stack direction="row" sx={{ alignItems: 'center', mb: 1 }}>
                  <Box sx={{ flex: 1, minWidth: 0 }}>
                    <Typography sx={{ fontWeight: 700 }} noWrap>{u.name}</Typography>
                    <Typography variant="caption" color="text.secondary">{u.id} · {u.area} · {u.sector}</Typography>
                  </Box>
                  <StateChip label={u.status} color={UNIT_COLOR[u.status]} />
                </Stack>
                <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1 }}>
                  <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                    <Box sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: u.online ? 'success.main' : 'text.disabled' }} />
                    <Typography variant="body2">{t(u.online ? 'online' : 'offline')}</Typography>
                  </Stack>
                  {u.battery >= 0 && (
                    <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center', color: u.battery < 20 ? 'error.main' : undefined }}>
                      <BatteryIcon fontSize="small" /><Typography variant="body2">{u.battery}%</Typography>
                    </Stack>
                  )}
                  {u.shift && <Typography variant="body2" color="text.secondary">{t('Shift')} {u.shift}</Typography>}
                </Stack>
                {u.skills && <Typography variant="body2" color="text.secondary" noWrap>{t('Skills')}: {u.skills}</Typography>}
                {u.task ? (
                  <Box sx={{ mt: 1, p: 1, borderRadius: 1, bgcolor: 'action.hover' }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <Typography variant="body2" sx={{ fontWeight: 600, flex: 1 }} noWrap>I{u.task.id} · {u.task.label} · {u.task.area}</Typography>
                      <StateChip label={u.task.priority} color={PRIORITY_COLOR[u.task.priority]} />
                    </Stack>
                    <Typography variant="caption" color="text.secondary">{u.task.device}</Typography>
                  </Box>
                ) : <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('No task')}{u.staged ? ` · ${u.staged}` : ''}{u.reinforcing ? ` · ${u.reinforcing}` : ''}</Typography>}
              </CardContent>
            );
            return <Card key={u.id}>{u.task ? <CardActionArea onClick={() => onIncident(u.task!.id)}>{body}</CardActionArea> : body}</Card>;
          })}
        </Box>
      )}
    </Stack>
  );
}
