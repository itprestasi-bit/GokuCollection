import { getAuth } from "firebase-admin/auth";
import { FieldValue } from "firebase-admin/firestore";
import { onDocumentCreated, onDocumentUpdated, onDocumentWritten } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { logger, setGlobalOptions } from "firebase-functions/v2";
import { createAlert, db, distanceMeters, getParams } from "./helpers";
import { decodePath, encodePath, distanceMeters as geoDistance } from "./polyline";
import { defineSecret } from "firebase-functions/params";

// The Admin app is initialised in ./helpers, which loads first (see note there).

// Firestore for this project lives in asia-southeast2 (Jakarta). Eventarc will
// only place a Firestore trigger in the database's own region, so every function
// here has to run there too — and it is the right region regardless, since both
// the collectors and the App Hosting backend are in Indonesia. Note that a
// non-default region means the web client must ask for it explicitly:
// getFunctions(app, "asia-southeast2"), otherwise callables 404 in us-central1.
setGlobalOptions({ region: "asia-southeast2", maxInstances: 10 });

/** Roads API accepts at most 100 points per request. */
const ROADS_BATCH = 100;
/** Spacing the trail is thinned to before snapping; interpolate fills the rest. */
const MIN_SNAP_SPACING_M = 25;
/** Ceiling on points per shift, so one long day cannot run up unbounded calls. */
const MAX_SNAP_POINTS = 5_000;

const VALID_ROLES = ["collector", "supervisor", "admin", "management"] as const;

/**
 * Server-side key for the Roads API.
 *
 * Deliberately not the key the browser bundle carries. That one is public by
 * construction and restricted by HTTP referrer, which the Roads API ignores —
 * it has no CORS support and cannot be called from a page at all. This key is
 * IP-restricted or unrestricted and must never leave the server.
 *
 *   firebase apphosting:secrets:set ROADS_API_KEY   (or functions:secrets:set)
 */
const roadsApiKey = defineSecret("ROADS_API_KEY");

/**
 * The key, with surrounding whitespace removed.
 *
 * A secret is set by pasting, and a paste carries whatever the clipboard held —
 * a trailing newline at minimum, which is invisible in every console that
 * displays it. In a URL that produced "API key not valid"; in the
 * X-Goog-Api-Key header it is an outright invalid header. Trimming here costs
 * nothing and removes a failure whose cause is unreadable from the error.
 */
function apiKey(): string {
  return roadsApiKey.value().trim();
}

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
 * Redraws a shift's GPS trail along the roads it was actually driven on.
 *
 * Raw GPS sits beside the carriageway, wanders between lanes and cuts corners
 * between fixes; snapped, the line follows the road and the replay reads like a
 * recording rather than a sketch. Google's Roads API does the matching.
 *
 * Three things shape this design:
 *
 *  - **It runs on the server** because the Roads API has no CORS support. There
 *    is no browser-side option to weigh up.
 *  - **The result is cached on the shift.** Snapping the same finished day twice
 *    buys nothing and costs the same again, so a second viewer reads the stored
 *    polyline. Only `force` re-snaps.
 *  - **The trail is thinned first.** The API accepts 100 points per request, and
 *    with interpolate=true it returns the road geometry *between* the points it
 *    is given — so feeding it a fix every two seconds costs 144 requests per
 *    shift to draw the same line that one every 25 metres draws in about 25.
 *
 * The snapped line is for display only. Distance, stop detection and the geofence
 * keep using raw GPS: map matching guesses, and a guess that puts a collector on
 * the road outside an outlet must never become evidence about whether they were
 * inside it.
 */
