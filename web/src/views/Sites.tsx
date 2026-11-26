import { Alert, Box, Button, Card, CardContent, Skeleton, Stack, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { useSites } from '../api/queries';
import { StateChip, fmt } from '../components/ui';
import { LEVEL_COLOR } from '../theme';

/** Every site of a head office (crowdwatch-data/sites.txt), each asked by this server. */
export function Sites() {
  const { t } = useTranslation();
  const { data: sites, isLoading } = useSites(true);
  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Sites')}</Typography>
      {isLoading && <Skeleton variant="rounded" height={200} />}
      {sites?.length === 0 && <Alert severity="info">{t('No sites yet. Add them on the server:')} <code>java -jar CrowdWatch.jar --site add NAME http://address:8080 CONTROL-KEY</code></Alert>}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))' }}>
        {(sites ?? []).map((x) => {
          const o = x.overview;
          if (!o) return (
            <Card key={x.name}><CardContent>
              <Stack direction="row" sx={{ alignItems: 'center', mb: 1 }}><Typography sx={{ fontWeight: 700, flex: 1 }}>{x.name}</Typography><StateChip label={t('NOT ANSWERING')} color="#dc2626" /></Stack>
              <Typography variant="body2" color="text.secondary">{x.url} · {x.problem}</Typography>
            </CardContent></Card>
          );
          const worst = o.levels.CRITICAL ? 'CRITICAL' : o.levels.HIGH ? 'HIGH' : o.levels.MODERATE ? 'MODERATE' : 'LOW';
          return (
            <Card key={x.name}><CardContent>
              <Stack direction="row" sx={{ alignItems: 'center' }}><Typography sx={{ fontWeight: 700, flex: 1 }}>{x.name}</Typography><StateChip label={worst} color={LEVEL_COLOR[worst]} /></Stack>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{o.venue}</Typography>
              <Stack direction="row" spacing={3} sx={{ mb: 1 }}>
                <Box><Typography variant="h5" sx={{ fontWeight: 700 }}>{fmt(o.people)}</Typography><Typography variant="caption" color="text.secondary">{t('People')}</Typography></Box>
                <Box><Typography variant="h5" sx={{ fontWeight: 700 }}>{o.levels.HIGH + o.levels.CRITICAL}</Typography><Typography variant="caption" color="text.secondary">{t('Areas HIGH or CRITICAL')}</Typography></Box>
                <Box><Typography variant="h5" sx={{ fontWeight: 700 }}>{o.incidents.open}</Typography><Typography variant="caption" color="text.secondary">{t('Open incidents')}</Typography></Box>
              </Stack>
              <Stack direction="row" spacing={1} sx={{ mb: 1 }}>
                {o.evacuation && <StateChip label={`EVACUATING ${o.evacuation}`} color="#ea580c" />}
                {o.drill && <StateChip label="DRILL" color="#7c3aed" />}
              </Stack>
              <Typography variant="body2" color={o.overdue ? 'warning.main' : 'text.secondary'} sx={{ mb: 1 }}>{t('Officer')}: {o.officer ?? '-'}{o.overdue ? ` · ${t('check-in OVERDUE')}` : ''}</Typography>
              <Button size="small" variant="outlined" href={x.url} target="_blank" rel="noopener">{t('Open this site')}</Button>
            </CardContent></Card>
          );
        })}
      </Box>
    </Stack>
  );
}
