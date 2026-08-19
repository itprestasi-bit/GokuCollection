/**
 * Collapses the duplicate alerts left behind by the old five-minute dedupe window.
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=... node collapse-duplicate-alerts.mjs [--apply]
 *
 * One standing condition should be one alert. Where the same open type exists many
 * times over for the same shift, the newest is kept and given the occurrence count
 * it absorbed, and the rest are marked resolved with a note saying why. Nothing is
 * deleted: the records stay, they just stop shouting.
 *
 * Runs as a dry run unless --apply is passed.
 */
import process from 'node:process';
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

const apply = process.argv.includes('--apply');
initializeApp({ credential: applicationDefault() });
const db = getFirestore();

const snap = await db.collection('alerts').where('status', '==', 'open').get();
console.log(`alert terbuka: ${snap.size}`);

const groups = new Map();
for (const doc of snap.docs) {
  const d = doc.data();
  const key = `${d.type}|${d.shift_id ?? d.collector_uid ?? 'lain'}`;
  if (!groups.has(key)) groups.set(key, []);
  groups.get(key).push(doc);
}

let collapsed = 0;
let kept = 0;
for (const [key, docs] of groups) {
  docs.sort((a, b) => (b.data().created_at?.toMillis() ?? 0) - (a.data().created_at?.toMillis() ?? 0));
  const [survivor, ...duplicates] = docs;
  kept++;
  if (duplicates.length === 0) continue;

  console.log(`  ${key}: simpan 1, tutup ${duplicates.length}`);
  collapsed += duplicates.length;
  if (!apply) continue;

  await survivor.ref.update({
    occurrences: docs.length,
    last_seen_at: survivor.data().created_at ?? FieldValue.serverTimestamp(),
  });
  for (const batchStart of [...Array(Math.ceil(duplicates.length / 400)).keys()]) {
    const batch = db.batch();
    for (const dup of duplicates.slice(batchStart * 400, batchStart * 400 + 400)) {
      batch.update(dup.ref, {
        status: 'resolved',
        resolution_note: 'Digabung otomatis — kondisi yang sama sudah tercatat pada satu alert.',
        resolved_at: FieldValue.serverTimestamp(),
      });
    }
    await batch.commit();
  }
}

console.log(`\n${apply ? 'DITERAPKAN' : 'UJI COBA'}: ${kept} alert dipertahankan, ${collapsed} digabung.`);
if (!apply) console.log('Jalankan lagi dengan --apply untuk menerapkan.');
process.exit(0);
