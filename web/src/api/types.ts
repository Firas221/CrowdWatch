// Shapes of the CrowdWatch API replies (see crowdwatch.net.ApiServer). Field names match the JSON exactly.

export type Level = 'LOW' | 'MODERATE' | 'HIGH' | 'CRITICAL';
export type Priority = 'CRITICAL' | 'HIGH' | 'NORMAL' | 'LOW';
export type IncidentStatus = 'WAITING' | 'ASSIGNED' | 'ON_SCENE' | 'RESOLVED';
export type UnitStatus = 'AVAILABLE' | 'BUSY' | 'OFF_DUTY' | 'UNREACHABLE';
export type Role = 'OPERATOR' | 'OFFICER' | 'ADMIN';

/** Reply of every change: 200 when done, 422 when a rule refused it. */
export interface ActionResult {
  ok: boolean;
  message: string;
}

export interface Me {
  signedIn: boolean;
  logins: boolean;
  keyRequired?: boolean;
  name?: string;
  role?: Role | 'DEVICE' | 'SCOUT';
  unit?: string | null;
  system?: boolean;
  twoStep?: boolean;
}

export interface LoginReply {
  ok: boolean;
  message?: string;
  needCode?: boolean;
  token?: string;
  name?: string;
  role?: Role;
  hours?: number;
}

export interface Overview {
  venue: string;
  code: string;
  now: string;
  people: number;
  capacity: number;
  areas: number;
  levels: Record<Level, number>;
  incidents: { open: number; waiting: number; unacknowledged: number } & Record<Priority, number>;
  units: { total: number; online: number } & Record<UnitStatus, number>;
  review: number;
  automation: boolean;
  autoRule: string | null;
  officer: string | null;
  officerStatus: string;
  overdue: boolean;
  evacuation: string | null;
  archived: number;
  proposal: { zone: string; reason: string; time: string } | null;
  signs: number;
  stale: number;
  lost: number;
  events: string[];
  countCheck: { expected: number; counted: number; difference: number; ok: boolean } | null;
  drill: boolean;
  heat: { temp: number; humidity: number; index: number; band: string; outdoor: string[] } | null;
  sites: boolean;
}

export interface Area {
  id: string;
  name: string;
  sector: string;
  type: string;
  occupancy: number;
  capacity: number;
  percent: number;
  level: Level;
  ratePerMinute: number;
  minutesToCritical: number;
  restricted: boolean;
  evacuating: boolean;
  stale: boolean;
  suspect: string | null;
  flowHeld: boolean;
  eventOffset: number;
  size: number;
  density: number;
  densityLevel: Level;
  camera: string | null;
  unitsHere: number;
  unitsWanted: number;
  links: string[];
}

export interface IncidentRow {
  id: number;
  category: string;
  priority: Priority;
  area: string;
  status: IncidentStatus;
  unit: string | null;
  minutesOpen: number;
  label: string;
  reported: string;
  automatic: boolean;
  review: boolean;
  device: string;
  acknowledged: boolean;
  reminders: number;
  lost: boolean;
  found: string | null;
}

export interface IncidentDetail {
  ok: boolean;
  message?: string;
  id: number;
  category: string;
  label: string;
  priority: Priority;
  area: string;
  areaName: string;
  status: IncidentStatus;
  unit: string | null;
  reported: string;
  details: string | null;
  contact: string | null;
  language: string | null;
  automatic: boolean;
  reviewedBy: string | null;
  open: boolean;
  device: string;
  route: string;
  lost: boolean;
  foundWhere: string | null;
  foundBy: string | null;
  search: string[];
  meeting: string | null;
  history: { t: string; text: string }[];
}

export interface Unit {
  id: string;
  name: string;
  area: string;
  sector: string;
  status: UnitStatus;
  skills: string;
  online: boolean;
  battery: number;
  shift: string | null;
  staged: string | null;
  reinforcing: string | null;
  home: string | null;
  lastContact: string | null;
  task: { id: number; label: string; area: string; priority: Priority; device: string } | null;
}

export interface Alert {
  time: string;
  kind: string;
  ref: string | null;
  text: string;
}

export interface Venue {
  name: string;
  code: string;
  thresholds: [number, number, number];
  automation: boolean;
  categories: { code: string; label: string; priority: Priority; skill: string | null }[];
  sectors: string[];
  areas: string[];
  exits: string[];
  fireZones: string[];
}

export interface Readings {
  ok: boolean;
  area: string;
  capacity: number;
  readings: { s: number; n: number }[];
}
