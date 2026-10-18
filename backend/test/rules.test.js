// Firestore security rules for Shomer Team – run with the emulator (npm test).
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, updateDoc, deleteDoc, writeBatch, increment, collection, addDoc, getDocs, query, where } from 'firebase/firestore';

let env;
const OWNER = 'boss@acme.co.il', GUARD = 'guard1@gmail.com', GUARD2 = 'guard2@gmail.com', STRANGER = 'x@gmail.com', ADMIN = 'mzv7766@gmail.com';
const db = (email) => env.authenticatedContext(email, { email, email_verified: true }).firestore();
const ORG = 'acme';

before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-shomer', firestore: { rules: readFileSync('firestore.rules', 'utf8') } });
});
after(async () => env && env.cleanup());
beforeEach(async () => env.clearFirestore());

async function createOrg(seats = 0) {
  const d = db(OWNER);
  const b = writeBatch(d);
  b.set(doc(d, 'shomer_orgs', ORG), { name: 'Acme Security', ownerEmail: OWNER, managers: [OWNER], seats: 0, memberCount: 0, policy: {} });
  b.set(doc(d, 'shomer_index', OWNER), { orgId: ORG, role: 'manager' });
  await b.commit();
  if (seats) await env.withSecurityRulesDisabled(async (c) => updateDoc(doc(c.firestore(), 'shomer_orgs', ORG), { seats }));
}

async function addMember(email, as = OWNER) {
  const d = db(as);
  const b = writeBatch(d);
  b.set(doc(d, 'shomer_orgs', ORG, 'members', email), { name: 'Guard', badge: '4127', addedBy: as });
  b.update(doc(d, 'shomer_orgs', ORG), { memberCount: increment(1) });
  b.set(doc(d, 'shomer_index', email), { orgId: ORG, role: 'member' });
  return b.commit();
}

test('anyone signed in can open a company; seats start at 0', async () => {
  await assertSucceeds(createOrg());
  const d = db(STRANGER);
  await assertFails(setDoc(doc(d, 'shomer_orgs', 'evil'), { name: 'x', ownerEmail: STRANGER, managers: [STRANGER], seats: 50, memberCount: 0 }));
  await assertFails(setDoc(doc(d, 'shomer_orgs', 'evil2'), { name: 'x', ownerEmail: OWNER, managers: [OWNER], seats: 0, memberCount: 0 }));
});

test('unverified e-mail is not accepted', async () => {
  const d = env.authenticatedContext('u', { email: OWNER, email_verified: false }).firestore();
  await assertFails(setDoc(doc(d, 'shomer_orgs', 'o2'), { name: 'x', ownerEmail: OWNER, managers: [OWNER], seats: 0, memberCount: 0 }));
});

test('members can be added only up to the number of paid devices', async () => {
  await createOrg(1);
  await assertSucceeds(addMember(GUARD));
  await assertFails(addMember(GUARD2));
});

test('no seats → cannot add anyone', async () => {
  await createOrg(0);
  await assertFails(addMember(GUARD));
});

test('a manager cannot give himself seats – only the server (after Google Play) or the admin', async () => {
  await createOrg();
  const d = db(OWNER);
  await assertFails(updateDoc(doc(d, 'shomer_orgs', ORG), { seats: 1000 }));
  await assertFails(updateDoc(doc(d, 'shomer_orgs', ORG), { seats: 7, plan: { source: 'play', basePlan: 'team-7' } }));
  await assertFails(updateDoc(doc(d, 'shomer_orgs', ORG), { seats: 10, plan: { source: 'play', basePlan: 'team-10', token: 't' } }));
  await assertFails(updateDoc(doc(d, 'shomer_orgs', ORG), { plan: { source: 'play', basePlan: 'team-10', token: 't' } }));
  // other settings still work
  await assertSucceeds(updateDoc(doc(d, 'shomer_orgs', ORG), { policy: { autoLock: true } }));
});

