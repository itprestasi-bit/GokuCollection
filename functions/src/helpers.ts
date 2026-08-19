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

/**
 * Written exclusively from here (Admin SDK bypasses security rules) — the `alerts`
 * Firestore rule denies client-side create entirely, so a collector can never
 * suppress or fabricate their own compliance record.
 *
 * One standing condition produces one alert, however long it lasts.
 *
 * This used to skip a duplicate only if an identical open alert had been raised in
 * the previous five minutes — while scanActiveShifts, which raises most of them,
 * runs every ten. The window therefore never once matched: a shift that went
 * offline at 09:00 and was still offline at 13:30 filed 25 separate alerts saying
 * the same thing with a larger number in it, and 49 of the 50 alerts in the system
 * were that one condition repeating.
 *
 * So the check is now on the standing alert itself rather than on a time window.
 * A recurrence refreshes the existing alert — the message carries the current
 * figure, `occurrences` records how many times it has been seen, `last_seen_at`
 * when. Resolve it and a genuinely new occurrence opens a fresh one, which is the
 * behaviour an operator expects from a list they have just cleared.
 */
export async function createAlert(input: CreateAlertInput): Promise<void> {
  // An alert without a shift (a device change, say) still belongs to a collector,
  // and should not be allowed to repeat either — the old code skipped dedupe for
  // those entirely.
  const scope = db
    .collection("alerts")
    .where("type", "==", input.type)
    .where("status", "==", "open");
  const query = input.shift_id
    ? scope.where("shift_id", "==", input.shift_id)
    : scope.where("collector_uid", "==", input.collector_uid);

  const existing = await query.limit(1).get();
  if (!existing.empty) {
    await existing.docs[0].ref.update({
      message: input.message,
      severity: input.severity,
      occurrences: FieldValue.increment(1),
      last_seen_at: FieldValue.serverTimestamp(),
    });
    return;
  }

  await db.collection("alerts").add({
    ...input,
    shift_id: input.shift_id ?? null,
    status: "open",
    resolution_note: null,
    occurrences: 1,
    created_at: FieldValue.serverTimestamp(),
    last_seen_at: FieldValue.serverTimestamp(),
  });
}

export { db };
