# Firebase Setup — Step by Step

Milestone 02 contains Firebase code, rules, and bootstrap tools, but it does **not** include your private `google-services.json` or service-account key.

## A. Create the Firebase project

1. Open Firebase Console and create a project, for example `collection-field-prod`.
2. Add an **Android app**.
3. Android package name must be exactly:

```text
com.collectionfield.app
```

4. Download `google-services.json`.
5. Copy it to:

```text
CollectionFieldApp/app/google-services.json
```

Do not rename it.

## B. Enable Authentication

Firebase Console → **Authentication** → **Sign-in method** → enable **Email/Password**.

The app still shows Employee ID + PIN. Internally:

```text
COL001 -> col001@collectionfield.app
```

PIN must currently be 6–12 digits.

## C. Create Firestore

Firebase Console → **Firestore Database** → Create database.

Choose the region closest to your operation. For the actual project, use the same region strategy consistently for related Firebase/Google Cloud resources.

The repository already contains:

```text
firestore.rules
firestore.indexes.json
```

## D. Create Realtime Database

Firebase Console → **Realtime Database** → Create database.

This stores only the latest collector live position under:

```text
/live_locations/{collectorUid}
```

## E. Enable Cloud Storage

Create the default Storage bucket now so the next milestone can add camera/photo proof without changing Firebase project structure.

The repository already contains `storage.rules`.

## F. Deploy Security Rules

Install Firebase CLI, login, then from the project root:

```bash
firebase login
firebase use --add
firebase deploy --only firestore:rules,firestore:indexes,database,storage
```

Select the Firebase project you created when `firebase use --add` asks.

## G. Easiest way to create one test collector manually

For the first test, you can use Firebase Console without Node.js tooling.

### 1. Authentication

Authentication → Users → Add user

```text
Email:    col001@collectionfield.app
Password: 123456
```

After creating it, copy the user's Firebase **UID**.

### 2. Firestore profile

Create document:

```text
users/{PASTE_UID_HERE}
```

Fields:

```text
uid           string   same Firebase UID
employeeCode  string   COL001
authEmail     string   col001@collectionfield.app
displayName   string   Budi Collector
role          string   COLLECTOR
branchId      string   JKT-01
active        boolean  true
```

Custom claims are not required for a collector to write/read their own data. They are needed later for supervisor/admin access to the Realtime Database dashboard.

## H. Create users with the included admin bootstrap tool

Recommended when you have multiple collectors.

Requirements:

- Node.js 22+
- a Firebase/Google service-account credential available via `GOOGLE_APPLICATION_CREDENTIALS`

From `tools/`:

```bash
npm install
cp users.example.json users.local.json
```

Edit `users.local.json`, then set the credential environment variable.

macOS/Linux:

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/secure/path/service-account.json"
npm run bootstrap-users -- ./users.local.json
```

Windows PowerShell:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\secure\service-account.json"
npm run bootstrap-users -- ./users.local.json
```

The script will:

1. create/update Firebase Authentication users;
2. set role custom claims;
3. create/update `users/{uid}` profiles in Firestore.

Never commit the service-account key or `users.local.json`.

## I. Seed test outlets

```bash
cp outlets.example.json outlets.local.json
npm run bootstrap-outlets -- ./outlets.local.json
```

Outlets are stored as `outlets/{outletId}` with a Firestore `GeoPoint`.

## J. Run Android app

After `app/google-services.json` exists:

1. reopen/sync the project in Android Studio;
2. run the app on a physical Android phone;
3. Login:

```text
Employee ID: COL001
PIN:         123456
```

Expected login flow:

```text
Employee ID + PIN
      ↓
Firebase Authentication
      ↓
Firestore users/{uid}
      ↓
active + employeeCode + role validation
      ↓
local session
      ↓
HOME
```

## K. Verify cloud sync

Start a shift and move the phone.

Verify Firestore:

```text
shifts/{shiftId}
shifts/{shiftId}/telemetry/{pointId}
```

Verify Realtime Database:

```text
live_locations/{collectorUid}
```

The local Room queue should gradually return toward `0 pending` while internet is available.
