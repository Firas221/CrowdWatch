import { useMemo, useState, type FormEvent } from 'react';
import { Box, Button, Card, CardContent, Stack, Table, TableBody, TableCell, TableContainer, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { download, get, openPage } from '../api/client';
import type { ReportData } from '../api/types';
import { Chart, Stat, fmt, useToast } from '../components/ui';

const day = (d: Date) => d.toISOString().substring(0, 10);

/** Busiest times, incidents, how quickly units acknowledged and arrived, missed check-ins - over a period. */
export function Reports() {
  const { t } = useTranslation();
  const toast = useToast();
  const [from, setFrom] = useState(day(new Date(Date.now() - 6 * 86_400_000)));
  const [to, setTo] = useState(day(new Date()));
  const url = (format: string) => `/api/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&format=${format}`;
  const load = useMutation({ mutationFn: () => get<ReportData & { message?: string }>(url('json')), onError: (e) => toast(e.message, false) });
  const r = load.data && Array.isArray(load.data.areas) ? load.data : null;
  const minutes = (v: number) => (v < 0 ? '-' : String(v));
  const hourly = useMemo(() => r && ({
    grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => fmt(v) },
    xAxis: { type: 'category', data: r.hourly.map((_, h) => String(h).padStart(2, '0')) },
    yAxis: { type: 'value', splitLine: { lineStyle: { opacity: 0.3 } } },
    series: [{ type: 'bar', data: r.hourly, itemStyle: { color: '#2563eb', borderRadius: 3 } }],
  }), [r]);
  const daily = useMemo(() => r && ({
    grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: r.days.map((d) => d.day) },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { opacity: 0.3 } } },
    series: [{ type: 'bar', data: r.days.map((d) => d.incidents), itemStyle: { color: '#ea580c', borderRadius: 3 } }],
  }), [r]);

  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Reports')}</Typography>
      <Card><CardContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('Busiest times, incidents, how quickly units acknowledged and arrived, missed check-ins')}</Typography>
        <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1} onSubmit={(e: FormEvent) => { e.preventDefault(); load.mutate(); }}>
          <TextField size="small" type="date" label={t('From')} value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="date" label={t('To')} value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <Button type="submit" variant="contained" disabled={load.isPending}>{t('Show')}</Button>
          <Button variant="outlined" onClick={() => download(url('csv'), `crowdwatch-report-${from}-to-${to}.csv`).catch((e: Error) => toast(e.message, false))}>{t('Download for Excel (CSV)')}</Button>
          <Button variant="outlined" onClick={() => openPage(url('html')).catch((e: Error) => toast(e.message, false))}>{t('Print / save as PDF')}</Button>
        </Stack>
      </CardContent></Card>
      {!r ? <Card><CardContent><Typography color="text.secondary">{t('Choose the period and press Show.')}</Typography></CardContent></Card> : (
        <>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', md: 'repeat(5, 1fr)' } }}>
            <Stat label={t('incidents')} value={fmt(r.incidents)} note={`${r.automatic} ${t('automatic')} · ${r.sos} SOS`} />
            <Stat label={t('median minutes to acknowledge')} value={minutes(r.ackMedian)} note={`90%: ${minutes(r.ackP90)} ${t('min')}`} />
            <Stat label={t('median minutes to on scene')} value={minutes(r.sceneMedian)} note={`90%: ${minutes(r.sceneP90)} ${t('min')}`} />
            <Stat label={t('check-in overdue')} value={r.overdue} />
            <Stat label={t('unit did not answer')} value={r.noAnswer} />
          </Box>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '1fr 1fr' } }}>
            <Card><CardContent><Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('People on site by hour')}</Typography>{hourly && <Chart option={hourly} height={260} />}</CardContent></Card>
            <Card><CardContent><Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Incidents per day')}</Typography>{daily && <Chart option={daily} height={260} />}</CardContent></Card>
          </Box>
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '3fr 2fr' } }}>
            <TableContainer component={Card}>
              <Typography variant="subtitle1" sx={{ fontWeight: 700, p: 2, pb: 0 }}>{t('Busiest areas')}</Typography>
              <Table size="small">
                <TableHead><TableRow><TableCell>{t('Area')}</TableCell><TableCell>{t('Peak')}</TableCell><TableCell>{t('When')}</TableCell><TableCell>{t('Busiest hour')}</TableCell></TableRow></TableHead>
                <TableBody>
                  {r.areas.map((a) => (
                    <TableRow key={a.id}><TableCell><b>{a.id}</b><Typography variant="caption" color="text.secondary" component="div">{a.name}</Typography></TableCell>
                      <TableCell>{fmt(a.peak)} ({a.peakPct}%)</TableCell><TableCell>{a.peakAt}</TableCell><TableCell>{a.busiestHour} ({a.busiestPct}%)</TableCell></TableRow>
                  ))}
                  {r.areas.length === 0 && <TableRow><TableCell colSpan={4}>{t('No counts recorded in this period.')}</TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
            <TableContainer component={Card}>
              <Typography variant="subtitle1" sx={{ fontWeight: 700, p: 2, pb: 0 }}>{t('By category')}</Typography>
              <Table size="small">
                <TableHead><TableRow><TableCell>{t('Category')}</TableCell><TableCell align="right">{t('Incidents')}</TableCell><TableCell align="right">{t('Median to on scene')}</TableCell></TableRow></TableHead>
                <TableBody>
                  {r.categories.map((c) => <TableRow key={c.category}><TableCell>{c.category}</TableCell><TableCell align="right">{c.incidents}</TableCell><TableCell align="right">{c.sceneMedian < 0 ? '-' : `${c.sceneMedian} ${t('min')}`}</TableCell></TableRow>)}
                  {r.categories.length === 0 && <TableRow><TableCell colSpan={3}>-</TableCell></TableRow>}
                </TableBody>
              </Table>
            </TableContainer>
          </Box>
        </>
      )}
    </Stack>
  );
}
