import { useMemo } from 'react';
import { Box, Button, Card, Chip, CardContent, CardHeader, List, ListItemButton, ListItemText, Skeleton, Stack, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { can } from '../api/client';
import { useAlerts, useAreas, useIncidents, useLayout, useOverview } from '../api/queries';
import type { Level } from '../api/types';
import { Chart, Stat, StateChip, fmt, useAction } from '../components/ui';
import { LevelLegend, VenueMap } from '../components/VenueMap';
import { LEVEL_COLOR, PRIORITY_COLOR } from '../theme';
import type { View } from '../components/Shell';

const LEVELS: Level[] = ['LOW', 'MODERATE', 'HIGH', 'CRITICAL'];

export function Overview({ onView, onIncident }: { onView: (v: View) => void; onIncident: (id: number) => void }) {
  const { t } = useTranslation();
  const { data: o } = useOverview();
  const { data: areas } = useAreas();
  const { data: incidents } = useIncidents();
  const { data: alerts } = useAlerts();
  const automation = useAction('/api/automation');
  const { data: layout } = useLayout();

  const busiest = useMemo(() => {
    const top = (areas ?? []).slice(0, 10).reverse();
    return {
      grid: { left: 8, right: 40, top: 8, bottom: 8, containLabel: true },
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: (v: number) => v + '%' },
      xAxis: { type: 'value', max: (m: { max: number }) => Math.max(100, m.max), axisLabel: { formatter: '{value}%' }, splitLine: { lineStyle: { opacity: 0.3 } } },
      yAxis: { type: 'category', data: top.map((a) => a.name) },
      series: [{
        type: 'bar', barMaxWidth: 18, label: { show: true, position: 'right', formatter: '{c}%' },
        data: top.map((a) => ({ value: a.percent, itemStyle: { color: LEVEL_COLOR[a.level], borderRadius: 4 } })),
      }],
    };
  }, [areas]);

  const byLevel = useMemo(() => ({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie', radius: ['50%', '75%'], center: ['50%', '45%'], label: { show: false },
      data: LEVELS.map((l) => ({ name: t(l), value: o?.levels[l] ?? 0, itemStyle: { color: LEVEL_COLOR[l] } })),
    }],
  }), [o, t]);

  if (!o) return <Skeleton variant="rounded" height={400} />;
  const pct = o.capacity > 0 ? Math.round((o.people * 100) / o.capacity) : 0;
  const hot = o.levels.HIGH + o.levels.CRITICAL;

  return (
    <Stack spacing={3}>
      <Stack direction="row" sx={{ alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 1 }}>
        <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Overview')}</Typography>
        <Stack direction="row" spacing={1} sx={{ alignItems: 'center' }}>
          <StateChip label={o.automation ? 'Automatic dispatch ON' : 'Automatic dispatch OFF'} color={o.automation ? '#16a34a' : '#64748b'} />
          {can('OFFICER') && (
            <Button size="small" variant="outlined" disabled={automation.isPending} onClick={() => automation.mutate({ on: o.automation ? 0 : 1 })}>
              {t(o.automation ? 'Switch off' : 'Switch on')}
            </Button>
          )}
        </Stack>
      </Stack>

      <StatusChips onView={onView} />

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(5, 1fr)' } }}>
        <Stat label={t('People in the venue')} value={fmt(o.people)} note={`${pct}% ${t('of capacity')} (${fmt(o.capacity)})`} onClick={() => onView('areas')} />
        <Stat label={t('Areas HIGH or CRITICAL')} value={hot} color={hot > 0 ? LEVEL_COLOR[o.levels.CRITICAL > 0 ? 'CRITICAL' : 'HIGH'] : undefined}
          note={t('of {{n}} areas', { n: o.areas })} onClick={() => onView('areas')} />
        <Stat label={t('Open incidents')} value={o.incidents.open} color={o.incidents.CRITICAL > 0 ? PRIORITY_COLOR.CRITICAL : undefined}
          note={t('{{n}} critical, {{w}} waiting', { n: o.incidents.CRITICAL, w: o.incidents.waiting })} onClick={() => onView('incidents')} />
        <Stat label={t('Units available')} value={`${o.units.AVAILABLE} / ${o.units.total}`} note={t('{{n}} devices online', { n: o.units.online })} onClick={() => onView('units')} />
        <Stat label={t('Awaiting review')} value={o.review} color={o.review > 0 ? '#d97706' : undefined} note={t('automatic dispatches')} onClick={() => onView('incidents')} />
      </Box>

      {layout?.map && (
        <Card>
          <CardHeader title={t('Venue map')} subheader={t('Live crowd levels; the number is the units there. Click an area for its details.')}
            slotProps={{ title: { variant: 'subtitle1', fontWeight: 700 } }} action={<Box sx={{ pt: 1, pe: 1 }}><LevelLegend t={t} /></Box>} />
          <CardContent sx={{ pt: 0 }}><VenueMap onArea={() => onView('areas')} /></CardContent>
        </Card>
      )}

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <Card><CardHeader title={t('Busiest areas')} slotProps={{ title: { variant: 'subtitle1', fontWeight: 700 } }} />
          <CardContent sx={{ pt: 0 }}><Chart option={busiest} height={320} /></CardContent></Card>
        <Card><CardHeader title={t('Areas by crowd level')} slotProps={{ title: { variant: 'subtitle1', fontWeight: 700 } }} />
          <CardContent sx={{ pt: 0 }}><Chart option={byLevel} height={320} /></CardContent></Card>
      </Box>

      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
        <Card>
          <CardHeader title={t('Most urgent incidents')} slotProps={{ title: { variant: 'subtitle1', fontWeight: 700 } }} />
          <List dense sx={{ pt: 0 }}>
            {(incidents ?? []).slice(0, 6).map((i) => (
              <ListItemButton key={i.id} onClick={() => onIncident(i.id)}>
                <ListItemText primary={`I${i.id} · ${i.label} · ${i.area}`} secondary={`${i.unit ?? '-'} · ${i.device} · ${i.minutesOpen} ${t('min')}`} />
                <StateChip label={i.priority} color={PRIORITY_COLOR[i.priority]} />
              </ListItemButton>
            ))}
            {incidents?.length === 0 && <Typography sx={{ px: 2, pb: 2 }} color="text.secondary">{t('No open incidents.')}</Typography>}
          </List>
        </Card>
        <Card>
          <CardHeader title={t('Latest alerts')} slotProps={{ title: { variant: 'subtitle1', fontWeight: 700 } }} />
          <List dense sx={{ pt: 0 }}>
            {(alerts ?? []).slice(0, 6).map((a, k) => (
              <ListItemButton key={a.time + k} onClick={() => onView('alerts')}>
                <ListItemText primary={a.text} secondary={`${a.time.substring(11, 19)} · ${a.kind}${a.ref ? ' · ' + a.ref : ''}`} />
              </ListItemButton>
            ))}
            {alerts?.length === 0 && <Typography sx={{ px: 2, pb: 2 }} color="text.secondary">{t('No alerts yet.')}</Typography>}
          </List>
        </Card>
      </Box>
    </Stack>
  );
}

