import fs from 'node:fs';
import process from 'node:process';
import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

initializeApp({ credential: applicationDefault() });
const db = getFirestore();
const file = process.argv[2] ?? './outlets.local.json';
const outlets = JSON.parse(fs.readFileSync(file, 'utf8'));

for (const input of outlets) {
  if (!input.id || !input.code || !input.name) throw new Error('Outlet id/code/name wajib diisi');
  if (!Number.isFinite(input.lat) || !Number.isFinite(input.lng)) throw new Error(`${input.id}: lat/lng tidak valid`);

  await db.collection('outlets').doc(input.id).set({
    code: String(input.code).trim().toUpperCase(),
    name: String(input.name).trim(),
    lat: input.lat,
    lng: input.lng,
    address: input.address ?? '',
    radius_m: Number(input.radiusM ?? 30),
    priority: Number(input.priority ?? 1),
    status: String(input.status ?? 'active').toLowerCase(),
    total_piutang: Number(input.totalPiutang ?? 0),
    jatuh_tempo: input.jatuhTempo ?? null,
    updated_at: FieldValue.serverTimestamp(),
  }, { merge: true });

  console.log(`UPSERT OUTLET ${input.id} • ${input.name}`);
}

console.log(`Done: ${outlets.length} outlet(s)`);
