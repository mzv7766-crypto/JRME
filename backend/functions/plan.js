// Pure Google Play plan logic (no Firebase) – unit-tested in plan.test.js.
import { createHash } from 'node:crypto';

export const PACKAGE = 'com.shomer.bodycam';
export const TEAM = 'shomer_team';
export const TEAM_SEATS = [5, 10, 25, 50];

export const sha256 = (s) => createHash('sha256').update(s).digest('hex');
export const seatsOf = (basePlan) => {
  const n = Number(String(basePlan || '').replace(/^team-/, ''));
  return TEAM_SEATS.includes(n) ? n : null;
};

/**
 * Pure decision from a Play "subscriptionsv2" answer (unit-tested).
 * Returns { seats, basePlan, expiry, state, entitled } or throws a short reason.
 */
export function decide(sub, managers, now = Date.now()) {
  const item = (sub.lineItems || []).find((l) => l.productId === TEAM);
  if (!item) throw new Error('not-a-team-plan');
  const basePlan = item.offerDetails?.basePlanId || null;
  const seats = seatsOf(basePlan);
  if (!seats) throw new Error('unknown-base-plan');
  const expiry = item.expiryTime ? Date.parse(item.expiryTime) : 0;
  const state = String(sub.subscriptionState || '').replace('SUBSCRIPTION_STATE_', '');
  // the purchase carries a hash of the buyer's e-mail (set by the app); it must be one of this company's managers
  const buyer = sub.externalAccountIdentifiers?.obfuscatedExternalAccountId;
  if (buyer && !managers.map((m) => sha256(m.toLowerCase())).includes(buyer)) throw new Error('bought-by-someone-else');
  const entitled = (state === 'ACTIVE' || state === 'IN_GRACE_PERIOD' || state === 'CANCELED') && expiry > now;
  return { seats: entitled ? seats : 0, basePlan, expiry, state, entitled };
}

