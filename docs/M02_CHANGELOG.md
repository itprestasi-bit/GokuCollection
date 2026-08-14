# Milestone 02 Changelog

## Android

- Version bumped to `0.2.0` / versionCode `2`.
- Added Firebase Android BoM, Auth, Firestore, Realtime Database, Storage.
- Added conditional Google Services Gradle plugin support.
- Replaced local fake login with Firebase Authentication.
- Added Employee ID → internal Firebase email mapping.
- Added Firestore `users/{uid}` profile validation.
- Added persisted role-aware collector session.
- Added Room migration 1 → 2.
- Added Firebase UID to shifts, telemetry, visits.
- Added shift sync state.
- Added Firestore outlet-master refresh.
- Activated WorkManager sync worker.
- Added Firestore shift + route telemetry writes.
- Added RTDB live collector location writes.
- Added OFF_SHIFT update when shift ends.
- Updated Home UI cloud/session text.

## Firebase project files

- `firebase.json`
- `firestore.rules`
- `firestore.indexes.json`
- `database.rules.json`
- `storage.rules`

## Admin tooling

- `tools/bootstrap-users.mjs`
- `tools/bootstrap-outlets.mjs`
- test user/outlet JSON templates

## Documentation

- Firebase setup walkthrough.
- Database schema.
- Milestone 02 acceptance checklist.