/** What needs attention now, each leading to the view where it is dealt with. */
function StatusChips({ onView }: { onView: (v: View) => void }) {
  const { t } = useTranslation();
  const { data: o } = useOverview();
  const { data: areas } = useAreas();
  const { data: incidents } = useIncidents();
  if (!o) return null;
  const c: { color: string; text: string; view: View; blink?: boolean }[] = [];
  if (o.proposal) c.push({ color: '#dc2626', text: t('Evacuation proposed for {{zone}} - decide now', { zone: o.proposal.zone }), view: 'emergency', blink: true });
  if (o.heat && o.heat.band !== 'NONE') c.push({ color: o.heat.band.includes('DANGER') ? '#dc2626' : '#d97706', text: t('Heat index {{n}}°C', { n: o.heat.index }) + ' - ' + o.heat.band, view: 'settings' });
  if (o.lost) c.push({ color: '#7c3aed', text: t('{{n}} lost person(s) missing', { n: o.lost }), view: 'lost', blink: true });
  if (o.signs) c.push({ color: '#d97706', text: t('{{n}} public message(s) to approve', { n: o.signs }), view: 'emergency' });
  if (o.stale) c.push({ color: '#64748b', text: t('{{n}} area(s) with doubtful sensor data', { n: o.stale }), view: 'areas' });
  if (o.countCheck && !o.countCheck.ok) c.push({ color: '#dc2626', text: t('Counts do not add up: {{n}} - recount', { n: fmt(Math.abs(o.countCheck.difference)) }), view: 'settings', blink: true });
  for (const e of o.events ?? []) c.push({ color: '#7c3aed', text: t('Scheduled peak: {{e}}', { e }), view: 'settings' });
  if (o.incidents.waiting) c.push({ color: '#dc2626', text: t('{{n}} incident(s) waiting for a unit', { n: o.incidents.waiting }), view: 'incidents', blink: true });
  for (const i of (incidents ?? []).filter((x) => x.reminders > 0 && !x.acknowledged && x.unit).slice(0, 2))
    c.push({ color: '#dc2626', text: t('{{unit}} has not acknowledged I{{id}}', { unit: i.unit, id: i.id }), view: 'units', blink: true });
  for (const a of (areas ?? []).filter((x) => x.minutesToCritical > 0 && x.minutesToCritical <= 30).sort((x, y) => x.minutesToCritical - y.minutesToCritical).slice(0, 2))
    c.push({ color: '#7c3aed', text: t('{{id}} rising +{{rate}}/min - CRITICAL in ~{{n}} min', { id: a.id, rate: a.ratePerMinute, n: a.minutesToCritical }), view: 'areas' });
  if (c.length === 0) return null;
  return (
    <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1 }}>
      {c.map((x, k) => (
        <Chip key={k} clickable onClick={() => onView(x.view)} label={x.text}
          icon={<Box component="span" sx={{ width: 8, height: 8, borderRadius: '50%', bgcolor: x.color, ml: 1, animation: x.blink ? 'cwpulse 1.2s infinite' : undefined }} />}
          sx={{ bgcolor: x.color + '1f', color: 'text.primary', border: '1px solid ' + x.color + '66', fontWeight: 600 }} />
      ))}
    </Box>
  );
}
