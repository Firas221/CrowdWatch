// The data the console shows, kept fresh by TanStack Query. Everything under the 'live' key is
// reloaded when the server pushes a change (see live.ts).

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { get, getBlob } from './client';
import { usePollInterval } from './live';
import type { Alert, Announcement, Area, Evacuation, IncidentDetail, IncidentRow, Layout, LostCase, Message, Overview, Readings, Site, Unit, Venue } from './types';

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

export const useLost = () => useLiveQuery<LostCase[]>('lost', '/api/lost');
export const useAnnouncements = () => useLiveQuery<Announcement[]>('announcements', '/api/announcements');
export const useLayout = () => useLiveQuery<Layout>('layout', '/api/layout');
/** The evacuation in progress, or a preview of the plan for a zone. */
export const useEvacuation = (zone: string, enabled: boolean) => useLiveQuery<Evacuation>('evacuation', '/api/evacuation?zone=' + encodeURIComponent(zone), enabled);
export const useMessages = (q: { unit?: string | null; incident?: number | null }, enabled = true) =>
  useLiveQuery<Message[]>('messages', '/api/messages?' + (q.unit ? 'unit=' + encodeURIComponent(q.unit) : '') + (q.incident ? '&incident=' + q.incident : ''), enabled);

/** The other sites of a head office (each asked by the server, so not too often). */
export function useSites(enabled: boolean) {
  return useQuery({ queryKey: ['sites'], queryFn: () => get<Site[]>('/api/sites'), refetchInterval: 15_000, enabled });
}

/**
 * An image the server serves only with the sign-in (venue drawing, photos), as an object URL.
 * Fetched once per path and released when no longer shown.
 */
export function useImage(path: string | null) {
  const q = useQuery({ queryKey: ['image', path], queryFn: () => getBlob(path!), enabled: path != null, staleTime: Infinity, gcTime: 10 * 60_000 });
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!q.data) { setUrl(null); return; }
    const u = URL.createObjectURL(q.data);
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [q.data]);
  return url;
}
