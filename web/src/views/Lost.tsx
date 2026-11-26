import { useState, type FormEvent } from 'react';
import { Alert, Box, Button, Card, CardContent, Chip, Stack, TextField, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { can } from '../api/client';
import { useLost, useVenue } from '../api/queries';
import { areaItems, FoundDialog } from '../components/Dialogs';
import { StateChip, useAction } from '../components/ui';

const VIOLET = '#7c3aed';

export function Lost({ onIncident }: { onIncident: (id: number) => void }) {
  const { t } = useTranslation();
  const { data: venue } = useVenue();
  const { data: lost } = useLost();
  const [found, setFound] = useState<number | null>(null);
  const [f, setF] = useState({ area: '', contact: '', language: '', details: '' });
  const [fp, setFp] = useState({ area: '', details: '' });
  const category = venue?.categories.find((c) => (c.skill ?? '').toLowerCase() === 'lost-person')?.code;
  const report = useAction('/api/incidents', () => setF({ area: '', contact: '', language: '', details: '' }));
  const foundPerson = useAction('/api/foundperson', () => setFp({ area: '', details: '' }));
  const widen = venue?.rules.lostWiden ?? 10;

  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Lost persons')}</Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '2fr 1fr' } }}>
        <Card><CardContent>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Report a lost person')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            {t('A lookout with the description goes to every unit at once, units at exits check people leaving, the search area widens every {{n}} min, and the case is escalated at 15 and 30 min. Children and vulnerable adults are handled as CRITICAL.', { n: widen })}
          </Typography>
          {!category ? <Alert severity="info">{t('This venue has no lost-person category.')}</Alert> : can('OPERATOR') && (
            <Stack component="form" spacing={2} onSubmit={(e: FormEvent) => { e.preventDefault(); report.mutate({ category, area: f.area, details: f.details, contact: f.contact || null, language: f.language || null }); }}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                <TextField select label={t('Last seen at')} value={f.area} onChange={(e) => setF({ ...f, area: e.target.value })} required sx={{ flex: 1 }}>{areaItems(venue?.areas)}</TextField>
                <TextField label={t('Reporter contact')} placeholder={t('phone or where they wait')} value={f.contact} onChange={(e) => setF({ ...f, contact: e.target.value })} sx={{ flex: 1 }} />
                <TextField label={t('Language (optional)')} value={f.language} onChange={(e) => setF({ ...f, language: e.target.value })} sx={{ flex: 1 }} />
              </Stack>
              <TextField label={t('Description')} placeholder={t('Age, clothes, hair, language, who they were with')} value={f.details} onChange={(e) => setF({ ...f, details: e.target.value })} multiline minRows={2} required slotProps={{ htmlInput: { maxLength: 400 } }} />
              <Box><Button type="submit" variant="contained" disabled={report.isPending}>{t('Send lookout')}</Button></Box>
            </Stack>
          )}
        </CardContent></Card>
        <Card><CardContent>
          <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Found someone?')}</Typography>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>{t('For a person found alone. They are recorded as found, a unit looks after them, and open cases are listed to compare.')}</Typography>
          {can('OPERATOR') && (
            <Stack component="form" spacing={2} onSubmit={(e: FormEvent) => { e.preventDefault(); foundPerson.mutate({ area: fp.area, details: fp.details }); }}>
              <TextField select label={t('Where')} value={fp.area} onChange={(e) => setFp({ ...fp, area: e.target.value })} required>{areaItems(venue?.areas)}</TextField>
              <TextField label={t('Description')} placeholder={t('Age, clothes, what they say')} value={fp.details} onChange={(e) => setFp({ ...fp, details: e.target.value })} multiline minRows={2} required slotProps={{ htmlInput: { maxLength: 380 } }} />
              <Box><Button type="submit" variant="outlined" disabled={foundPerson.isPending}>{t('Record found person')}</Button></Box>
            </Stack>
          )}
        </CardContent></Card>
      </Box>
      {lost?.length === 0 && <Card><CardContent><Typography color="text.secondary">{t('No open lost-person cases.')}</Typography></CardContent></Card>}
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))' }}>
        {(lost ?? []).map((l) => (
          <Card key={l.id} sx={{ borderTop: 3, borderTopColor: l.found ? 'success.main' : VIOLET }}>
            <CardContent>
              <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', mb: 1 }}>
                <Box sx={{ flex: 1 }}>
                  <Typography sx={{ fontWeight: 700 }}>I{l.id} · {l.details}</Typography>
                  <Typography variant="caption" color="text.secondary">{t('last seen')} {l.area} ({l.areaName}) · {l.reported}</Typography>
                </Box>
                <StateChip label={l.found ? t('FOUND') : `${t('MISSING')} ${l.minutes} ${t('min')}`} color={l.found ? '#16a34a' : '#dc2626'} />
              </Stack>
              {l.found ? (
                <Alert severity="success" sx={{ mb: 1 }}>{t('Found at {{where}} by {{by}}. Bring to {{meeting}}, check identity with the reporter, then resolve.', { where: l.foundWhere, by: l.foundBy, meeting: l.meeting })}</Alert>
              ) : (
                <Box sx={{ mb: 1 }}>
                  <Typography variant="caption" color="text.secondary">{t('Search area ({{r}} min walk, widens every {{n}} min)', { r: l.radius, n: widen })}</Typography>
                  <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 0.5, mt: 0.5 }}>{l.search.map((s) => <Chip key={s} size="small" label={s} sx={{ bgcolor: VIOLET + '22', color: VIOLET }} />)}</Box>
                </Box>
              )}
              <Box sx={{ display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 2, rowGap: 0.25, mb: 1 }}>
                <Typography variant="body2" color="text.secondary">{t('Meeting point')}</Typography><Typography variant="body2">{l.meeting ?? '-'}</Typography>
                <Typography variant="body2" color="text.secondary">{t('On the case')}</Typography><Typography variant="body2">{l.unit ?? t('waiting for a unit')}</Typography>
                {l.contact && <><Typography variant="body2" color="text.secondary">{t('Reporter')}</Typography><Typography variant="body2">{l.contact}{l.language ? ` (${l.language})` : ''}</Typography></>}
              </Box>
              <Stack direction="row" spacing={1}>
                {!l.found && can('OPERATOR') && <Button variant="contained" color="success" size="small" onClick={() => setFound(l.id)}>{t('Person found...')}</Button>}
                <Button size="small" variant="outlined" onClick={() => onIncident(l.id)}>{t('Open case')}</Button>
              </Stack>
            </CardContent>
          </Card>
        ))}
      </Box>
      <FoundDialog id={found} onClose={() => setFound(null)} />
    </Stack>
  );
}
