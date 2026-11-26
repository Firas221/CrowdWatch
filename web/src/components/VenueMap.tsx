import { useMemo } from 'react';
import { Box, useTheme } from '@mui/material';
import { useAreas, useImage, useLayout, useUnits } from '../api/queries';
import type { EvacuationRow, Level, ReplayFrame } from '../api/types';
import { LEVEL_COLOR } from '../theme';
import { fmt } from './ui';

const GRAY = '#64748b';

/**
 * The venue drawing with every area in its crowd colour, the number of units there, exits with
 * their flow, and (when rows are given) the evacuation routes as arrows, thicker for more people.
 * With a replay frame, the areas show that minute instead of now.
 */
export function VenueMap({ rows, frame, onArea, maxHeight = 640 }: { rows?: EvacuationRow[] | null; frame?: ReplayFrame['areas']; onArea?: (id: string) => void; maxHeight?: number }) {
  const theme = useTheme();
  const { data: L } = useLayout();
  const { data: areas } = useAreas();
  const { data: units } = useUnits();
  const image = useImage(L?.map && L.image ? '/api/map' : null);
  const byId = useMemo(() => new Map((areas ?? []).map((a) => [a.id, a])), [areas]);
  const unitsAt = useMemo(() => {
    const n = new Map<string, number>();
    for (const u of units ?? []) if (u.status !== 'OFF_DUTY') n.set(u.area, (n.get(u.area) ?? 0) + 1);
    return n;
  }, [units]);

  if (!L?.map) return null;
  const fs = Math.max(11, Math.round(L.w / 95));
  const centre = new Map(L.areas.map((p) => [p.id, [p.x + p.w / 2, p.y + p.h / 2] as const]));
  const text = theme.palette.text.primary;

  return (
    <Box sx={{ width: '100%', overflow: 'hidden', borderRadius: 2, border: 1, borderColor: 'divider', bgcolor: 'action.hover' }}>
      <svg viewBox={`0 0 ${L.w} ${L.h}`} role="img" aria-label="Venue map with live crowd levels" style={{ width: '100%', height: 'auto', maxHeight, display: 'block' }}>
        <defs>
          {(['ok', 'zone'] as const).map((k) => (
            <marker key={k} id={'cwArrow-' + k} viewBox="0 0 10 10" refX="7" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" fill={k === 'zone' ? LEVEL_COLOR.HIGH : LEVEL_COLOR.LOW} />
            </marker>
          ))}
        </defs>
        {image && <image href={image} x={0} y={0} width={L.w} height={L.h} preserveAspectRatio="none" />}
        {L.areas.map((p) => {
          const live = byId.get(p.id);
          const f = frame?.[p.id];
          const level: Level = f ? f[1] : live?.level ?? p.level;
          const pct = f ? (live && live.capacity > 0 ? Math.round((f[0] * 100) / live.capacity) : 0) : live?.percent ?? p.pct;
          const restricted = live?.restricted ?? p.restricted;
          const doubtful = !f && (live?.stale || live?.suspect);
          const col = restricted || doubtful ? GRAY : LEVEL_COLOR[level];
          const hot = !restricted && (level === 'HIGH' || level === 'CRITICAL');
          const evacuating = !f && (live?.evacuating ?? p.evacuating);
          const n = f ? 0 : unitsAt.get(p.id) ?? 0;
          const common = {
            fill: col, fillOpacity: restricted ? 0.12 : hot ? 0.42 : 0.26, stroke: col, strokeWidth: hot ? 4 : 2, strokeLinejoin: 'round' as const,
            strokeDasharray: p.exit ? '10 6' : evacuating ? '4 4' : undefined,
          };
          return (
            <g key={p.id} onClick={onArea ? () => onArea(p.id) : undefined} style={{ cursor: onArea ? 'pointer' : undefined }}>
              <title>{(live?.name ?? p.id) + ' - ' + (restricted ? 'staff only' : `${pct}% ${level}`)}</title>
              {p.shape ? <polygon points={p.shape.join(' ')} {...common} /> : <rect x={p.x} y={p.y} width={p.w} height={p.h} rx={8} {...common} />}
              <text x={p.x + 7} y={p.y + fs + 4} fontSize={fs} fontWeight={800} fill={text}>{p.id}</text>
              {!restricted && <text x={p.x + 7} y={p.y + 2 * fs + 8} fontSize={Math.round(fs * 1.25)} fontWeight={800} fill={col}>{pct}%</text>}
              {p.exit && <text x={p.x + p.w - 7} y={p.y + p.h - 8} textAnchor="end" fontSize={fs} fontWeight={800} fill={LEVEL_COLOR.LOW}>EXIT{p.flow ? ` ${fmt(p.flow)}/min` : ''}</text>}
              {n > 0 && (
                <g>
                  <circle cx={p.x + p.w - fs} cy={p.y + fs} r={fs * 0.85} fill={theme.palette.primary.main} />
                  <text x={p.x + p.w - fs} y={p.y + fs * 1.35} textAnchor="middle" fontSize={fs} fontWeight={800} fill="#fff">{n}</text>
                </g>
              )}
            </g>
          );
        })}
        {rows && [...new Map(rows.map((r) => [r.path, r])).values()].map((r) => {
          const pts = String(r.path).replace(/^\[|\]$/g, '').split(' -> ').map((id) => centre.get(id)).filter((x): x is readonly [number, number] => !!x);
          if (pts.length < 2) return null;
          const people = parseInt(r.people, 10) || 0;
          const w = people > 0 ? Math.max(3, Math.min(14, Math.log(people + 1) * 1.4)) : 2;
          return (
            <polyline key={r.path} points={pts.map((q) => q.join(',')).join(' ')} fill="none" stroke={r.inZone ? LEVEL_COLOR.HIGH : LEVEL_COLOR.LOW} strokeOpacity={0.9}
              strokeWidth={w} strokeLinecap="round" strokeLinejoin="round" markerEnd={`url(#cwArrow-${r.inZone ? 'zone' : 'ok'})`}>
              <title>{`${r.from} -> ${r.exit}${people ? `: ${fmt(people)} people, out in about ${r.clear} min` : ''}`}</title>
            </polyline>
          );
        })}
      </svg>
    </Box>
  );
}

/** Colour key of the crowd levels. */
export function LevelLegend({ t }: { t: (s: string) => string }) {
  return (
    <Box sx={{ display: 'flex', gap: 1.5, flexWrap: 'wrap', fontSize: 13, color: 'text.secondary' }}>
      {(['LOW', 'MODERATE', 'HIGH', 'CRITICAL'] as Level[]).map((l) => (
        <Box key={l} sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
          <Box sx={{ width: 10, height: 10, borderRadius: 0.5, bgcolor: LEVEL_COLOR[l] }} />{t(l)}
        </Box>
      ))}
    </Box>
  );
}
