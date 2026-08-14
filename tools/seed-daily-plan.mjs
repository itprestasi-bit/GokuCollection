import { applicationDefault, initializeApp } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

// Initialize with environment variables set in previous steps
initializeApp({ credential: applicationDefault() });

const db = getFirestore();
const employeeCode = 'COL001';
const today = new Date().toISOString().split('T')[0]; // 2026-08-13

const outlets = [
  {
    outlet_id: 'OUT-001',
    nama_outlet: 'Toko Makmur (Testing)',
    alamat: 'Jl. Thamrin No. 1, Jakarta',
    latitude: -6.1935,
    longitude: 106.8235,
    total_piutang: 5400000,
    jatuh_tempo: '2026-08-10',
  },
  {
    outlet_id: 'OUT-002',
    nama_outlet: 'Toko Sejahtera (Testing)',
    alamat: 'Jl. Sudirman No. 45, Jakarta',
    latitude: -6.2100,
    longitude: 106.8200,
    total_piutang: 1250000,
    jatuh_tempo: '2026-08-20',
  },
  {
    outlet_id: 'OUT-003',
    nama_outlet: 'CV Sumber Rejeki (Testing)',
    alamat: 'Kuningan City, Jakarta',
    latitude: -6.2245,
    longitude: 106.8290,
    total_piutang: 8900000,
    jatuh_tempo: '2026-08-12',
  },
  {
    outlet_id: 'OUT-004',
    nama_outlet: 'Toko Maju Jaya (Testing)',
    alamat: 'Menteng Central, Jakarta',
    latitude: -6.1850,
    longitude: 106.8320,
    total_piutang: 3200000,
    jatuh_tempo: '2026-08-15',
  },
  {
    outlet_id: 'OUT-005',
    nama_outlet: 'Family Mart Tanah Abang IV',
    alamat: 'Jl. Tanah Abang IV No. 1, Gambir, Jakarta Pusat',
    latitude: -6.1776,
    longitude: 106.8182,
    total_piutang: 2100000,
    jatuh_tempo: '2026-08-25',
  },
];

async function seed() {
  const userSnap = await db.collection('users').where('employee_code', '==', employeeCode).limit(1).get();
  if (userSnap.empty) {
    throw new Error(`User dengan employee_code ${employeeCode} belum ada — jalankan bootstrap-users.mjs dulu`);
  }
  const collectorUid = userSnap.docs[0].id;

  // Pastikan outlet master data ada (dengan radius geofence 30m default + info piutang)
  const batch = db.batch();
  for (const o of outlets) {
    batch.set(
      db.collection('outlets').doc(o.outlet_id),
      {
        code: o.outlet_id,
        name: o.nama_outlet,
        lat: o.latitude,
        lng: o.longitude,
        address: o.alamat,
        radius_m: 30,
        priority: 1,
        status: 'active',
        total_piutang: o.total_piutang,
        jatuh_tempo: o.jatuh_tempo,
        updated_at: FieldValue.serverTimestamp(),
      },
      { merge: true },
    );
  }
  await batch.commit();

  const docId = `${collectorUid}_${today}`;
  await db.collection('assignments').doc(docId).set({
    date: today,
    collector_id: employeeCode,
    collector_uid: collectorUid,
    outlet_ids: outlets.map((o) => o.outlet_id),
    planned_sequence: outlets.map((_, i) => i + 1),
    priority: {},
    target: outlets.length,
    updated_at: FieldValue.serverTimestamp(),
  });
  console.log(`Successfully seeded assignment with ${outlets.length} outlets for ${employeeCode} (${collectorUid}) on ${today}`);
}

seed().catch(console.error);
