import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Box, Button, Card, CardContent, Slider, Stack, TextField, Typography } from '@mui/material';
import PlayIcon from '@mui/icons-material/PlayArrow';
import PauseIcon from '@mui/icons-material/Pause';
import { useMutation } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { get } from '../api/client';
import { useLayout } from '../api/queries';
import type { Level, Replay as ReplayData } from '../api/types';
import { LevelLegend, VenueMap } from '../components/VenueMap';
import { StateChip, fmt, useToast } from '../components/ui';
import { LEVEL_COLOR } from '../theme';

const local = (d: Date) => new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().substring(0, 16);

/** How the crowd moved, minute by minute, with the alerts of the time (up to 24 hours at a time). */
export function Replay() {
  const { t } = useTranslation();
  const toast = useToast();
  const { data: layout } = useLayout();
  const [from, setFrom] = useState(local(new Date(Date.now() - 3_600_000)));
  const [to, setTo] = useState(local(new Date()));
  const [idx, setIdx] = useState(0);
  const [playing, setPlaying] = useState(false);
  const load = useMutation({
    mutationFn: () => get<ReplayData & { message?: string }>(`/api/replay?from=${encodeURIComponent(from + ':00')}&to=${encodeURIComponent(to + ':00')}`),
    onSuccess: (r) => { if (!Array.isArray(r.frames)) toast(r.message ?? 'Refused.', false); setIdx(0); setPlaying(false); },
    onError: (e) => toast(e.message, false),
  });
  const rp = load.data && Array.isArray(load.data.frames) ? load.data : null;
  const frames = rp?.frames ?? [];
  const f = frames[Math.min(idx, frames.length - 1)];

  useEffect(() => {
    if (!playing) return;
    const timer = window.setInterval(() => setIdx((i) => { if (i >= frames.length - 1) { setPlaying(false); return i; } return i + 1; }), 400);
    return () => window.clearInterval(timer);
  }, [playing, frames.length]);

  const sum = useMemo(() => {
    const lv: Record<Level, number> = { LOW: 0, MODERATE: 0, HIGH: 0, CRITICAL: 0 };
    let people = 0;
    if (f) for (const [n, l] of Object.values(f.areas)) { people += n; lv[l]++; }
    return { people, lv };
  }, [f]);
  const alerts = useMemo(() => (rp && f ? rp.alerts.filter((a) => a.time <= f.t + ':59').slice(-8).reverse() : []), [rp, f]);

  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Replay')}</Typography>
      <Card><CardContent>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('How the crowd moved, minute by minute, with the alerts of the time - for reviews after an event (up to 24 hours at a time)')}</Typography>
        <Stack component="form" direction={{ xs: 'column', sm: 'row' }} spacing={1} onSubmit={(e: FormEvent) => { e.preventDefault(); load.mutate(); }}>
          <TextField size="small" type="datetime-local" label={t('From')} value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField size="small" type="datetime-local" label={t('To')} value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} />
          <Button type="submit" variant="contained" disabled={load.isPending}>{t('Load')}</Button>
        </Stack>
      </CardContent></Card>
      {!rp ? <Card><CardContent><Typography color="text.secondary">{t('Choose a period and press Load.')}</Typography></CardContent></Card>
        : frames.length === 0 ? <Card><CardContent><Typography color="text.secondary">{t('No counts were recorded in this period.')}</Typography></CardContent></Card> : (
          <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '3fr 1fr' } }}>
            <Card><CardContent>
              <Stack direction="row" spacing={2} sx={{ alignItems: 'center', mb: 1, flexWrap: 'wrap' }}>
                <Box sx={{ flex: 1 }}>
                  <Typography variant="h6" sx={{ fontWeight: 700 }}>{f.t.replace('T', ' ')}</Typography>
                  <Typography variant="body2" color="text.secondary">{fmt(sum.people)} {t('people on site')} · {sum.lv.HIGH} HIGH · {sum.lv.CRITICAL} CRITICAL</Typography>
                </Box>
                <Button variant="outlined" startIcon={playing ? <PauseIcon /> : <PlayIcon />} onClick={() => { if (idx >= frames.length - 1) setIdx(0); setPlaying(!playing); }}>{t(playing ? 'Pause' : 'Play')}</Button>
              </Stack>
              <Slider value={idx} min={0} max={frames.length - 1} onChange={(_, v) => { setPlaying(false); setIdx(v as number); }} aria-label={t('Minute')} />
              {layout?.map ? <><VenueMap frame={f.areas} /><Box sx={{ mt: 1 }}><LevelLegend t={t} /></Box></> : (
                <Box sx={{ display: 'grid', gap: 1, gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))' }}>
                  {Object.entries(f.areas).map(([id, [n, l]]) => (
                    <Box key={id} sx={{ p: 1, borderRadius: 1, border: 1, borderColor: LEVEL_COLOR[l] + '88', bgcolor: LEVEL_COLOR[l] + '1a' }}>
                      <Typography variant="body2" sx={{ fontWeight: 700 }}>{id}</Typography>
                      <Typography variant="body2">{fmt(n)} · <StateChip label={l} color={LEVEL_COLOR[l]} /></Typography>
                    </Box>
                  ))}
                </Box>
              )}
            </CardContent></Card>
            <Card><CardContent>
              <Typography variant="subtitle1" sx={{ fontWeight: 700, mb: 1 }}>{t('Alerts until then')}</Typography>
              {alerts.length === 0 && <Typography variant="body2" color="text.secondary">{t('No alerts yet.')}</Typography>}
              <Stack spacing={1}>
                {alerts.map((a, k) => <Box key={k}><Typography variant="caption" color="text.secondary">{a.time.substring(11, 16)} · {a.kind}</Typography><Typography variant="body2">{a.text}</Typography></Box>)}
              </Stack>
            </CardContent></Card>
          </Box>
        )}
    </Stack>
  );
}
