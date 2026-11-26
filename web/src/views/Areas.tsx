import { useMemo, useState } from 'react';
import {
  Box, Button, Card, Drawer, IconButton, InputAdornment, LinearProgress, MenuItem, Skeleton, Stack, Table, TableBody, TableCell,
  TableContainer, TableHead, TableRow, TextField, Typography,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import CloseIcon from '@mui/icons-material/Close';
import { useTranslation } from 'react-i18next';
import { can } from '../api/client';
import { useAreas, useReadings } from '../api/queries';
import type { Area, Level } from '../api/types';
import { Chart, StateChip, fmt, useAction } from '../components/ui';
import { LEVEL_COLOR } from '../theme';

const LEVELS: Level[] = ['LOW', 'MODERATE', 'HIGH', 'CRITICAL'];

export function Areas() {
  const { t } = useTranslation();
  const { data: areas, isLoading } = useAreas();
  const [q, setQ] = useState('');
  const [level, setLevel] = useState<Level | ''>('');
  const [open, setOpen] = useState<string | null>(null);
  const shown = useMemo(() => {
    const s = q.trim().toLowerCase();
    return (areas ?? []).filter((a) => (!level || a.level === level) && (!s || [a.id, a.name, a.sector, a.type].some((x) => x?.toLowerCase().includes(s))));
  }, [areas, q, level]);
  const selected = areas?.find((a) => a.id === open) ?? null;

  return (
    <Stack spacing={2}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ alignItems: { sm: 'center' } }}>
        <Typography variant="h5" sx={{ fontWeight: 700, flex: 1 }}>{t('Areas and crowd')}</Typography>
        <TextField size="small" placeholder={t('Search')} value={q} onChange={(e) => setQ(e.target.value)}
          slotProps={{ input: { startAdornment: <InputAdornment position="start"><SearchIcon fontSize="small" /></InputAdornment> } }} />
        <TextField size="small" select value={level} onChange={(e) => setLevel(e.target.value as Level | '')} sx={{ minWidth: 160 }}>
          <MenuItem value="">{t('All levels')}</MenuItem>
          {LEVELS.map((l) => <MenuItem key={l} value={l}>{t(l)}</MenuItem>)}
        </TextField>
      </Stack>
      {isLoading ? <Skeleton variant="rounded" height={400} /> : (
        <TableContainer component={Card}>
          <Table size="small" stickyHeader>
            <TableHead>
              <TableRow>
                <TableCell>{t('Area')}</TableCell>
                <TableCell>{t('Sector')}</TableCell>
                <TableCell sx={{ minWidth: 180 }}>{t('People')}</TableCell>
                <TableCell>{t('Level')}</TableCell>
                <TableCell align="right">{t('Trend')}</TableCell>
                <TableCell align="right">{t('Critical in')}</TableCell>
                <TableCell align="right">{t('Density')}</TableCell>
                <TableCell align="right">{t('Units')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {shown.map((a) => (
                <TableRow key={a.id} hover selected={a.id === open} onClick={() => setOpen(a.id)} sx={{ cursor: 'pointer' }}>
                  <TableCell>
                    <Typography variant="body2" sx={{ fontWeight: 600 }}>{a.name}</Typography>
                    <Typography variant="caption" color="text.secondary">{a.id}{flags(a, t)}</Typography>
                  </TableCell>
                  <TableCell>{a.sector}</TableCell>
                  <TableCell>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
                      <LinearProgress variant="determinate" value={Math.min(100, a.percent)} sx={{ flex: 1, height: 8, borderRadius: 4, bgcolor: 'action.hover', '& .MuiLinearProgress-bar': { bgcolor: LEVEL_COLOR[a.level] } }} />
                      <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', minWidth: 40, textAlign: 'end' }}>{a.percent}%</Typography>
                    </Stack>
                    <Typography variant="caption" color="text.secondary">{fmt(a.occupancy)} / {fmt(a.capacity)}</Typography>
                  </TableCell>
                  <TableCell><StateChip label={a.level} color={LEVEL_COLOR[a.level]} /></TableCell>
                  <TableCell align="right" sx={{ color: a.ratePerMinute > 0 ? 'warning.main' : a.ratePerMinute < 0 ? 'success.main' : undefined, fontVariantNumeric: 'tabular-nums' }}>
                    {a.ratePerMinute > 0 ? '+' : ''}{fmt(a.ratePerMinute)}
                  </TableCell>
                  <TableCell align="right">{a.minutesToCritical >= 0 ? `${a.minutesToCritical} ${t('min')}` : '-'}</TableCell>
                  <TableCell align="right">{a.size > 0 ? a.density : '-'}</TableCell>
                  <TableCell align="right">{a.unitsHere}{a.unitsWanted > a.unitsHere ? ` / ${a.unitsWanted}` : ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
      <Drawer anchor="right" open={selected != null} onClose={() => setOpen(null)} sx={{ zIndex: (th) => th.zIndex.modal }}>
        {selected && <AreaPanel area={selected} onClose={() => setOpen(null)} />}
      </Drawer>
    </Stack>
  );
}

function flags(a: Area, t: (s: string) => string) {
  const f = [a.restricted && t('restricted'), a.evacuating && t('evacuating'), (a.stale || a.suspect) && t('sensor stale')].filter(Boolean);
  return f.length ? ' · ' + f.join(' · ') : '';
}

function AreaPanel({ area, onClose }: { area: Area; onClose: () => void }) {
  const { t } = useTranslation();
  const { data: r } = useReadings(area.id);
  const [count, setCount] = useState('');
  const save = useAction('/api/occupancy', () => setCount(''));
  const option = useMemo(() => {
    // the sampled readings, then the count now
    const pts = [...(r?.readings ?? []).map((p) => [Math.round((p.s / 60) * 10) / 10, p.n]), [0, area.occupancy]];
    return {
      grid: { left: 8, right: 16, top: 16, bottom: 36, containLabel: true },
      tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmt(v) },
      xAxis: { type: 'value', min: 0, inverse: true, minInterval: 1, name: t('minutes ago'), nameLocation: 'middle', nameGap: 28 },
      yAxis: { type: 'value', max: (m: { max: number }) => Math.max(area.capacity, m.max), splitLine: { lineStyle: { opacity: 0.3 } } },
      series: [
        { type: 'line', data: pts, showSymbol: pts.length < 4, smooth: true, areaStyle: { opacity: 0.15 }, lineStyle: { width: 2 }, itemStyle: { color: LEVEL_COLOR[area.level] },
          markLine: { silent: true, symbol: 'none', data: [{ yAxis: area.capacity, label: { formatter: fmt(area.capacity) } }], lineStyle: { type: 'dashed', color: '#dc2626' } } },
      ],
    };
  }, [r, area.capacity, area.level, area.occupancy, t]);

  return (
    <Box sx={{ width: { xs: '100vw', sm: 460 }, p: 3 }} role="dialog" aria-label={area.name}>
      <Stack direction="row" sx={{ alignItems: 'flex-start', mb: 2 }}>
        <Box sx={{ flex: 1 }}>
          <Typography variant="h6" sx={{ fontWeight: 700 }}>{area.name}</Typography>
          <Typography variant="body2" color="text.secondary">{area.id} · {area.sector} · {area.type}</Typography>
        </Box>
        <IconButton onClick={onClose} aria-label="close"><CloseIcon /></IconButton>
      </Stack>
      <Stack direction="row" spacing={1} sx={{ mb: 2, flexWrap: 'wrap', gap: 1 }}>
        <StateChip label={area.level} color={LEVEL_COLOR[area.level]} />
        {area.size > 0 && <StateChip label={`${area.density} ${t('per m²')}`} color={LEVEL_COLOR[area.densityLevel]} />}
        {area.restricted && <StateChip label="restricted" color="#7c3aed" />}
        {area.evacuating && <StateChip label="evacuating" color="#dc2626" />}
      </Stack>
      <Typography variant="h4" sx={{ fontWeight: 700, fontVariantNumeric: 'tabular-nums' }}>{fmt(area.occupancy)} <Typography component="span" color="text.secondary">/ {fmt(area.capacity)} ({area.percent}%)</Typography></Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {t('Trend')}: {area.ratePerMinute > 0 ? '+' : ''}{area.ratePerMinute} {t('per min')}
        {area.minutesToCritical >= 0 && ` · ${t('Critical in')} ${area.minutesToCritical} ${t('min')}`}
      </Typography>
      <Typography variant="subtitle2" sx={{ mt: 2 }}>{t('Last readings')}</Typography>
      <Chart option={option} height={220} />
      {area.links.length > 0 && <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>{t('Linked to')}: {area.links.join(', ')}</Typography>}
      {can('OPERATOR') && (
        <Box component="form" sx={{ mt: 3 }} onSubmit={(e) => { e.preventDefault(); save.mutate({ area: area.id, count, source: 'STAFF' }); }}>
          <Typography variant="subtitle2" sx={{ mb: 1 }}>{t('Set the count')}</Typography>
          <Stack direction="row" spacing={1}>
            <TextField size="small" type="number" label={t('People counted now')} value={count} onChange={(e) => setCount(e.target.value)} required slotProps={{ htmlInput: { min: 0 } }} sx={{ flex: 1 }} />
            <Button type="submit" variant="contained" disabled={save.isPending}>{t('Save count')}</Button>
          </Stack>
        </Box>
      )}
    </Box>
  );
}
