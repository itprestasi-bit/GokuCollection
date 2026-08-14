# Milestone 01 Acceptance Checklist

## Foundation
- [x] Android project structure created.
- [x] Compose UI configured.
- [x] Local repository/data layers separated from UI.
- [x] Room schema contains shifts, telemetry, outlets and visits.

## Shift Tracking
- [x] User explicitly starts shift.
- [x] Foreground location service starts from the visible app.
- [x] Persistent notification identifies active tracking.
- [x] GPS points are captured into the local database.
- [x] Tracking mode changes between moving / slow / stopped after stable samples.
- [x] User can end shift and stop location updates.
- [x] Shift retains first and last coordinates.

## Offline
- [x] GPS capture does not depend on Firebase/network availability.
- [x] Every telemetry point has a unique client UUID.
- [x] Every point carries a sync state.
- [x] Pending data remains local until a real backend acknowledges it.

## Outlet foundation
- [x] Outlet master entity exists.
- [x] Seed outlets are shown in the app.
- [x] Distance from latest collector GPS to outlet is calculated locally.
- [ ] Outlet Detail / camera proof — Milestone 02.
- [ ] Manual IN/OUT — Milestone 02.

## Cloud / Admin
- [ ] Firebase Auth — Milestone 02.
- [ ] RTDB live position — Milestone 02.
- [ ] Firestore domain records — Milestone 02.
- [ ] Storage photo proof — Milestone 02.
- [ ] Admin dashboard — after Android proof-of-visit workflow.
