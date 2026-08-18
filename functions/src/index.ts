import { getAuth } from "firebase-admin/auth";
import { FieldValue } from "firebase-admin/firestore";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger, setGlobalOptions } from "firebase-functions/v2";
import { createAlert, db, distanceMeters, getParams } from "./helpers";

// The Admin app is initialised in ./helpers, which loads first (see note there).

// Firestore for this project lives in asia-southeast2 (Jakarta). Eventarc will
// only place a Firestore trigger in the database's own region, so every function
// here has to run there too — and it is the right region regardless, since both
// the collectors and the App Hosting backend are in Indonesia. Note that a
// non-default region means the web client must ask for it explicitly:
// getFunctions(app, "asia-southeast2"), otherwise callables 404 in us-central1.
setGlobalOptions({ region: "asia-southeast2", maxInstances: 10 });

const VALID_ROLES = ["collector", "supervisor", "admin", "management"] as const;

/**
 * Admin-only: creates a real Firebase Auth account (email {code}@collectionfield.app,
 * password = PIN) plus the matching `users/{uid}` profile. The dashboard's client SDK
 * can't create *other* users' accounts on its own (it would hijack the caller's
 * session), so this has to run server-side with the Admin SDK.
 */
export const createUser = onCall(async (request) => {
  if (!request.auth || request.auth.token.role !== "admin") {
    throw new HttpsError("permission-denied", "Hanya admin yang boleh menambah user.");
  }

  const data = request.data as {
    employeeCode?: string;
    pin?: string;
    name?: string;
    role?: string;
    teamId?: string | null;
    branchId?: string | null;
    active?: boolean;
  };

  const employeeCode = String(data.employeeCode || "").trim().toUpperCase().replace(/\s+/g, "");
  if (!/^[A-Z0-9_-]{2,32}$/.test(employeeCode)) {
    throw new HttpsError("invalid-argument", "Format Kode Pegawai tidak valid.");
  }

  const pin = String(data.pin || "");
  if (!/^\d{6,12}$/.test(pin)) {
    throw new HttpsError("invalid-argument", "PIN harus 6-12 digit angka.");
  }

  const name = String(data.name || "").trim();
  if (!name) {
    throw new HttpsError("invalid-argument", "Nama wajib diisi.");
  }

  const role = String(data.role || "collector").toLowerCase();
  if (!VALID_ROLES.includes(role as (typeof VALID_ROLES)[number])) {
    throw new HttpsError("invalid-argument", "Role tidak valid.");
  }

  const email = `${employeeCode.toLowerCase()}@collectionfield.app`;
  const auth = getAuth();

  const existing = await auth.getUserByEmail(email).catch(() => null);
  if (existing) {
    throw new HttpsError("already-exists", `Kode Pegawai ${employeeCode} sudah terdaftar.`);
  }

  const active = data.active !== false;
  const userRecord = await auth.createUser({
    email,
    password: pin,
    displayName: name,
    disabled: !active,
  });

  await auth.setCustomUserClaims(userRecord.uid, { role, active });

  await db.collection("users").doc(userRecord.uid).set({
    employee_code: employeeCode,
    email,
    name,
    role,
    team_id: data.teamId || null,
    branch_id: data.branchId || null,
    device_id: null,
    active,
    created_at: FieldValue.serverTimestamp(),
    updated_at: FieldValue.serverTimestamp(),
  });

  return { uid: userRecord.uid, email };
});

/**
 * Keeps the Firebase Auth custom claim (read by every Firestore/RTDB/Storage
 * security rule) in sync with the `users/{uid}` document's role/active fields —
 * so a role change made anywhere (bootstrap script or the admin dashboard) takes
 * effect without a separate manual step.
 */
export const syncUserRoleClaims = onDocumentWritten("users/{uid}", async (event) => {
  const uid = event.params.uid;
  const after = event.data?.after?.data();
  if (!after) return; // user doc deleted — leave existing claims untouched

  const role = typeof after.role === "string" ? after.role.toLowerCase() : "collector";
  const active = after.active !== false;

  try {
    await getAuth().setCustomUserClaims(uid, { role, active });
  } catch (err) {
    logger.error(`syncUserRoleClaims failed for ${uid}`, err);
  }
});

