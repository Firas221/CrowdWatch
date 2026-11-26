import { useState } from 'react';
import { Box, Button, Stack, TextField, Typography } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { useImage, useMessages } from '../api/queries';
import type { Message } from '../api/types';
import { useAction } from './ui';

function Photo({ id }: { id: number }) {
  const url = useImage('/api/photo?id=' + id);
  const { t } = useTranslation();
  return url ? <Box component="img" src={url} alt={t('Photo from the unit')} sx={{ display: 'block', maxWidth: '100%', maxHeight: 240, borderRadius: 1, mt: 0.5 }} />
    : <Typography variant="caption" color="text.secondary">{t('loading photo...')}</Typography>;
}

/** Texts and photos between a unit and the control room, newest last, with a box to send one. */
export function Messages({ unit, incident }: { unit: string | null; incident?: number }) {
  const { t } = useTranslation();
  const { data } = useMessages({ unit, incident: incident ?? null }, unit != null || incident != null);
  const [text, setText] = useState('');
  const send = useAction('/api/message', () => setText(''));
  const list: Message[] = Array.isArray(data) ? [...data].sort((a, b) => a.time.localeCompare(b.time)) : [];
  return (
    <Stack spacing={1}>
      <Typography variant="subtitle2">{t('Messages')}</Typography>
      {list.length === 0 && <Typography variant="body2" color="text.secondary">{t('No messages yet.')}</Typography>}
      {list.map((m) => (
        <Box key={m.id} sx={{ alignSelf: m.fromUnit ? 'flex-start' : 'flex-end', maxWidth: '85%', p: 1, borderRadius: 2, bgcolor: m.fromUnit ? 'action.hover' : 'primary.main', color: m.fromUnit ? undefined : 'primary.contrastText' }}>
          <Typography variant="caption" sx={{ opacity: 0.8 }}>{m.time.substring(11, 16)} · {m.fromUnit ? m.unit : m.by}</Typography>
          <Typography variant="body2">{m.text}</Typography>
          {m.photo && <Photo id={m.id} />}
        </Box>
      ))}
      {unit && (
        <Stack component="form" direction="row" spacing={1} onSubmit={(e) => { e.preventDefault(); send.mutate({ unit, incident: incident ?? null, text }); }}>
          <TextField size="small" label={t('Message to {{unit}}', { unit })} value={text} onChange={(e) => setText(e.target.value)} required sx={{ flex: 1 }} slotProps={{ htmlInput: { maxLength: 300 } }} />
          <Button type="submit" variant="outlined" disabled={send.isPending}>{t('Send')}</Button>
        </Stack>
      )}
    </Stack>
  );
}