test('the admin can grant seats to any company', async () => {
  await createOrg();
  await assertSucceeds(updateDoc(doc(db(ADMIN), 'shomer_orgs', ORG), { seats: 3, plan: { source: 'admin' } }));
  await assertSucceeds(getDocs(collection(db(ADMIN), 'shomer_orgs')));
  await assertFails(getDocs(collection(db(OWNER), 'shomer_orgs')));
});

test('the owner cannot be removed from the managers', async () => {
  await createOrg();
  await assertFails(updateDoc(doc(db(OWNER), 'shomer_orgs', ORG), { managers: ['other@x.com'] }));
  await assertSucceeds(updateDoc(doc(db(OWNER), 'shomer_orgs', ORG), { managers: [OWNER, 'deputy@x.com'] }));
});

test('a guard finds his company and writes only his own status', async () => {
  await createOrg(5);
  await addMember(GUARD);
  const g = db(GUARD);
  const idx = await assertSucceeds(getDoc(doc(g, 'shomer_index', GUARD)));
  if (idx.data().orgId !== ORG) throw new Error('wrong org');
  await assertSucceeds(getDoc(doc(g, 'shomer_orgs', ORG)));
  await assertSucceeds(setDoc(doc(g, 'shomer_orgs', ORG, 'devices', GUARD), { onShift: true, lat: 32.1, lon: 34.8 }));
  await assertFails(setDoc(doc(g, 'shomer_orgs', ORG, 'devices', 'other@gmail.com'), { onShift: true }));
  await assertFails(getDoc(doc(g, 'shomer_orgs', ORG, 'members', 'other@gmail.com')));
  await assertFails(getDocs(collection(g, 'shomer_orgs', ORG, 'events')));
});

test('only managers send commands; the guard can read his own', async () => {
  await createOrg(5);
  await addMember(GUARD);
  await assertSucceeds(setDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'cmd', GUARD), { id: 'c1', type: 'start' }));
  await assertSucceeds(getDoc(doc(db(GUARD), 'shomer_orgs', ORG, 'cmd', GUARD)));
  await assertFails(setDoc(doc(db(GUARD), 'shomer_orgs', ORG, 'cmd', GUARD), { id: 'c2', type: 'stop' }));
});

test('strangers see nothing', async () => {
  await createOrg(5);
  await addMember(GUARD);
  const s = db(STRANGER);
  await assertFails(getDoc(doc(s, 'shomer_orgs', ORG)));
  await assertFails(getDoc(doc(s, 'shomer_orgs', ORG, 'devices', GUARD)));
  await assertFails(getDoc(doc(s, 'shomer_orgs', ORG, 'live', GUARD)));
  await assertFails(getDoc(doc(s, 'shomer_index', GUARD)));
  await assertFails(setDoc(doc(s, 'shomer_index', STRANGER), { orgId: ORG, role: 'manager' }));
  await assertFails(setDoc(doc(s, 'shomer_index', STRANGER), { orgId: ORG, role: 'member' }));
});

test('live video signalling between the manager and that guard only', async () => {
  await createOrg(5);
  await addMember(GUARD);
  await addMember(GUARD2);
  await assertSucceeds(setDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'live', GUARD), { state: 'request', offer: { type: 'offer', sdp: 'x' } }));
  await assertSucceeds(updateDoc(doc(db(GUARD), 'shomer_orgs', ORG, 'live', GUARD), { state: 'answer', answer: { type: 'answer', sdp: 'y' } }));
  await assertSucceeds(addDoc(collection(db(GUARD), 'shomer_orgs', ORG, 'live', GUARD, 'dc'), { candidate: 'c' }));
  await assertFails(getDoc(doc(db(GUARD2), 'shomer_orgs', ORG, 'live', GUARD)));
});

