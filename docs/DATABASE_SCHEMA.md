# Database Schema — Milestone 02

The app is intentionally split across Room, Cloud Firestore, and Firebase Realtime Database.

## 1. Local Room database

Room is the offline-first source of truth on the collector device.

### `shifts`

| Field | Type | Purpose |
|---|---|---|
| id | String UUID | Shift identifier |
| collectorId | String | Business employee code |
| collectorUid | String | Firebase Auth UID |
| startedAt | epoch ms | Shift start |
| endedAt | epoch ms? | Shift end |
| status | ACTIVE / ENDED | Operational state |
| firstLat / firstLng | Double? | First GPS |
| lastLat / lastLng | Double? | Last GPS |
| syncStatus | PENDING / SYNCING / SYNCED / FAILED | Cloud sync state |

### `telemetry_points`

Raw GPS points captured by the foreground location service. Includes shift/user identifiers, coordinates, accuracy, speed, bearing, battery, network state, mock-location flag, device timestamp, server receive timestamp, and sync state.

### `outlets`

Offline copy of active master outlets so the app can still display the field list without internet.

### `visits`

Foundation for the next milestone: manual/automatic visit, arrival, departure, duration, result, method, confidence, notes, and sync state.

## 2. Cloud Firestore

### `users/{uid}`

```text
uid             string
employeeCode    string          e.g. COL001
authEmail       string          e.g. col001@collectionfield.app
displayName     string
role            string          COLLECTOR | SUPERVISOR | ADMIN
branchId        string|null
active          boolean
updatedAt       server timestamp
```

`users` is the business profile. Firebase Authentication remains the credential store.

### `outlets/{outletId}`

```text
code            string
name            string
location        GeoPoint
address         string
radiusM         number          default 75
priority        number          default 1
status          string          ACTIVE | INACTIVE
updatedAt       timestamp
```

### `shifts/{shiftId}`

```text
id              string
collectorUid    string
collectorId     string
startedAt       timestamp
endedAt         timestamp|null
status          ACTIVE | ENDED
firstLocation   GeoPoint|null
lastLocation    GeoPoint|null
updatedAt       server timestamp
```

### `shifts/{shiftId}/telemetry/{pointId}`

```text
id              string
shiftId         string
collectorUid    string
collectorId     string
location        GeoPoint
accuracyM       number
speedMps        number
bearing         number
capturedAt      timestamp       device capture time
batteryPct      number
networkState    string
mockFlag        boolean
receivedAt      server timestamp
```

This provides route-history/replay data for the MVP. At very large scale, raw GPS can later be moved to a cheaper telemetry ingestion/storage pipeline without changing the business collections.

### `assignments/{assignmentId}` — schema reserved

```text
collectorUid    string
collectorId     string
dateKey         string          YYYY-MM-DD
outletIds       array<string>
status          string
createdAt       timestamp
updatedAt       timestamp
```

### `visits/{visitId}`

```text
id              string
shiftId         string
collectorUid    string
collectorId     string
outletId        string
arrivalAt       timestamp
departureAt     timestamp|null
durationSec     number|null
result          string|null
method          MANUAL | AUTO
confidence      number
notes           string|null
updatedAt       server timestamp
```

### `visit_photos/{photoId}` — schema reserved

Metadata only. Binary image is stored in Cloud Storage.

```text
collectorUid    string
visitId         string
outletId        string
storagePath     string
capturedAt      timestamp
location        GeoPoint
hash            string|null
```

### `alerts/{alertId}` — schema reserved

```text
collectorUid    string
shiftId         string|null
type            LONG_STOP | GPS_OFF | OFFLINE | LOCATION_ANOMALY | SHORT_VISIT
severity        INFO | WARNING | CRITICAL
status          OPEN | ACKNOWLEDGED | RESOLVED
createdAt       timestamp
payload         map
```

## 3. Firebase Realtime Database

Realtime Database stores only the latest live state per collector.

### `/live_locations/{collectorUid}`

```text
collectorUid
collectorId
shiftId
lat
lng
accuracyM
speedMps
bearing
capturedAt
batteryPct
networkState
mockFlag
status            ON_SHIFT | OFF_SHIFT
serverUpdatedAt
shiftEndedAt      optional
```

The path is overwritten with the newest location. It is intentionally not used as the historical route store.

## 4. Cloud Storage

Reserved path for Milestone 03:

```text
/visit_photos/{collectorUid}/{visitId}/{filename}.jpg
```

Storage rules restrict collectors to their own folder and image content under 10 MB.

## 5. Identity model

Collector UI uses:

```text
Employee ID: COL001
PIN:         123456
```

Firebase Authentication receives the internal email:

```text
col001@collectionfield.app
```

The collector never needs to know that internal email. This lets V1 keep the business-friendly Employee ID + PIN flow while using Firebase password authentication.

For production, PIN policy can later be strengthened or replaced with a server-side custom-auth flow without changing the rest of the app architecture.
