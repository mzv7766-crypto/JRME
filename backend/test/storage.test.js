// Cloud Storage rules for company recordings – run with the emulators (npm test).
import { test, before, after, beforeEach } from 'node:test';
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc } from 'firebase/firestore';
import { ref, uploadBytes, getBytes, deleteObject } from 'firebase/storage';

let env;
const OWNER = 'boss@acme.co.il', GUARD = 'guard1@gmail.com', GUARD2 = 'guard2@gmail.com', STRANGER = 'x@gmail.com';
const ORG = 'acme';
const ctx = (email) => env.authenticatedContext(email, { email, email_verified: true });
const video = { contentType: 'video/mp4' };
const bytes = new Uint8Array([0, 0, 0, 24, 102, 116, 121, 112]);
const path = (email, id) => `shomer_orgs/${ORG}/rec/${email}/${id}.mp4`;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-shomer',
    firestore: { rules: readFileSync('firestore.rules', 'utf8') },
    storage: { rules: readFileSync('storage.rules', 'utf8') },
  });
});
after(async () => env && env.cleanup());
beforeEach(async () => {
  await env.clearFirestore();
  await env.clearStorage();
  await env.withSecurityRulesDisabled(async (c) => {
    const d = c.firestore();
    await setDoc(doc(d, 'shomer_orgs', ORG), { name: 'Acme', ownerEmail: OWNER, managers: [OWNER], seats: 5, memberCount: 2 });
    await setDoc(doc(d, 'shomer_orgs', ORG, 'members', GUARD), { name: 'A', badge: '1' });
    await setDoc(doc(d, 'shomer_orgs', ORG, 'members', GUARD2), { name: 'B', badge: '2' });
  });
});

test('a guard uploads only into his own folder, only videos, never overwrites', async () => {
  const s = ctx(GUARD).storage();
  await assertSucceeds(uploadBytes(ref(s, path(GUARD, 'r1')), bytes, video));
  await assertFails(uploadBytes(ref(s, path(GUARD, 'r1')), bytes, video));          // overwrite
  await assertFails(uploadBytes(ref(s, path(GUARD2, 'r2')), bytes, video));         // someone else's folder
  await assertFails(uploadBytes(ref(s, path(GUARD, 'r3')), bytes, { contentType: 'text/plain' }));
  await assertFails(uploadBytes(ref(ctx(STRANGER).storage(), path(STRANGER, 'r4')), bytes, video));
  await assertFails(deleteObject(ref(s, path(GUARD, 'r1'))));
});

test('the manager watches and deletes; other guards cannot watch', async () => {
  await assertSucceeds(uploadBytes(ref(ctx(GUARD).storage(), path(GUARD, 'r1')), bytes, video));
  await assertSucceeds(getBytes(ref(ctx(OWNER).storage(), path(GUARD, 'r1'))));
  await assertFails(getBytes(ref(ctx(GUARD2).storage(), path(GUARD, 'r1'))));
  await assertFails(getBytes(ref(ctx(STRANGER).storage(), path(GUARD, 'r1'))));
  await assertSucceeds(deleteObject(ref(ctx(OWNER).storage(), path(GUARD, 'r1'))));
});

test('a company without a plan cannot upload', async () => {
  await env.withSecurityRulesDisabled(async (c) => setDoc(doc(c.firestore(), 'shomer_orgs', ORG), { name: 'Acme', ownerEmail: OWNER, managers: [OWNER], seats: 0, memberCount: 2 }));
  await assertFails(uploadBytes(ref(ctx(GUARD).storage(), path(GUARD, 'r1')), bytes, video));
});
