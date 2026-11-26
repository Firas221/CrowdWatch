import { useRef, useState, type FormEvent, type PointerEvent as ReactPointerEvent } from 'react';
import { Alert, Box, Button, Card, CardContent, Stack, TextField, Typography, useTheme } from '@mui/material';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { getText, saveBlob } from '../api/client';
import { useImage, useLayout, useVenue } from '../api/queries';
import { useAction, useToast } from '../components/ui';

type Rect = [number, number, number, number];
type NewArea = { id: string; name: string; sector: string; type: string; capacity: string; size: string };

/**
 * The venue file with the editor's changes: new pos: lines for moved areas (their old pos: and
 * shape: lines dropped) and area: lines for new ones; everything else stays as it is.
 */
export function editedVenueText(text: string, pos: Record<string, Rect>, added: NewArea[], now = new Date()): string {
  const changed = Object.keys(pos);
  const lines = text.split(/\r?\n/).filter((l) => {
    const m = /^\s*(pos|shape):\s*(\S+)/i.exec(l);
    return !(m && changed.includes(m[2].toUpperCase()));
  });
  let out = lines.join('\n').replace(/\s+$/, '') + '\n\n# set in the venue editor on ' + now.toISOString().substring(0, 16).replace('T', ' ') + '\n';
  for (const a of added) out += `area: ${a.id} | ${a.name} | ${a.sector} | ${a.type} | ${a.capacity}\n` + (a.size ? `size: ${a.id} ${a.size}\n` : '');
  for (const id of changed) out += `pos: ${id} ${pos[id].map((v) => Math.round(v)).join(' ')}\n`;
  return out;
}

