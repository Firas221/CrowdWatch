import { useState } from 'react';
import { Alert, Box, Button, Divider, IconButton, MenuItem, Skeleton, Stack, TextField, Typography } from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import { useTranslation } from 'react-i18next';
import { can } from '../api/client';
import { useIncident, useUnits } from '../api/queries';
import { FoundDialog } from './Dialogs';
import { Messages } from './Messages';
import { StateChip, useAction } from './ui';
import { PRIORITY_COLOR, STATUS_COLOR } from '../theme';

/** One incident: what, where, who is on it, the unit's route, the history, and what the control room can do. */
export function IncidentPanel({ id, onClose }: { id: number; onClose: () => void }) {
  const { t } = useTranslation();
  const { data: i, isLoading } = useIncident(id);
  const { data: units } = useUnits();
  const [note, setNote] = useState('');
  const [unit, setUnit] = useState('');
  const [reason, setReason] = useState('');
  const [found, setFound] = useState(false);
  const onscene = useAction('/api/onscene');
  const escalate = useAction('/api/escalate');
  const review = useAction('/api/review');
  const reassign = useAction('/api/reassign', () => { setUnit(''); setReason(''); });
  const resolve = useAction('/api/resolve', () => onClose());

  return (
    <Box sx={{ width: { xs: '100vw', sm: 480 }, p: 3 }} role="dialog" aria-label={'I' + id}>
      <Stack direction="row" sx={{ alignItems: 'flex-start', mb: 2 }}>
        <Box sx={{ flex: 1 }}>
          <Typography variant="h6" sx={{ fontWeight: 700 }}>I{id}{i?.ok ? ' · ' + i.label : ''}</Typography>
          {i?.ok && <Typography variant="body2" color="text.secondary">{i.areaName} ({i.area}) · {i.reported}</Typography>}
        </Box>
        <IconButton onClick={onClose} aria-label="close"><CloseIcon /></IconButton>
      </Stack>
      {isLoading && <Skeleton variant="rounded" height={300} />}
      {i && !i.ok && <Alert severity="info">{t('This incident is no longer in memory (it may be archived).')}</Alert>}
      {i?.ok && (
        <Stack spacing={2}>
          <Stack direction="row" spacing={1} sx={{ flexWrap: 'wrap', gap: 1 }}>
            <StateChip label={i.priority} color={PRIORITY_COLOR[i.priority]} />
            <StateChip label={i.status} color={STATUS_COLOR[i.status]} />
            {i.automatic && <StateChip label="automatic" color="#0891b2" />}
            {i.lost && <StateChip label="lost person" color="#7c3aed" />}
          </Stack>
          <Box sx={{ display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 2, rowGap: 0.5 }}>
            <Typography variant="body2" color="text.secondary">{t('Unit')}</Typography><Typography variant="body2">{i.unit ?? '-'}</Typography>
            <Typography variant="body2" color="text.secondary">{t('Device')}</Typography><Typography variant="body2">{i.device}</Typography>
            <Typography variant="body2" color="text.secondary">{t('Route of the unit')}</Typography><Typography variant="body2">{i.route}</Typography>
            {i.details && <><Typography variant="body2" color="text.secondary">{t('Details')}</Typography><Typography variant="body2">{i.details}</Typography></>}
            {i.contact && <><Typography variant="body2" color="text.secondary">{t('Contact')}</Typography><Typography variant="body2">{i.contact}{i.language ? ` (${i.language})` : ''}</Typography></>}
            {i.lost && <><Typography variant="body2" color="text.secondary">{t('Lost person')}</Typography>
              <Typography variant="body2">{i.foundWhere ? <b>{t('FOUND at {{where}} by {{by}}', { where: i.foundWhere, by: i.foundBy })}</b> : <><b>{t('MISSING')}</b> - {t('search')} {i.search.join(', ')}</>}</Typography>
              <Typography variant="body2" color="text.secondary">{t('Meeting point')}</Typography><Typography variant="body2">{i.meeting ?? '-'}</Typography></>}
            {i.automatic && <><Typography variant="body2" color="text.secondary">{t('Review')}</Typography><Typography variant="body2">{i.reviewedBy ? t('reviewed by {{name}}', { name: i.reviewedBy }) : t('awaiting an officer')}</Typography></>}
          </Box>

          {i.open && can('OFFICER') && i.automatic && !i.reviewedBy && (
            <Alert severity="warning" action={
              <Stack direction="row" spacing={1}>
                <Button color="inherit" size="small" disabled={review.isPending} onClick={() => review.mutate({ id, decision: true, note: 'approved in the web console' })}>{t('Approve dispatch')}</Button>
                <Button color="inherit" size="small" disabled={review.isPending} onClick={() => review.mutate({ id, decision: false, note: 'rejected in the web console' })}>{t('Reject dispatch')}</Button>
              </Stack>}>
              {t('Needs review')}
            </Alert>
          )}

          {i.open && can('OPERATOR') && (
            <>
              <Stack direction="row" spacing={1}>
                {i.status === 'ASSIGNED' && <Button variant="outlined" disabled={onscene.isPending} onClick={() => onscene.mutate({ id })}>{t('Mark on scene')}</Button>}
                <Button variant="outlined" color="warning" disabled={escalate.isPending} onClick={() => escalate.mutate({ id })}>{t('Escalate')}</Button>
                {i.lost && !i.foundWhere && <Button variant="contained" color="success" onClick={() => setFound(true)}>{t('Person found...')}</Button>}
              </Stack>
              <Stack component="form" direction="row" spacing={1} onSubmit={(e) => { e.preventDefault(); reassign.mutate({ id, unit, reason: reason || 'reassigned in the web console' }); }}>
                <TextField select size="small" label={t('Reassign to')} value={unit} onChange={(e) => setUnit(e.target.value)} required sx={{ flex: 1 }}>
                  {(units ?? []).filter((u) => u.id !== i.unit && u.status !== 'OFF_DUTY').map((u) => (
                    <MenuItem key={u.id} value={u.id}>{u.id} · {u.name} · {t(u.status)} · {u.area}</MenuItem>
                  ))}
                </TextField>
                <TextField size="small" label={t('Reason')} value={reason} onChange={(e) => setReason(e.target.value)} sx={{ flex: 1 }} />
                <Button type="submit" variant="outlined" disabled={reassign.isPending}>{t('Reassign')}</Button>
              </Stack>
              <Stack component="form" direction="row" spacing={1} onSubmit={(e) => { e.preventDefault(); resolve.mutate({ id, note }); }}>
                <TextField size="small" label={t('Resolution note')} value={note} onChange={(e) => setNote(e.target.value)} sx={{ flex: 1 }} />
                <Button type="submit" variant="contained" color="success" disabled={resolve.isPending}>{t('Resolve')}</Button>
              </Stack>
            </>
          )}

          <Divider />
          <Messages unit={i.unit} incident={id} />
          <Divider />
          <Typography variant="subtitle2">{t('History')}</Typography>
          <Stack spacing={1}>
            {i.history.slice().reverse().map((h, k) => (
              <Box key={k} sx={{ borderInlineStart: 3, borderColor: k === 0 ? 'primary.main' : 'divider', ps: 1.5 }}>
                <Typography variant="caption" color="text.secondary">{h.t}</Typography>
                <Typography variant="body2">{h.text}</Typography>
              </Box>
            ))}
          </Stack>
        </Stack>
      )}
      <FoundDialog id={found ? id : null} onClose={() => setFound(false)} />
    </Box>
  );
}
