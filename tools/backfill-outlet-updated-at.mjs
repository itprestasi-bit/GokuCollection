import process from 'node:process';
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

/**
 * One-time backfill: give every outlet an `updated_at`.
 *
 * Delta sync keys off this field, and only 3 of 7,683 documents had one — the
 * rest came from a bulk import that never set it. Without the backfill a
 * `where("updated_at", ">", cursor)` query silently returns almost nothing,
 * which would look like "sync works and there are no changes" while actually
 * never delivering any outlet at all.
 *
 * Documents that already have the field are left alone: overwriting a real
 * modification time with "now" would throw away information.
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=... node backfill-outlet-updated-at.mjs
 */

initializeApp({ credential: applicationDefault() });
const db = getFirestore();

const BATCH = 450;

async function main() {
  const snap = await db.collection("outlets").get();
  console.log(`outlets dibaca: ${snap.size}`);

  const missing = snap.docs.filter((d) => d.data().updated_at == null);
  console.log(`belum punya updated_at: ${missing.length}`);
  console.log(`sudah punya (dilewati): ${snap.size - missing.length}`);

  if (missing.length === 0) {
    console.log("tidak ada yang perlu di-backfill.");
    return;
  }

  let done = 0;
  for (let i = 0; i < missing.length; i += BATCH) {
    const chunk = missing.slice(i, i + BATCH);
    const batch = db.batch();
    for (const d of chunk) {
      batch.update(d.ref, { updated_at: FieldValue.serverTimestamp() });
    }
    await batch.commit();
    done += chunk.length;
    console.log(`  commit: ${done}/${missing.length}`);
  }

  const after = (await db.collection("outlets").orderBy("updated_at").count().get()).data().count;
  console.log("=== HASIL ===");
  console.log(`di-backfill      : ${done}`);
  console.log(`punya updated_at : ${after} / ${snap.size}`);
}

main()
  .then(() => process.exit(0))
  .catch((e) => {
    console.error("ERROR:", e.code || "", e.message || e);
    process.exit(1);
  });
