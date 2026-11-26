import { createTheme } from '@mui/material/styles';
import type { Level, Priority, IncidentStatus, UnitStatus } from './api/types';

/** Crowd levels and other states, the same colours as the classic console. */
export const LEVEL_COLOR: Record<Level, string> = { LOW: '#16a34a', MODERATE: '#d97706', HIGH: '#ea580c', CRITICAL: '#dc2626' };
export const PRIORITY_COLOR: Record<Priority, string> = { CRITICAL: '#dc2626', HIGH: '#ea580c', NORMAL: '#2563eb', LOW: '#d97706' };
export const STATUS_COLOR: Record<IncidentStatus, string> = { WAITING: '#d97706', ASSIGNED: '#2563eb', ON_SCENE: '#7c3aed', RESOLVED: '#16a34a' };
export const UNIT_COLOR: Record<UnitStatus, string> = { AVAILABLE: '#16a34a', BUSY: '#2563eb', OFF_DUTY: '#64748b', UNREACHABLE: '#dc2626' };

export function makeTheme(mode: 'light' | 'dark', direction: 'ltr' | 'rtl') {
  return createTheme({
    direction,
    palette: {
      mode,
      primary: { main: '#2563eb' },
      error: { main: '#dc2626' },
      warning: { main: '#d97706' },
      success: { main: '#16a34a' },
      background: mode === 'dark' ? { default: '#0b1220', paper: '#111a2e' } : { default: '#f3f5f9', paper: '#ffffff' },
    },
    shape: { borderRadius: 10 },
    typography: {
      fontFamily: direction === 'rtl'
        ? '"Segoe UI", Tahoma, "Noto Sans Arabic", system-ui, sans-serif'
        : 'Inter, "Segoe UI", system-ui, -apple-system, Roboto, sans-serif',
      button: { textTransform: 'none', fontWeight: 600 },
    },
    components: {
      MuiPaper: { styleOverrides: { root: { backgroundImage: 'none' } } },
      MuiCard: { defaultProps: { variant: 'outlined' } },
      MuiTableCell: { styleOverrides: { root: { paddingTop: 8, paddingBottom: 8 } } },
    },
  });
}