export const snapShiftTrack = onCall({ secrets: [roadsApiKey] }, async (request) => {
  const role = request.auth?.token.role;
  if (!request.auth || !["admin", "supervisor", "management"].includes(String(role))) {
    throw new HttpsError("permission-denied", "Hanya staff yang boleh menjalankan snap-to-road.");
  }

  const shiftId = String((request.data as { shiftId?: string })?.shiftId ?? "");
  const force = Boolean((request.data as { force?: boolean })?.force);
  if (!shiftId) throw new HttpsError("invalid-argument", "shiftId wajib diisi.");

  const shiftRef = db.collection("shifts").doc(shiftId);
  const shiftSnap = await shiftRef.get();
  if (!shiftSnap.exists) throw new HttpsError("not-found", "Shift tidak ditemukan.");

  const cached = shiftSnap.get("snapped_path");
  if (!force && typeof cached === "string" && cached.length > 0) {
    return { path: cached, cached: true, pointCount: shiftSnap.get("snapped_point_count") ?? null };
  }

  const chunks = await shiftRef.collection("track").orderBy("started_at", "asc").get();
  const raw: { lat: number; lng: number }[] = [];
  chunks.forEach((doc) => raw.push(...decodePath(String(doc.get("path") ?? ""))));
  if (raw.length < 2) {
    throw new HttpsError("failed-precondition", "Jejak shift ini terlalu pendek untuk ditempelkan ke jalan.");
  }

  // Thin to a fixed spacing, and widen that spacing if the day was long enough
  // that even thinned it would exceed the request ceiling. A runaway shift should
  // cost a bounded number of calls, not an unbounded one.
  let spacing = MIN_SNAP_SPACING_M;
  let thinned = thin(raw, spacing);
  while (thinned.length > MAX_SNAP_POINTS) {
    spacing *= 2;
    thinned = thin(raw, spacing);
  }

  const snapped: { lat: number; lng: number }[] = [];
  // One point of overlap between requests, otherwise each batch is matched in
  // isolation and the joins show as small jumps.
  for (let i = 0; i < thinned.length; i += ROADS_BATCH - 1) {
    const batch = thinned.slice(i, i + ROADS_BATCH);
    if (batch.length < 2) break;
    const path = batch.map((p) => `${p.lat},${p.lng}`).join("|");
    const url =
      `https://roads.googleapis.com/v1/snapToRoads?interpolate=true` +
      `&path=${encodeURIComponent(path)}&key=${apiKey()}`;

    const res = await fetch(url);
    const body = (await res.json()) as {
      snappedPoints?: { location: { latitude: number; longitude: number } }[];
      error?: { message?: string; status?: string };
    };
    if (!res.ok || body.error) {
      const detail = body.error?.message ?? `HTTP ${res.status}`;
      logger.error("snapToRoads failed", detail);
      throw new HttpsError("unavailable", `Roads API menolak permintaan: ${detail}`);
    }
    for (const p of body.snappedPoints ?? []) {
      snapped.push({ lat: p.location.latitude, lng: p.location.longitude });
    }
  }

  if (snapped.length < 2) {
    throw new HttpsError("unavailable", "Roads API tidak mengembalikan jalur.");
  }

  const encoded = encodePath(snapped);
  await shiftRef.update({
    snapped_path: encoded,
    snapped_point_count: snapped.length,
    snapped_spacing_m: spacing,
    snapped_at: FieldValue.serverTimestamp(),
  });

  return { path: encoded, cached: false, pointCount: snapped.length };
});

/**
 * Snaps a short run of recent positions to the road, for the live map.
 *
 * Separate from [snapShiftTrack] because the economics are opposite. A finished
 * shift is snapped once and cached forever; a live marker would be snapped again
 * every few seconds, for every collector, all day. Naively — one call per position
 * per collector — that is hundreds of thousands of paid requests a month, which is
 * why the caller sends a *window* of recent points on a timer instead of a point
 * per update, and only for collectors who are actually moving.
 *
 * A path is sent rather than a single point on purpose. Snapping one coordinate in
 * isolation has no direction to work with, so between two parallel roads it picks
 * whichever is nearer and the marker hops between them — the exact jumping this is
 * meant to remove. A run of points carries the direction of travel, so the match
 * stays on the road being driven.
 */
export const snapPath = onCall({ secrets: [roadsApiKey] }, async (request) => {
  const role = request.auth?.token.role;
  if (!request.auth || !["admin", "supervisor", "management"].includes(String(role))) {
    throw new HttpsError("permission-denied", "Hanya staff yang boleh memakai snap-to-road.");
  }

  const points = ((request.data as { points?: { lat: number; lng: number }[] })?.points ?? [])
    .filter((p) => Number.isFinite(p?.lat) && Number.isFinite(p?.lng))
    .slice(-ROADS_BATCH);
  if (points.length < 2) {
    throw new HttpsError("invalid-argument", "Minimal dua titik diperlukan untuk menempel ke jalan.");
  }

  const path = points.map((p) => `${p.lat},${p.lng}`).join("|");
  const res = await fetch(
    `https://roads.googleapis.com/v1/snapToRoads?interpolate=false` +
      `&path=${encodeURIComponent(path)}&key=${apiKey()}`,
  );
  const body = (await res.json()) as {
    snappedPoints?: { location: { latitude: number; longitude: number } }[];
    error?: { message?: string };
  };
  if (!res.ok || body.error) {
    const detail = body.error?.message ?? `HTTP ${res.status}`;
    logger.error("snapPath failed", detail);
    throw new HttpsError("unavailable", `Roads API menolak permintaan: ${detail}`);
  }

  const snapped = (body.snappedPoints ?? []).map((p) => ({
    lat: p.location.latitude,
    lng: p.location.longitude,
  }));
  if (snapped.length === 0) {
    // Off-road, or a road Google does not know. The caller keeps the raw position
    // rather than showing nothing — a marker slightly beside the road beats a
    // marker that vanishes.
    return { points: [] };
  }
  return { points: snapped };
});

