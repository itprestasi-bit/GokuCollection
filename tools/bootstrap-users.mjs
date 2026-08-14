import fs from 'node:fs';
import process from 'node:process';
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

initializeApp({ credential: applicationDefault() });

const auth = getAuth();
const db = getFirestore();
const file = process.argv[2] ?? './users.local.json';
const users = JSON.parse(fs.readFileSync(file, 'utf8'));

const normalize = (value) => value.trim().toUpperCase().replaceAll(' ', '');
const emailFor = (employeeCode) => `${normalize(employeeCode).toLowerCase()}@collectionfield.app`;

// NOTE: this also sets the custom claim directly so accounts work even before the
// `syncUserRoleClaims` Cloud Function is deployed. Once deployed, that function keeps
// claims in sync automatically whenever `role`/`active` change (e.g. edited from the
// dashboard), so this script no longer needs to be re-run just to change someone's role.
for (const input of users) {
  const employeeCode = normalize(input.employeeCode);
  const role = String(input.role ?? 'collector').toLowerCase();
  if (!/^[A-Z0-9_-]{2,32}$/.test(employeeCode)) {
    throw new Error(`${employeeCode}: format Employee ID tidak valid`);
  }
  if (!/^\d{6,12}$/.test(String(input.pin))) {
    throw new Error(`${employeeCode}: PIN harus 6-12 digit angka`);
  }
  if (!['collector', 'supervisor', 'admin', 'management'].includes(role)) {
    throw new Error(`${employeeCode}: role tidak valid`);
  }

  const email = emailFor(employeeCode);
  let user;
  try {
    user = await auth.getUserByEmail(email);
    user = await auth.updateUser(user.uid, {
      password: String(input.pin),
      displayName: input.displayName ?? employeeCode,
      disabled: input.active === false,
    });
    console.log(`UPDATE AUTH ${employeeCode} -> ${user.uid}`);
  } catch (error) {
    if (error.code !== 'auth/user-not-found') throw error;
    user = await auth.createUser({
      email,
      password: String(input.pin),
      displayName: input.displayName ?? employeeCode,
      disabled: input.active === false,
    });
    console.log(`CREATE AUTH ${employeeCode} -> ${user.uid}`);
  }

  await auth.setCustomUserClaims(user.uid, { role, active: input.active !== false });
  await db.collection('users').doc(user.uid).set({
    employee_code: employeeCode,
    email,
    name: input.displayName ?? employeeCode,
    role,
    branch_id: input.branchId ?? null,
    device_id: null,
    active: input.active !== false,
    updated_at: FieldValue.serverTimestamp(),
  }, { merge: true });

  console.log(`SYNC PROFILE ${employeeCode} (${role})`);
}

console.log(`Done: ${users.length} user(s)`);
