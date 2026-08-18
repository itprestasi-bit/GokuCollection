import { getApps, initializeApp } from "firebase-admin/app";
import { getFirestore, FieldValue } from "firebase-admin/firestore";

// ES import hoisting runs this module's body *before* index.ts reaches its own
// initializeApp() call, so getFirestore() here used to throw app/no-app at load
// time — which failed the whole codebase analysis on deploy. Initialising from
// whichever module loads first, guarded, is the only order-independent fix.
if (!getApps().length) initializeApp();

const db = getFirestore();

export interface ParameterConfig {
  default_outlet_radius_m: number;
  long_stop_threshold_mins: number;
  offline_timeout_mins: number;
  visit_too_short_sec: number;
  impossible_movement_kmh: number;
}

const DEFAULT_PARAMS: ParameterConfig = {
  default_outlet_radius_m: 30,
  long_stop_threshold_mins: 20,
  offline_timeout_mins: 15,
  visit_too_short_sec: 60,
  impossible_movement_kmh: 120,
};

let cachedParams: { value: ParameterConfig; fetchedAt: number } | null = null;
const PARAMS_CACHE_MS = 60_000;

/** `parameters/global` is small and read on nearly every telemetry write, so cache it briefly. */
export async function getParams(): Promise<ParameterConfig> {
  if (cachedParams && Date.now() - cachedParams.fetchedAt < PARAMS_CACHE_MS) {
    return cachedParams.value;
  }
  const snap = await db.collection("parameters").doc("global").get();
  const value: ParameterConfig = { ...DEFAULT_PARAMS, ...(snap.data() as Partial<ParameterConfig> | undefined) };
  cachedParams = { value, fetchedAt: Date.now() };
  return value;
}

/** Haversine distance in meters between two lat/lng points. */
export function distanceMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6371e3;
  const phi1 = (lat1 * Math.PI) / 180;
  const phi2 = (lat2 * Math.PI) / 180;
  const dPhi = ((lat2 - lat1) * Math.PI) / 180;
  const dLambda = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dPhi / 2) ** 2 + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) ** 2;
  return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export type AlertType =
  | "long_stop"
  | "offline"
  | "gps_disabled"
  | "impossible_movement"
  | "visit_too_short"
  | "outlet_mismatch"
  | "stale_sync"
  | "mock_location"
  | "device_changed";

export interface CreateAlertInput {
  type: AlertType;
  severity: "warning" | "critical";
  collector_id: string;
  collector_uid: string;
  shift_id?: string | null;
  message: string;
}

const DEDUPE_WINDOW_MS = 5 * 60_000;

/**
 * Written exclusively from here (Admin SDK bypasses security rules) — the `alerts`
 * Firestore rule denies client-side create entirely, so a collector can never
 * suppress or fabricate their own compliance record.
 *
 * De-duplicates: skips creating a new alert if an `open` one of the same type for
 * the same shift was already raised within the last 5 minutes, so a sustained
 * condition (e.g. a shift that's been offline for an hour) doesn't flood the list.
 */
export async function createAlert(input: CreateAlertInput): Promise<void> {
  if (input.shift_id) {
    const since = new Date(Date.now() - DEDUPE_WINDOW_MS);
    const recent = await db
      .collection("alerts")
      .where("shift_id", "==", input.shift_id)
      .where("type", "==", input.type)
      .where("status", "==", "open")
      .where("created_at", ">=", since)
      .limit(1)
      .get();
    if (!recent.empty) return;
  }

  await db.collection("alerts").add({
    ...input,
    shift_id: input.shift_id ?? null,
    status: "open",
    resolution_note: null,
    created_at: FieldValue.serverTimestamp(),
  });
}

export { db };