/**
 * Plans the driving route through today's stops, along real roads.
 *
 * The route screen used to join the collector's position to each outlet with
 * straight lines, which shows the order but not the journey — a line across a
 * river tells a rider nothing about how to get there, and the distance it implies
 * is not the distance they will travel.
 *
 * Google's Routes API returns the road geometry, the driving distance and an
 * estimated duration, and will reorder the intermediate stops for the shortest
 * total trip. That last part replaces the app's own nearest-neighbour ordering,
 * which is a greedy heuristic that ignores one-way streets, turn restrictions and
 * the river.
 *
 * TWO_WHEELER is asked for first because collectors ride motorbikes and the road
 * network available to them is not the one a car uses; DRIVE is the fallback where
 * two-wheeler routing is not offered.
 *
 * Volume is small by construction — a plan is per collector per screen opening,
 * not per position — so this is nothing like the live snapping in cost.
 */
export const planRoute = onCall({ secrets: [roadsApiKey] }, async (request) => {
  if (!request.auth) {
    throw new HttpsError("unauthenticated", "Perlu login untuk merencanakan rute.");
  }

  const data = request.data as {
    origin?: { lat: number; lng: number };
    stops?: { lat: number; lng: number }[];
  };
  const origin = data?.origin;
  const stops = (data?.stops ?? []).filter((p) => Number.isFinite(p?.lat) && Number.isFinite(p?.lng));
  if (!origin || !Number.isFinite(origin.lat) || stops.length === 0) {
    throw new HttpsError("invalid-argument", "Butuh posisi awal dan minimal satu tujuan.");
  }

  const waypoint = (p: { lat: number; lng: number }) => ({
    location: { latLng: { latitude: p.lat, longitude: p.lng } },
  });

  const call = async (travelMode: string) => {
    const res = await fetch("https://routes.googleapis.com/directions/v2:computeRoutes", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Goog-Api-Key": apiKey(),
        "X-Goog-FieldMask":
          "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline," +
          "routes.optimizedIntermediateWaypointIndex",
      },
      body: JSON.stringify({
        origin: waypoint(origin),
        destination: waypoint(stops[stops.length - 1]),
        intermediates: stops.slice(0, -1).map(waypoint),
        travelMode,
        // Without this the duration is a free-flow estimate — the time the trip
        // would take on empty roads, which in Jakarta is a number that describes
        // no hour of any working day. TRAFFIC_AWARE prices the route against
        // current conditions, and also changes which roads are chosen, not just
        // the arrival estimate.
        routingPreference: "TRAFFIC_AWARE",
        // Only meaningful with intermediates; harmless otherwise.
        optimizeWaypointOrder: stops.length > 2,
      }),
    });
    return { ok: res.ok, status: res.status, body: (await res.json()) as any };
  };

  let travelMode = "TWO_WHEELER";
  let result = await call(travelMode);
  if (!result.ok) {
    logger.warn("TWO_WHEELER ditolak, jatuh ke DRIVE", JSON.stringify(result.body?.error ?? {}).slice(0, 300));
    travelMode = "DRIVE";
    result = await call(travelMode);
  }

  if (!result.ok || result.body?.error) {
    const detail = result.body?.error?.message ?? `HTTP ${result.status}`;
    logger.error("computeRoutes failed", detail);
    throw new HttpsError("unavailable", `Routes API menolak permintaan: ${detail}`);
  }

  const route = result.body?.routes?.[0];
  if (!route?.polyline?.encodedPolyline) {
    throw new HttpsError("unavailable", "Routes API tidak mengembalikan jalur.");
  }

  return {
    // Reported so the caller — and anyone debugging a route that looks like a car
    // took it — can see which network was actually used rather than assume.
    travelMode,
    polyline: route.polyline.encodedPolyline as string,
    distanceMeters: route.distanceMeters ?? 0,
    // Comes back as a string of seconds, e.g. "1245s".
    durationSeconds: Number(String(route.duration ?? "0s").replace("s", "")) || 0,
    // Present only when reordering was requested; the caller relabels its markers.
    order: (route.optimizedIntermediateWaypointIndex ?? []) as number[],
  };
});

/** Keeps points at least [spacingM] apart, always keeping the first and last. */
function thin(points: { lat: number; lng: number }[], spacingM: number) {
  const out = [points[0]];
  for (const p of points) {
    const last = out[out.length - 1];
    if (geoDistance(last.lat, last.lng, p.lat, p.lng) >= spacingM) out.push(p);
  }
  const final = points[points.length - 1];
  if (out[out.length - 1] !== final) out.push(final);
  return out;
}

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
