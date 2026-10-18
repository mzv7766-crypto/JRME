// Direct (rules-bypassing) access to the Firestore emulator, for seeding and checking.
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, addDoc, collection, getDocs } from 'firebase/firestore';

const env = await initializeTestEnvironment({ projectId: 'demo-shomer', firestore: { host: '127.0.0.1', port: 8080 } });
export async function admin(fn) { let r; await env.withSecurityRulesDisabled(async (c) => { r = await fn(c.firestore()); }); return r; }
export const ORG = 'acme', BOSS = 'boss@acme.co.il', GUARD = 'guard@acme.co.il';

export async function seed() {
  await admin(async (db) => {
    await setDoc(doc(db, 'shomer_orgs', ORG), {
      name: 'אבטחת השרון', ownerEmail: BOSS, managers: [BOSS], seats: 5, memberCount: 1,
      plan: { source: 'admin' }, policy: { preRecordSeconds: 30, recordAudio: true, fallDetection: true, autoLock: true, allowDiscard: false, allowDelete: false },
    });
    await setDoc(doc(db, 'shomer_index', BOSS), { orgId: ORG, role: 'manager' });
    await setDoc(doc(db, 'shomer_index', GUARD), { orgId: ORG, role: 'member' });
    await setDoc(doc(db, 'shomer_orgs', ORG, 'members', GUARD), { name: 'דנה לוי', badge: '4127' });
    for (const [i, n] of [['g2@acme.co.il', 'יוסי כהן'], ['g3@acme.co.il', 'אבי מזרחי']].entries()) {
      await setDoc(doc(db, 'shomer_orgs', ORG, 'members', n[0]), { name: n[1], badge: String(5100 + i) });
    }
    // two more guards on the map (simulated phones) so the map looks like a real shift
    const now = Date.now();
    await setDoc(doc(db, 'shomer_orgs', ORG, 'devices', 'g2@acme.co.il'), { onShift: true, recording: false, battery: 64, lat: 32.0880, lon: 34.7790, updatedAt: now + 3600_000 });
    await setDoc(doc(db, 'shomer_orgs', ORG, 'devices', 'g3@acme.co.il'), { onShift: true, recording: false, battery: 17, lat: 32.0812, lon: 34.7865, updatedAt: now + 3600_000 });
  });
}

export const device = (email = GUARD) => admin(async (db) => (await getDoc(doc(db, 'shomer_orgs', ORG, 'devices', email))).data() || null);
export const events = () => admin(async (db) => (await getDocs(collection(db, 'shomer_orgs', ORG, 'events'))).docs.map((d) => d.data()));
export const recordings = () => admin(async (db) => (await getDocs(collection(db, 'shomer_orgs', ORG, 'recordings'))).docs.map((d) => ({ id: d.id, ...d.data() })));
export const fakeFall = () => admin(async (db) => addDoc(collection(db, 'shomer_orgs', ORG, 'events'), { type: 'fall', email: 'g3@acme.co.il', name: 'אבי מזרחי 5101', at: Date.now(), lat: 32.0812, lon: 34.7865, detail: '3.4g' }));

const cmd = process.argv[2];
if (cmd === 'seed') { await seed(); console.log('seeded'); process.exit(0); }
if (cmd === 'device') { console.log(JSON.stringify(await device(process.argv[3]))); process.exit(0); }
if (cmd === 'events') { console.log(JSON.stringify(await events())); process.exit(0); }
if (cmd === 'fall') { await fakeFall(); process.exit(0); }
if (cmd === 'wait-shift') {
  for (let i = 0; i < 60; i++) { const d = await device(); if (d?.onShift) { console.log(JSON.stringify(d)); process.exit(0); } await new Promise((r) => setTimeout(r, 3000)); }
  console.log('timeout'); process.exit(1);
}
