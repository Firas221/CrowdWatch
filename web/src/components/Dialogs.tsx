import { useEffect, useState, type FormEvent } from 'react';
import { Button, Dialog, DialogActions, DialogContent, DialogTitle, ListSubheader, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { useLost, useVenue } from '../api/queries';
import { useAction } from './ui';

/** A list of the venue's areas for a select box. */
export function areaItems(areas: string[] | undefined) {
  return (areas ?? []).map((a) => <MenuItem key={a} value={a}>{a}</MenuItem>);
}

/** Sectors, fire zones and areas: what an evacuation can cover. */
export function zoneItems(v: { sectors: string[]; fireZones: string[]; areas: string[] } | undefined, t: (s: string) => string) {
  if (!v) return [];
  return [
    <ListSubheader key="s">{t('Sectors')}</ListSubheader>, ...v.sectors.map((s) => <MenuItem key={'s' + s} value={s}>{s}</MenuItem>),
    ...(v.fireZones.length ? [<ListSubheader key="f">{t('Fire zones')}</ListSubheader>, ...v.fireZones.map((s) => <MenuItem key={'f' + s} value={s}>{s}</MenuItem>)] : []),
    <ListSubheader key="a">{t('Areas')}</ListSubheader>, ...v.areas.map((s) => <MenuItem key={'a' + s} value={s}>{s}</MenuItem>),
  ];
}

/** SOS from the control room: a CRITICAL incident, the nearest free units sent at once. */
export function SosDialog({ open, onClose, onSent }: { open: boolean; onClose: () => void; onSent?: () => void }) {
  const { t } = useTranslation();
  const { data: venue } = useVenue();
  const [area, setArea] = useState('');
  const [text, setText] = useState('');
  const send = useAction('/api/sos', () => { setText(''); onClose(); onSent?.(); });
  return (
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={(e: FormEvent) => { e.preventDefault(); send.mutate({ area, text: text || 'SOS from the control room' }); }}>
        <DialogTitle sx={{ color: 'error.main', fontWeight: 800 }}>{t('SOS - someone needs help now')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">{t('Creates a CRITICAL incident and sends the nearest free units straight away.')}</Typography>
            <TextField select label={t('Where')} value={area} onChange={(e) => setArea(e.target.value)} required>{areaItems(venue?.areas)}</TextField>
            <TextField label={t('What is happening')} value={text} onChange={(e) => setText(e.target.value)} placeholder={t('e.g. person collapsed near gate A4')} slotProps={{ htmlInput: { maxLength: 300 } }} />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{t('Cancel')}</Button>
          <Button type="submit" variant="contained" color="error" disabled={send.isPending}>{t('Send SOS')}</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** A missing person has been found: where, so the lookout stops and they are brought to the meeting point. */
export function FoundDialog({ id, onClose }: { id: number | null; onClose: () => void }) {
  const { t } = useTranslation();
  const { data: venue } = useVenue();
  const { data: lost } = useLost();
  const l = lost?.find((x) => x.id === id);
  const [area, setArea] = useState('');
  const lastSeen = l?.area;
  useEffect(() => { if (lastSeen) setArea(lastSeen); }, [id, lastSeen]);
  const send = useAction('/api/found', () => onClose());
  return (
    <Dialog open={id != null} onClose={onClose} fullWidth maxWidth="xs">
      <form onSubmit={(e: FormEvent) => { e.preventDefault(); send.mutate({ id, area }); }}>
        <DialogTitle>I{id} {t('found')}</DialogTitle>
        <DialogContent>
          <Stack spacing={2} sx={{ pt: 1 }}>
            {l && <Typography variant="body2">{l.details}</Typography>}
            <TextField select label={t('Found at')} value={area} onChange={(e) => setArea(e.target.value)} required>{areaItems(venue?.areas)}</TextField>
            <Typography variant="body2" color="text.secondary">{t('The lookout stops for every unit and the person is brought to {{place}}.', { place: l?.meeting ?? t('the meeting point') })}</Typography>
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose}>{t('Cancel')}</Button>
          <Button type="submit" variant="contained" color="success" disabled={send.isPending}>{t('Mark found')}</Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}
