# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users
Field collectors who perform site visits to debtors. They work in various environments, potentially with unstable internet, and need a reliable way to track their location and record visit evidence.

## Product Purpose
To monitor and track collector activities in the field, ensuring accurate location data and verified visit reports (including photos and status updates) for the management.

## Positioning
An integrated field collection management system with real-time tracking and Firestore/Cloud SQL synchronization, providing a seamless bridge between field actions and the administrative dashboard.

## Operating Context
Field visits, GPS tracking, photo capture, debtor status updates. The app is used on Android mobile devices during work shifts.

## Capabilities and Constraints
- Real-time location tracking (Firestore).
- Offline-first data capture with background synchronization.
- Photo capture and compression for upload to Cloud Storage.
- Firebase Authentication (Employee ID + PIN).
- Role-based access (Collector, Supervisor, Admin).

## Brand Commitments
- Name: Collection Field
- Voice: Professional, efficient, and reliable.
- Language: Primarily Indonesian (target audience).

## Product Principles
- **Reliability First**: Data must be captured even in poor signal areas.
- **Real-time Visibility**: Management should see live locations.
- **Efficiency**: Minimize data usage (photo compression) and maximize battery life.
- **Ease of Use**: Simple, focused UI for field staff who may be in a hurry.
