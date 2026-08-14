# Milestone 02 Acceptance Checklist

## Firebase configuration

- [ ] `app/google-services.json` added locally.
- [ ] Login screen shows `Firebase: READY`.
- [ ] Firebase Authentication Email/Password enabled.
- [ ] Firestore created.
- [ ] Realtime Database created.
- [ ] Storage created.
- [ ] Firebase rules deployed.

## Authentication

- [ ] `COL001 / 123456` authenticates when matching Firebase account/profile exists.
- [ ] Invalid PIN does not open Home.
- [ ] Disabled/inactive Firestore profile is rejected.
- [ ] Missing Firestore `users/{uid}` profile is rejected.
- [ ] App restart restores an authenticated session.
- [ ] Logout clears Firebase + local session.
- [ ] Logout stays disabled during an active shift.

## Database / offline-first

- [ ] Existing Milestone 01 Room database migrates from schema 1 → 2 without destructive reset.
- [ ] New shift stores Firebase UID + employee code locally.
- [ ] GPS points store Firebase UID + employee code locally.
- [ ] Active outlets can be downloaded from Firestore into Room.

## Cloud sync

- [ ] START SHIFT creates/syncs `shifts/{shiftId}`.
- [ ] GPS remains written to Room first.
- [ ] Online GPS sync creates `shifts/{shiftId}/telemetry/{pointId}`.
- [ ] Latest GPS overwrites `live_locations/{collectorUid}` in RTDB.
- [ ] Network loss keeps points pending locally.
- [ ] Network recovery retries pending data.
- [ ] END SHIFT syncs shift status to `ENDED`.
- [ ] RTDB live location status becomes `OFF_SHIFT` after END SHIFT sync.

## Security smoke test

- [ ] Collector can read own `users/{uid}`.
- [ ] Collector cannot modify `users/{uid}`.
- [ ] Collector can read active outlets.
- [ ] Collector can write only shifts/telemetry with their own UID.
- [ ] Collector can write only their own RTDB live-location path.
- [ ] Admin/Supervisor RTDB read requires custom role claim.

## Not part of Milestone 02

- Camera/photo capture UI.
- Manual visit check-in/check-out UI.
- Auto geofence IN/OUT.
- Stop detection engine.
- Admin web dashboard.
- Route optimization.