test('events: guards append their own, managers read and acknowledge, nobody deletes', async () => {
  await createOrg(5);
  await addMember(GUARD);
  const ref = await assertSucceeds(addDoc(collection(db(GUARD), 'shomer_orgs', ORG, 'events'), { type: 'fall', email: GUARD }));
  await assertFails(addDoc(collection(db(GUARD), 'shomer_orgs', ORG, 'events'), { type: 'fall', email: GUARD2 }));
  await assertSucceeds(updateDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'events', ref.id), { ackBy: OWNER, ackAt: 1 }));
  await assertFails(updateDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'events', ref.id), { type: 'nothing' }));
  await assertFails(deleteDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'events', ref.id)));
});

test('removing a guard frees the seat', async () => {
  await createOrg(1);
  await addMember(GUARD);
  const d = db(OWNER);
  const b = writeBatch(d);
  b.delete(doc(d, 'shomer_orgs', ORG, 'members', GUARD));
  b.update(doc(d, 'shomer_orgs', ORG), { memberCount: increment(-1) });
  b.delete(doc(d, 'shomer_index', GUARD));
  await assertSucceeds(b.commit());
  await assertSucceeds(addMember(GUARD2));
});

test('a guard already in another company cannot be taken over', async () => {
  await createOrg(5);
  await addMember(GUARD);
  // second company
  const o = db(STRANGER);
  const b = writeBatch(o);
  b.set(doc(o, 'shomer_orgs', 'other'), { name: 'Other', ownerEmail: STRANGER, managers: [STRANGER], seats: 0, memberCount: 0 });
  b.set(doc(o, 'shomer_index', STRANGER), { orgId: 'other', role: 'manager' });
  await b.commit();
  await assertFails(setDoc(doc(o, 'shomer_index', GUARD), { orgId: 'other', role: 'member' }));
});

test('Jeremy free accounts keep working', async () => {
  await assertSucceeds(setDoc(doc(db(ADMIN), 'freeAccounts', 'friend@gmail.com'), { by: ADMIN }));
  await assertSucceeds(getDoc(doc(db('friend@gmail.com'), 'freeAccounts', 'friend@gmail.com')));
  await assertFails(getDoc(doc(db(STRANGER), 'freeAccounts', 'friend@gmail.com')));
  await assertFails(setDoc(doc(db(STRANGER), 'freeAccounts', STRANGER), {}));
});

test('company recordings: a guard lists only his own, the manager sees them, kept ones survive clean-up', async () => {
  await createOrg(5);
  await addMember(GUARD);
  await addMember(GUARD2);
  const g = db(GUARD);
  const rec = (email, extra = {}) => ({ email, driveId: 'drv123', size: 10, uploadedAt: 1, ...extra });
  await assertSucceeds(setDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r1'), rec(GUARD)));
  await assertFails(setDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r2'), rec(GUARD2)));
  await assertFails(setDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r3'), { email: GUARD }));
  await assertFails(setDoc(doc(db(STRANGER), 'shomer_orgs', ORG, 'recordings', 'r4'), rec(STRANGER)));
  await assertSucceeds(getDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r1')));
  await assertFails(getDoc(doc(db(GUARD2), 'shomer_orgs', ORG, 'recordings', 'r1')));
  await assertSucceeds(getDocs(query(collection(g, 'shomer_orgs', ORG, 'recordings'), where('email', '==', GUARD))));   // phone clean-up query
  await assertFails(getDocs(collection(g, 'shomer_orgs', ORG, 'recordings')));
  await assertSucceeds(getDocs(collection(db(OWNER), 'shomer_orgs', ORG, 'recordings')));
  await assertFails(updateDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r1'), { keep: true }));
  await assertSucceeds(updateDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'recordings', 'r1'), { keep: true }));
  await assertFails(deleteDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r1')));          // kept by the manager
  await assertSucceeds(updateDoc(doc(db(OWNER), 'shomer_orgs', ORG, 'recordings', 'r1'), { keep: false }));
  await assertSucceeds(deleteDoc(doc(g, 'shomer_orgs', ORG, 'recordings', 'r1')));       // retention clean-up
});