/**
 * Fires on every GPS point synced from the field app. Flags client-reported mock
 * locations, and independently derives implied speed between consecutive points
 * to catch GPS spoofing / teleportation regardless of what the client claims.
 */
export const detectTelemetryFraud = onDocumentCreated(
  "shifts/{shiftId}/telemetry/{pointId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;
    const point = snap.data();
    const shiftId = event.params.shiftId as string;

    if (point.mock_flag === true) {
      await createAlert({
        type: "mock_location",
        severity: "critical",
        collector_id: point.collector_id,
        collector_uid: point.collector_uid,
        shift_id: shiftId,
        message: "Terdeteksi lokasi palsu (mock GPS) pada titik telemetri",
      });
    }

    if (point.low_confidence === true) return; // don't derive speed from a noisy fix

    const recent = await db
      .collection("shifts")
      .doc(shiftId)
      .collection("telemetry")
      .orderBy("captured_at", "desc")
      .limit(2)
      .get();
    if (recent.size < 2) return;

    const [latest, prev] = recent.docs.map((d) => d.data());
    const dtSec = (latest.captured_at.toMillis() - prev.captured_at.toMillis()) / 1000;
    if (dtSec <= 0) return;

    const distance = distanceMeters(latest.lat, latest.lng, prev.lat, prev.lng);
    const impliedKmh = (distance / dtSec) * 3.6;

    const params = await getParams();
    if (impliedKmh > params.impossible_movement_kmh) {
      await createAlert({
        type: "impossible_movement",
        severity: "critical",
        collector_id: point.collector_id,
        collector_uid: point.collector_uid,
        shift_id: shiftId,
        message: `Kecepatan tersirat ${impliedKmh.toFixed(0)} km/jam antar titik GPS melebihi batas wajar (${params.impossible_movement_kmh} km/jam)`,
      });
    }
  },
);

/**
 * Fires the moment a visit's departure_at is newly set (auto-geofence exit or
 * manual submission). Flags suspiciously short visits and — for MANUAL visits,
 * which skip GPS-verified arrival — flags an arrival point far from the outlet.
 */
export const onVisitClosed = onDocumentUpdated("visits/{visitId}", async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after) return;
  if (before.departure_at || !after.departure_at) return;

  const params = await getParams();
  const durationSec: number = after.duration_sec ?? 0;

  if (durationSec < params.visit_too_short_sec) {
    await createAlert({
      type: "visit_too_short",
      severity: "warning",
      collector_id: after.collector_id,
      collector_uid: after.collector_uid,
      shift_id: after.shift_id,
      message: `Kunjungan hanya berlangsung ${durationSec} detik (batas: ${params.visit_too_short_sec}s)`,
    });
  }

  if (after.method === "MANUAL" && after.arrival_lat != null && after.arrival_lng != null) {
    const outletSnap = await db.collection("outlets").doc(after.outlet_id).get();
    const outlet = outletSnap.data();
    if (outlet) {
      const distance = distanceMeters(after.arrival_lat, after.arrival_lng, outlet.lat, outlet.lng);
      const radius = outlet.radius_m ?? params.default_outlet_radius_m;
      if (distance > radius) {
        await createAlert({
          type: "outlet_mismatch",
          severity: "warning",
          collector_id: after.collector_id,
          collector_uid: after.collector_uid,
          shift_id: after.shift_id,
          message: `Check-in manual berjarak ${distance.toFixed(0)}m dari outlet (radius ${radius}m)`,
        });
      }
    }
  }
});

/**
 * Is this point inside any outlet's geofence?
 *
 * Deliberately NOT a full-collection scan: `outlets` holds ~7.7k documents, and
 * reading all of them per shift per 10-minute tick was by itself enough to blow
 * through the whole daily Firestore read quota. Instead this narrows by latitude
 * first — a single-field range query served by the automatic index, returning a
 * handful of documents — then finishes the check in memory.
 *
 * The latitude band is intentionally wider than any plausible geofence so the
 * cheap pre-filter can never exclude an outlet the exact haversine test would
 * have matched.
 */
const LAT_BAND_DEG = 0.02; // ~2.2 km north/south

