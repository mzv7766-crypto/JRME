import { test } from 'node:test';
import assert from 'node:assert/strict';
import { decide, sha256, seatsOf } from './plan.js';

const NOW = Date.parse('2026-10-18T00:00:00Z');
const managers = ['boss@acme.co.il'];
const sub = (over = {}) => ({
  subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
  lineItems: [{ productId: 'shomer_team', expiryTime: '2026-11-18T00:00:00Z', offerDetails: { basePlanId: 'team-10' } }],
  externalAccountIdentifiers: { obfuscatedExternalAccountId: sha256('boss@acme.co.il') },
  ...over,
});

test('active plan gives its seats', () => {
  const d = decide(sub(), managers, NOW);
  assert.equal(d.seats, 10); assert.equal(d.basePlan, 'team-10'); assert.equal(d.entitled, true);
});
test('cancelled but paid until the end of the month keeps seats', () => {
  assert.equal(decide(sub({ subscriptionState: 'SUBSCRIPTION_STATE_CANCELED' }), managers, NOW).seats, 10);
});
test('expired / on hold / refunded → no seats', () => {
  assert.equal(decide(sub({ subscriptionState: 'SUBSCRIPTION_STATE_EXPIRED' }), managers, NOW).seats, 0);
  assert.equal(decide(sub({ subscriptionState: 'SUBSCRIPTION_STATE_ON_HOLD' }), managers, NOW).seats, 0);
  const past = sub(); past.lineItems[0].expiryTime = '2026-10-01T00:00:00Z';
  assert.equal(decide(past, managers, NOW).seats, 0);
});
test('grace period keeps seats', () => {
  assert.equal(decide(sub({ subscriptionState: 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' }), managers, NOW).seats, 10);
});
test('personal Pro token or unknown plan is rejected', () => {
  assert.throws(() => decide(sub({ lineItems: [{ productId: 'shomer_pro', expiryTime: '2026-11-18T00:00:00Z' }] }), managers, NOW), /not-a-team-plan/);
  const odd = sub(); odd.lineItems[0].offerDetails.basePlanId = 'team-7';
  assert.throws(() => decide(odd, managers, NOW), /unknown-base-plan/);
});
test('a purchase made by someone who is not a manager of this company is rejected', () => {
  assert.throws(() => decide(sub(), ['other@x.com'], NOW), /bought-by-someone-else/);
});
test('seatsOf', () => {
  assert.equal(seatsOf('team-50'), 50); assert.equal(seatsOf('team-3'), null); assert.equal(seatsOf(null), null);
});
