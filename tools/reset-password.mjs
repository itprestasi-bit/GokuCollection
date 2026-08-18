/**
 * Resets the password of one existing account.
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=./gokuproject-...-adminsdk.json \
 *   NEW_PASSWORD='...' node reset-password.mjs <email-atau-kode-pegawai>
 *
 * The password comes from the environment rather than argv so it does not sit in
 * shell history or in the process list for every other user of the machine.
 *
 * Collector accounts use the synthetic address <kode>@collectionfield.app, so
 * either `COL002` or the full address is accepted. This only ever *updates* an
 * account that already exists — creating one is bootstrap-users.mjs's job, and
 * failing loudly here means a typo can never silently mint a new login.
 */
import process from 'node:process';
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { getAuth } from 'firebase-admin/auth';

const target = process.argv[2];
const password = process.env.NEW_PASSWORD;

if (!target) {
  console.error('Pakai: NEW_PASSWORD=... node reset-password.mjs <email|kode-pegawai>');
  process.exit(1);
}
if (!password || password.length < 6) {
  console.error('NEW_PASSWORD wajib diisi, minimal 6 karakter.');
  process.exit(1);
}

const email = target.includes('@')
  ? target.trim().toLowerCase()
  : `${target.trim().toLowerCase().replaceAll(' ', '')}@collectionfield.app`;

initializeApp({ credential: applicationDefault() });
const auth = getAuth();

const user = await auth.getUserByEmail(email).catch((error) => {
  if (error.code === 'auth/user-not-found') {
    console.error(`Akun ${email} tidak ada. Tidak ada yang diubah.`);
    process.exit(2);
  }
  throw error;
});

await auth.updateUser(user.uid, { password });

// Existing sessions keep working off an unexpired ID token otherwise, which
// defeats the point when the reason for the reset is a leaked password.
await auth.revokeRefreshTokens(user.uid);

console.log(`OK  ${email}`);
console.log(`    uid       : ${user.uid}`);
console.log(`    nama      : ${user.displayName ?? '(kosong)'}`);
console.log(`    role klaim: ${JSON.stringify(user.customClaims ?? {})}`);
console.log('    password diganti, semua sesi lama dipaksa login ulang.');
process.exit(0);