async function isNearAnyOutlet(lat: number, lng: number, defaultRadiusM: number): Promise<boolean> {
  const nearby = await db
    .collection("outlets")
    .where("lat", ">=", lat - LAT_BAND_DEG)
    .where("lat", "<=", lat + LAT_BAND_DEG)
    .get();

  return nearby.docs.some((o) => {
    const outlet = o.data();
    if (typeof outlet.lat !== "number" || typeof outlet.lng !== "number") return false;
    return distanceMeters(lat, lng, outlet.lat, outlet.lng) <= (outlet.radius_m ?? defaultRadiusM);
  });
}

/**
 * Every 10 minutes during working hours (09:00-21:00 WIB), scans shifts still
 * marked active for signs of trouble a per-event trigger can't see on its own:
 * a GPS feed that's gone silent, or a stop lasting too long outside any outlet.
 */
export const scanActiveShifts = onSchedule(
  { schedule: "*/10 9-20 * * *", timeZone: "Asia/Jakarta" },
  async () => {
    const params = await getParams();
    const activeShifts = await db.collection("shifts").where("status", "==", "active").get();

    for (const shiftDoc of activeShifts.docs) {
      const shift = shiftDoc.data();
      const latestTelemetry = await db
        .collection("shifts")
        .doc(shiftDoc.id)
        .collection("telemetry")
        .orderBy("captured_at", "desc")
        .limit(1)
        .get();

      if (latestTelemetry.empty) continue;
      const latest = latestTelemetry.docs[0].data();
      const ageMin = (Date.now() - latest.captured_at.toMillis()) / 60_000;

      if (ageMin > params.offline_timeout_mins) {
        await createAlert({
          type: "offline",
          severity: "warning",
          collector_id: shift.collector_id,
          collector_uid: shift.collector_uid,
          shift_id: shiftDoc.id,
          message: `Tidak ada update GPS selama ${ageMin.toFixed(0)} menit`,
        });
        continue; // no point checking long-stop on stale data
      }

      // Long-stop: look back over the last hour of points, check if the collector
      // has been essentially stationary and outside every outlet's radius.
      const lookback = new Date(Date.now() - 60 * 60_000);
      const recentPoints = await db
        .collection("shifts")
        .doc(shiftDoc.id)
        .collection("telemetry")
        .where("captured_at", ">=", lookback)
        .orderBy("captured_at", "asc")
        .get();
      if (recentPoints.empty) continue;

      const points = recentPoints.docs.map((d) => d.data());
      const first = points[0];
      const stationaryMin = (latest.captured_at.toMillis() - first.captured_at.toMillis()) / 60_000;
      if (stationaryMin < params.long_stop_threshold_mins) continue;

      const maxDrift = Math.max(
        ...points.map((p) => distanceMeters(first.lat, first.lng, p.lat, p.lng)),
      );
      if (maxDrift > 60) continue; // moved around too much to call this a "stop"

      const nearOutlet = await isNearAnyOutlet(
        latest.lat,
        latest.lng,
        params.default_outlet_radius_m,
      );
      if (nearOutlet) continue;

      await createAlert({
        type: "long_stop",
        severity: "warning",
        collector_id: shift.collector_id,
        collector_uid: shift.collector_uid,
        shift_id: shiftDoc.id,
        message: `Berhenti ${stationaryMin.toFixed(0)} menit di luar radius outlet mana pun`,
      });
    }
  },
);

/**
 * Flags a login from a device different than the one last recorded for this
 * account — a signal of possible account/PIN sharing between collectors.
 */
export const onDeviceChanged = onDocumentUpdated("users/{uid}", async (event) => {
  const before = event.data?.before.data();
  const after = event.data?.after.data();
  if (!before || !after) return;

  const oldDevice = before.device_id;
  const newDevice = after.device_id;
  if (!oldDevice || !newDevice || oldDevice === newDevice) return;

  await createAlert({
    type: "device_changed",
    severity: "warning",
    collector_id: after.employee_code ?? event.params.uid,
    collector_uid: event.params.uid,
    message: `Login dari perangkat baru (sebelumnya: ${oldDevice})`,
  });
});