export function Editor() {
  const { t } = useTranslation();
  const theme = useTheme();
  const toast = useToast();
  const { data: L } = useLayout();
  const { data: venue } = useVenue();
  const image = useImage(L?.map && L.image ? '/api/map' : null);
  const file = useQuery({ queryKey: ['venuefile'], queryFn: () => getText('/api/venuefile'), staleTime: Infinity });
  const [pos, setPos] = useState<Record<string, Rect>>({});
  const [added, setAdded] = useState<NewArea[]>([]);
  const [sel, setSel] = useState<string | null>(null);
  const [drag, setDrag] = useState<{ id: string; mode: 'move' | 'size'; p: Rect; x: number; y: number; now?: Rect } | null>(null);
  const [na, setNa] = useState<NewArea>({ id: '', name: '', sector: '', type: 'HALL', capacity: '', size: '' });
  const svg = useRef<SVGSVGElement>(null);
  const save = useAction('/api/venuefile', () => { setPos({}); setAdded([]); file.refetch(); });

  if (!L) return null;
  if (!L.map) return <Alert severity="info">{t('This venue has no drawing (map: line), so there is nothing to place areas on. Add a map: line to the venue file first.')}</Alert>;

  const at = (id: string): Rect => {
    if (drag?.id === id && drag.now) return drag.now;
    if (pos[id]) return pos[id];
    const p = L.areas.find((a) => a.id === id);
    return p ? [p.x, p.y, p.w, p.h] : [0, 0, 0, 0];
  };
  const point = (e: ReactPointerEvent) => {
    const s = svg.current!, m = s.getScreenCTM()!.inverse();
    const pt = new DOMPoint(e.clientX, e.clientY).matrixTransform(m);
    return { x: pt.x, y: pt.y };
  };
  const down = (id: string, mode: 'move' | 'size') => (e: ReactPointerEvent) => {
    e.preventDefault();
    (e.target as Element).setPointerCapture(e.pointerId);
    const p = point(e);
    setSel(id);
    setDrag({ id, mode, p: at(id), x: p.x, y: p.y });
  };
  const move = (e: ReactPointerEvent) => {
    if (!drag) return;
    const p = point(e), dx = p.x - drag.x, dy = p.y - drag.y, o = drag.p;
    setDrag({ ...drag, now: drag.mode === 'move' ? [o[0] + dx, o[1] + dy, o[2], o[3]] : [o[0], o[1], Math.max(20, o[2] + dx), Math.max(20, o[3] + dy)] });
  };
  const up = () => {
    if (drag?.now && Math.abs(drag.now[0] - drag.p[0]) + Math.abs(drag.now[1] - drag.p[1]) + Math.abs(drag.now[2] - drag.p[2]) + Math.abs(drag.now[3] - drag.p[3]) > 2) {
      setPos({ ...pos, [drag.id]: drag.now });
    }
    setDrag(null);
  };
  const ids = [...L.areas.map((a) => a.id), ...added.map((a) => a.id)];
  const current = sel ? at(sel) : null;
  const changes = Object.keys(pos).length + added.length;
  const text = () => editedVenueText(file.data ?? '', pos, added);
  const blue = theme.palette.primary.main, green = '#16a34a', violet = '#7c3aed';

  function addArea(e: FormEvent) {
    e.preventDefault();
    const id = na.id.trim().toUpperCase();
    if (venue?.areas.includes(id) || added.some((a) => a.id === id)) { toast(t('There is already an area {{id}}.', { id }), false); return; }
    setAdded([...added, { ...na, id }]);
    setPos({ ...pos, [id]: [L!.w / 2 - 80, L!.h / 2 - 50, 160, 100] });
    setSel(id);
    setNa({ id: '', name: '', sector: '', type: 'HALL', capacity: '', size: '' });
  }

  return (
    <Stack spacing={2}>
      <Typography variant="h5" sx={{ fontWeight: 700 }}>{t('Venue editor')}</Typography>
      <Box sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', lg: '3fr 1fr' } }}>
        <Card><CardContent>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>{t('Drag an area to move it, drag its blue corner to resize it; click it to type exact numbers. Moved areas turn violet.')}</Typography>
          <Box sx={{ borderRadius: 2, overflow: 'hidden', border: 1, borderColor: 'divider', bgcolor: 'action.hover' }}>
            <svg ref={svg} viewBox={`0 0 ${L.w} ${L.h}`} style={{ width: '100%', height: 'auto', display: 'block', touchAction: 'none' }} onPointerMove={move} onPointerUp={up} onPointerCancel={up}>
              {image && <image href={image} width={L.w} height={L.h} />}
              {ids.map((id) => {
                const p = at(id), on = sel === id, moved = !!pos[id];
                const col = on ? blue : moved ? violet : green;
                return (
                  <g key={id}>
                    <rect x={p[0]} y={p[1]} width={p[2]} height={p[3]} rx={6} fill={col} fillOpacity={0.3} stroke={on ? blue : green} strokeWidth={on ? 4 : 2} style={{ cursor: 'move' }} onPointerDown={down(id, 'move')} />
                    <text x={p[0] + 6} y={p[1] + 18} fontSize={16} fontWeight={800} fill={theme.palette.text.primary} pointerEvents="none">{id}</text>
                    <rect x={p[0] + p[2] - 14} y={p[1] + p[3] - 14} width={14} height={14} fill={blue} style={{ cursor: 'nwse-resize' }} onPointerDown={down(id, 'size')} />
                  </g>
                );
              })}
            </svg>
          </Box>
        </CardContent></Card>
        <Card><CardContent>
          <Stack spacing={2}>
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{sel ?? t('No area selected')}</Typography>
            {current && sel ? (
              <PositionForm key={sel + current.join()} rect={current} onApply={(r) => setPos({ ...pos, [sel]: r })} />
            ) : <Typography variant="body2" color="text.secondary">{t('Click an area on the drawing.')}</Typography>}
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Add an area')}</Typography>
            <Stack component="form" spacing={1.5} onSubmit={addArea}>
              <Stack direction="row" spacing={1}>
                <TextField size="small" label="ID" placeholder="GATE-D" value={na.id} onChange={(e) => setNa({ ...na, id: e.target.value })} required slotProps={{ htmlInput: { maxLength: 20 } }} />
                <TextField size="small" type="number" label={t('Capacity')} value={na.capacity} onChange={(e) => setNa({ ...na, capacity: e.target.value })} required slotProps={{ htmlInput: { min: 1 } }} />
              </Stack>
              <TextField size="small" label={t('Name')} value={na.name} onChange={(e) => setNa({ ...na, name: e.target.value })} required slotProps={{ htmlInput: { maxLength: 60 } }} />
              <Stack direction="row" spacing={1}>
                <TextField size="small" label={t('Sector')} value={na.sector} onChange={(e) => setNa({ ...na, sector: e.target.value })} required />
                <TextField size="small" label={t('Type')} value={na.type} onChange={(e) => setNa({ ...na, type: e.target.value })} />
              </Stack>
              <TextField size="small" type="number" label={t('Floor area m² (optional)')} value={na.size} onChange={(e) => setNa({ ...na, size: e.target.value })} slotProps={{ htmlInput: { min: 1 } }} />
              <Typography variant="caption" color="text.secondary">{t('It appears in the middle of the drawing; drag it into place. Link it to its neighbours in the venue file (link: lines).')}</Typography>
              <Box><Button type="submit" variant="outlined">{t('Add')}</Button></Box>
            </Stack>
            <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>{t('Save')}</Typography>
            <Typography variant="body2" color="text.secondary">{t('{{n}} change(s). The venue file gets new pos: lines (and area: lines for new areas); everything else stays as it is.', { n: changes })}</Typography>
            <Stack direction="row" spacing={1}>
              <Button variant="outlined" disabled={!file.data} onClick={() => saveBlob(new Blob([text()], { type: 'text/plain' }), (venue?.code ?? 'venue') + '.venue')}>{t('Download venue file')}</Button>
              <Button variant="contained" disabled={!file.data || changes === 0 || save.isPending} onClick={() => save.mutate({ text: text() })}>{t('Save on the server')}</Button>
            </Stack>
          </Stack>
        </CardContent></Card>
      </Box>
    </Stack>
  );
}

function PositionForm({ rect, onApply }: { rect: Rect; onApply: (r: Rect) => void }) {
  const { t } = useTranslation();
  const [v, setV] = useState(rect.map((x) => String(Math.round(x))));
  const field = (k: number, label: string) => (
    <TextField size="small" type="number" label={label} value={v[k]} onChange={(e) => { const n = [...v]; n[k] = e.target.value; setV(n); }} sx={{ flex: 1 }} />
  );
  return (
    <Stack component="form" spacing={1.5} onSubmit={(e: FormEvent) => { e.preventDefault(); onApply([+v[0], +v[1], Math.max(10, +v[2]), Math.max(10, +v[3])]); }}>
      <Stack direction="row" spacing={1}>{field(0, 'x')}{field(1, 'y')}</Stack>
      <Stack direction="row" spacing={1}>{field(2, t('width'))}{field(3, t('height'))}</Stack>
      <Box><Button type="submit" variant="outlined">{t('Apply')}</Button></Box>
    </Stack>
  );
}
