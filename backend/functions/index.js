// Shomer server side (Firebase Cloud Functions, 2nd gen).
//
//  verifyTeam      – the app sends the Google Play purchase token of a company plan; the server asks Google Play
//                    whether it is really paid and only then gives the company its devices (seats).
//  recheckPlans    – every 6 hours: renewals, cancellations and refunds update the company's seats.
//  cleanupRecordings – every night: company cloud recordings older than the company's retention are deleted
//                    (unless a manager marked them "keep").
//
// Deploy: see backend/deploy.sh (one line in Google Cloud Shell).
import { onCall, HttpsError } from 'firebase-functions/v2/https';
import { onSchedule } from 'firebase-functions/v2/scheduler';
import { setGlobalOptions, logger } from 'firebase-functions/v2';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { getStorage } from 'firebase-admin/storage';
import { google } from 'googleapis';
import { PACKAGE, sha256, decide } from './plan.js';

setGlobalOptions({ region: 'us-central1', maxInstances: 5 });
initializeApp();
const db = getFirestore();

const ADMIN = 'mzv7766@gmail.com';
const DEFAULT_RETENTION_DAYS = 30;

let publisher;
async function playSubscription(token) {
  if (!publisher) {
    const auth = new google.auth.GoogleAuth({ scopes: ['https://www.googleapis.com/auth/androidpublisher'] });
    publisher = google.androidpublisher({ version: 'v3', auth });
  }
  const r = await publisher.purchases.subscriptionsv2.get({ packageName: PACKAGE, token });
  return r.data;
}

/** Asks Google Play and writes the result into the company. */
async function applyToken(orgRef, org, token, by) {
  let sub;
  try {
    sub = await playSubscription(token);
  } catch (e) {
    const code = e?.code || e?.response?.status;
    logger.warn('play api', { code, message: e?.message });
    if (code === 401 || code === 403) throw new HttpsError('failed-precondition', 'PLAY_ACCESS');   // service account not invited in Play Console
    if (code === 404 || code === 410 || code === 400) throw new HttpsError('not-found', 'PLAY_TOKEN');
    throw new HttpsError('unavailable', 'PLAY_UNAVAILABLE');
  }
  let d;
  try {
    d = decide(sub, org.managers || []);
  } catch (e) {
    throw new HttpsError('permission-denied', e.message);
  }
  const plan = {
    source: 'play', basePlan: d.basePlan, token, expiry: d.expiry, state: d.state,
    verified: true, test: !!sub.testPurchase, by: by || null, at: Date.now(),
  };
  const batch = db.batch();
  batch.update(orgRef, { seats: d.seats, plan });
  batch.set(db.collection('shomer_tokens').doc(sha256(token)), { orgId: orgRef.id, at: Date.now() });
  if (sub.linkedPurchaseToken) batch.delete(db.collection('shomer_tokens').doc(sha256(sub.linkedPurchaseToken)));
  await batch.commit();
  logger.info('plan applied', { org: orgRef.id, seats: d.seats, state: d.state });
  return { seats: d.seats, basePlan: d.basePlan, expiry: d.expiry, state: d.state };
}

export const verifyTeam = onCall(async (req) => {
  const email = req.auth?.token?.email?.toLowerCase();
  if (!email || req.auth.token.email_verified !== true) throw new HttpsError('unauthenticated', 'NOT_SIGNED_IN');
  const { orgId, token } = req.data || {};
  if (typeof orgId !== 'string' || typeof token !== 'string' || token.length < 10 || token.length > 4096) {
    throw new HttpsError('invalid-argument', 'BAD_REQUEST');
  }
  const ref = db.collection('shomer_orgs').doc(orgId);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError('not-found', 'NO_COMPANY');
  const org = snap.data();
  if (email !== ADMIN && !(org.managers || []).includes(email)) throw new HttpsError('permission-denied', 'NOT_MANAGER');
  // one subscription pays for one company
  const used = await db.collection('shomer_tokens').doc(sha256(token)).get();
  if (used.exists && used.data().orgId !== orgId) throw new HttpsError('already-exists', 'TOKEN_USED');
  return applyToken(ref, org, token, email);
});

export const recheckPlans = onSchedule({ schedule: 'every 6 hours', timeZone: 'Asia/Jerusalem' }, async () => {
  const orgs = await db.collection('shomer_orgs').where('plan.source', '==', 'play').get();
  for (const doc of orgs.docs) {
    const org = doc.data();
    if (!org.plan?.token) continue;
    try {
      await applyToken(doc.ref, org, org.plan.token, 'recheck');
    } catch (e) {
      // a token Google no longer knows = no subscription any more
      if (e instanceof HttpsError && (e.code === 'not-found' || e.code === 'permission-denied')) {
        await doc.ref.update({ seats: 0, 'plan.state': 'GONE', 'plan.at': Date.now() });
        logger.info('plan gone', { org: doc.id, reason: e.message });
      } else logger.warn('recheck failed', { org: doc.id, err: e.message });
    }
  }
});

export const cleanupRecordings = onSchedule({ schedule: 'every day 03:30', timeZone: 'Asia/Jerusalem' }, async () => {
  const bucket = getStorage().bucket();
  const orgs = await db.collection('shomer_orgs').get();
  for (const o of orgs.docs) {
    const days = Number(o.data().policy?.retentionDays) || DEFAULT_RETENTION_DAYS;
    const cutoff = Date.now() - days * 24 * 3600 * 1000;
    const old = await o.ref.collection('recordings').where('uploadedAt', '<', cutoff).limit(500).get();
    for (const r of old.docs) {
      const rec = r.data();
      if (rec.keep === true) continue;
      try {
        if (rec.path) await bucket.file(rec.path).delete({ ignoreNotFound: true });
        await r.ref.delete();
      } catch (e) {
        logger.warn('cleanup', { org: o.id, rec: r.id, err: e.message });
      }
    }
    if (old.size) logger.info('cleanup', { org: o.id, checked: old.size, days });
  }
});
