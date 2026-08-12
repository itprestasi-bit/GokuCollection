# Next.js Admin Dashboard Preparation

Prepare a Next.js dashboard project that is pre-connected to the existing Firebase and Data Connect infrastructure.

## User Review Required

> [!IMPORTANT]
> The Next.js project will be created in a new `dashboard` directory at the root of your project.
> You will need to install Node.js dependencies (`npm install`) inside that directory once I finish.

## Proposed Changes

### [Firebase Data Connect]

#### [MODIFY] [connector.yaml](file:///Users/langg__/StudioProjects/GokuCollection/app/src/main/dataconnect/default/connector.yaml)
Add a `javascriptSdk` target so that Data Connect generates a Web SDK usable by Next.js.

### [Dashboard (Next.js)]

#### [NEW] [Dashboard Structure](file:///Users/langg__/StudioProjects/GokuCollection/dashboard/)
Initialize a Next.js project with:
- `firebaseConfig.ts`: Client-side initialization.
- `firebaseAdmin.ts`: Server-side (Admin SDK) initialization using your service account.
- `.env.local.example`: Template for required environment variables.
- `package.json`: Including `firebase`, `firebase-admin`, and `dataconnect` dependencies.

## Verification Plan

### Automated Tests
- Run `firebase dataconnect:sdk:generate` to ensure the Web SDK is generated without errors.
- Run a basic build check on the Next.js structure (if possible).

### Manual Verification
- User will run `npm install` and `npm run dev` in the `dashboard` folder.
- User will verify the dashboard can pull user profiles from Firestore.
