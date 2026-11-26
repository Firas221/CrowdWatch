import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert as MuiAlert, Box, Card, CardContent, Chip, Snackbar, Typography, useTheme } from '@mui/material';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import * as echarts from 'echarts/core';
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, TooltipComponent, LegendComponent, MarkLineComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';
import { ApiError, post } from '../api/client';
import type { ActionResult } from '../api/types';

echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, MarkLineComponent, CanvasRenderer]);

/** A coloured state label (crowd level, priority, status); the text is translated, the colour fixed. */
export function StateChip({ label, color, size = 'small' }: { label: string; color: string; size?: 'small' | 'medium' }) {
  const { t } = useTranslation();
  return (
    <Chip size={size} label={t(label)} sx={{ bgcolor: color + '26', color, border: '1px solid ' + color + '66', fontWeight: 700, letterSpacing: 0.3 }} />
  );
}

/** A figure with its caption, as on the overview. */
export function Stat({ label, value, note, color, onClick }: { label: string; value: ReactNode; note?: ReactNode; color?: string; onClick?: () => void }) {
  return (
    <Card sx={{ cursor: onClick ? 'pointer' : undefined, '&:hover': onClick ? { borderColor: 'primary.main' } : undefined }} onClick={onClick}>
      <CardContent>
        <Typography variant="body2" color="text.secondary">{label}</Typography>
        <Typography variant="h4" sx={{ fontWeight: 700, color, fontVariantNumeric: 'tabular-nums', my: 0.5 }}>{value}</Typography>
        {note && <Typography variant="body2" color="text.secondary">{note}</Typography>}
      </CardContent>
    </Card>
  );
}

export const fmt = (n: number | null | undefined) => (n == null ? '-' : Number(n).toLocaleString('en-US'));

/** An Apache ECharts chart that follows the theme and resizes with its box. */
export function Chart({ option, height = 280 }: { option: echarts.EChartsCoreOption; height?: number }) {
  const ref = useRef<HTMLDivElement>(null);
  const chart = useRef<echarts.ECharts | null>(null);
  const theme = useTheme();
  const dark = theme.palette.mode === 'dark';
  useEffect(() => {
    if (!ref.current) return;
    const c = echarts.init(ref.current, dark ? 'dark' : undefined, { renderer: 'canvas' });
    chart.current = c;
    const ro = new ResizeObserver(() => c.resize());
    ro.observe(ref.current);
    return () => { ro.disconnect(); c.dispose(); chart.current = null; };
  }, [dark]);
  useEffect(() => {
    chart.current?.setOption({ backgroundColor: 'transparent', textStyle: { fontFamily: theme.typography.fontFamily }, ...option }, { notMerge: true });
  }, [option, dark, theme.typography.fontFamily]);
  return <Box ref={ref} sx={{ width: '100%', height }} />;
}

// ---------- messages to the user ----------
type Note = { text: string; good: boolean; n: number };
const ToastContext = createContext<(text: string, good: boolean) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [note, setNote] = useState<Note | null>(null);
  const show = useCallback((text: string, good: boolean) => setNote({ text, good, n: Date.now() }), []);
  return (
    <ToastContext.Provider value={show}>
      {children}
      <Snackbar key={note?.n} open={note != null} autoHideDuration={note?.good ? 4000 : 8000} onClose={() => setNote(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}>
        <MuiAlert severity={note?.good ? 'success' : 'error'} variant="filled" onClose={() => setNote(null)} sx={{ maxWidth: 640 }}>
          {note?.text}
        </MuiAlert>
      </Snackbar>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);

/**
 * A change sent to the server: shows the server's own answer (done, or why a rule refused it),
 * then reloads the live data. Returns the mutation so buttons can show that they are busy.
 */
export function useAction(path: string, after?: (r: ActionResult) => void) {
  const toast = useToast();
  const qc = useQueryClient();
  const { t } = useTranslation();
  return useMutation({
    mutationFn: (fields: Record<string, string | number | boolean | null | undefined>) => post<ActionResult>(path, fields),
    onSuccess: (r) => {
      toast(r.message || t(r.ok ? 'Done.' : 'Refused.'), r.ok);
      if (r.ok && after) after(r);
      qc.invalidateQueries({ queryKey: ['live'] });
    },
    onError: (e) => { if (!(e instanceof ApiError && e.status === 401)) toast(e.message, false); },
  });
}
