// The data the console shows, kept fresh by TanStack Query. Everything under the 'live' key is
// reloaded when the server pushes a change (see live.ts).

import { useQuery } from '@tanstack/react-query';
import { get } from './client';
import { usePollInterval } from './live';
import type { Alert, Area, IncidentDetail, IncidentRow, Overview, Readings, Unit, Venue } from './types';

function useLiveQuery<T>(name: string, path: string, enabled = true) {
  const every = usePollInterval();
  return useQuery({
    queryKey: ['live', name, path],
    queryFn: () => get<T>(path),
    refetchInterval: every,
    refetchIntervalInBackground: false,
    enabled,
  });
}

export const useOverview = () => useLiveQuery<Overview>('overview', '/api/overview');
export const useAreas = () => useLiveQuery<Area[]>('areas', '/api/areas');
export const useIncidents = () => useLiveQuery<IncidentRow[]>('incidents', '/api/incidents');
export const useUnits = () => useLiveQuery<Unit[]>('units', '/api/units');
export const useAlerts = () => useLiveQuery<Alert[]>('alerts', '/api/alerts');
export const useIncident = (id: number | null) => useLiveQuery<IncidentDetail>('incident', '/api/incident?id=' + id, id != null);
export const useReadings = (area: string | null) => useLiveQuery<Readings>('readings', '/api/readings?area=' + encodeURIComponent(area ?? ''), area != null);

/** The venue's settings, categories and areas change only when the venue file changes. */
export function useVenue() {
  return useQuery({ queryKey: ['venue'], queryFn: () => get<Venue>('/api/venue'), staleTime: 10 * 60_000 });
}
