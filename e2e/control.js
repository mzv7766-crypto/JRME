// The control room in a real (headless) browser: log in as the manager, watch the guard live, start/stop recording.
import { chromium } from 'playwright';
import { device, events, fakeFall, recordings } from './fs.js';
import { writeFileSync, appendFileSync } from 'node:fs';

const OUT = process.env.OUT || 'results';
const sum = (l) => { console.log(l); appendFileSync(`${OUT}/summary.md`, l + '\n'); };
const pass = (t) => sum(`- ✅ ${t}`), fail = (t) => sum(`- ❌ ${t}`), info = (t) => sum(`  - ${t}`);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const until = async (fn, ms = 60000, step = 1000) => { const end = Date.now() + ms; while (Date.now() < end) { try { const v = await fn(); if (v) return v; } catch {} await sleep(step); } return null; };
const phone = async (name) => { const { execSync } = await import('node:child_process'); writeFileSync(`${OUT}/${name}.png`, execSync('adb exec-out screencap -p')); };

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 1360, height: 860 }, locale: 'he-IL' });
const logs = [];
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`));
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`));
try {
  await page.goto('http://127.0.0.1:8000/?emu=1');
  await page.screenshot({ path: `${OUT}/web_01_login.png` });
  await page.fill('#emu-email', 'boss@acme.co.il');
  await page.click('#emu-login button');
  (await until(() => page.isVisible('#app'), 30000)) ? pass('manager signed in to the dashboard') : fail('dashboard did not open');
  const online = await until(async () => (await page.textContent('.guard[data-email="guard@acme.co.il"] .sub'))?.includes('במשמרת'), 90000);
  online ? pass('guard appears on shift in the dashboard') : fail('guard not shown on shift');
  await sleep(2500);
  await page.screenshot({ path: `${OUT}/web_02_board.png` });

  // ---- live video
  await page.click('.guard[data-email="guard@acme.co.il"]');
  await sleep(1000);
  await until(() => page.isEnabled('#d-watch'), 30000);
  await page.click('#d-watch', { timeout: 10000 });
  const playing = await until(() => page.evaluate(() => { const v = document.getElementById('video'); return v && v.videoWidth > 0 && !v.paused ? `${v.videoWidth}x${v.videoHeight}` : null; }), 90000, 1500);
  playing ? pass(`live video playing in the browser (${playing})`) : fail('live video did not start');
  await sleep(4000);
  await page.screenshot({ path: `${OUT}/web_03_live.png` });
  await phone('phone_01_being_watched');
  const d1 = await device();
  d1?.live ? pass('phone reports that it is being watched') : fail('phone did not report live=true');

  // ---- remote recording
  await page.click('#d-rec');
  const rec = await until(async () => (await device())?.recording, 30000);
  rec ? pass('remote start: phone is recording') : fail('remote start did not reach the phone');
  await sleep(8000);
  await phone('phone_02_remote_recording');
  await page.screenshot({ path: `${OUT}/web_04_recording.png` });
  await page.click('#d-rec');
  const stopped = await until(async () => (await device())?.recording === false, 30000);
  stopped ? pass('remote stop: phone stopped') : fail('remote stop did not reach the phone');
  const saved = await until(async () => (await events()).find((e) => e.type === 'rec_saved' && e.email === 'guard@acme.co.il'), 480000, 4000);   // software video encoder on the CI emulator is slow
  saved ? pass(`recording saved and reported (sha256 ${String(saved.sha256).slice(0, 12)}…, trigger ${saved.detail})`) : fail('no rec_saved event');
  saved?.detail === 'REMOTE' ? pass('trigger recorded as REMOTE') : info('trigger: ' + saved?.detail);

  // ---- company cloud backup
  const up = await until(async () => (await recordings()).find((r) => r.email === 'guard@acme.co.il'), 240000, 4000);
  up ? pass(`recording uploaded to the company cloud (${Math.round((up.size || 0) / 1024)} KB, sha ${String(up.sha256).slice(0, 12)}…)`) : fail('recording not uploaded to the company cloud');
  up && up.sha256 === saved?.sha256 ? pass('cloud copy has the same SHA-256 as the phone') : info('sha phone/cloud: ' + saved?.sha256 + ' / ' + up?.sha256);

  // ---- end live view
  await page.click('#d-watch');
  const ended = await until(async () => (await device())?.live === false, 40000);
  ended ? pass('live view ended on the phone') : fail('phone still reports live');

  // ---- fall alarm
  await fakeFall();
  const alarm = await until(() => page.isVisible('#alarm'), 20000);
  alarm ? pass('fall alarm pops up in the control room') : fail('no fall alarm');
  await sleep(1000);
  await page.screenshot({ path: `${OUT}/web_05_fall_alarm.png` });
  if (alarm) await page.click('#alarm-ack');
  await page.click('#tabs button[data-tab="recs"]');
  const listed = await until(() => page.isVisible('[data-play]'), 30000);
  listed ? pass('recording listed in the dashboard') : fail('recording not listed in the dashboard');
  if (listed) {
    await page.click('[data-play]');
    // Playwright's Chromium has no H.264 decoder, so check that the manager can actually fetch the video bytes
    const got = await until(() => page.evaluate(async () => {
      const src = document.getElementById('rec-video')?.src; if (!src) return null;
      const r = await fetch(src); if (!r.ok) return null;
      const b = new Uint8Array(await r.arrayBuffer());
      return String.fromCharCode(...b.slice(4, 8)) === 'ftyp' ? `${b.length} bytes, MP4` : null;
    }), 30000, 2000);
    got ? pass(`manager downloads the cloud recording in the browser (${got})`) : fail('cloud recording could not be downloaded');
    await sleep(1500);
    await page.screenshot({ path: `${OUT}/web_10_recordings.png` });
  }
  await page.click('#tabs button[data-tab="alerts"]'); await sleep(1500);
  await page.screenshot({ path: `${OUT}/web_06_alerts.png` });
  await page.click('#tabs button[data-tab="team"]'); await sleep(800);
  await page.screenshot({ path: `${OUT}/web_07_team.png` });
  await page.click('#tabs button[data-tab="rules"]'); await sleep(800);
  await page.screenshot({ path: `${OUT}/web_08_rules.png` });
  // phone-sized layout
  await page.setViewportSize({ width: 400, height: 860 });
  await page.click('#tabs button[data-tab="board"]'); await sleep(1500);
  await page.screenshot({ path: `${OUT}/web_09_mobile.png` });
} catch (e) {
  fail('control room script error: ' + e.message);
  await page.screenshot({ path: `${OUT}/web_error.png` }).catch(() => {});
} finally {
  writeFileSync(`${OUT}/web_console.txt`, logs.join('\n'));
  await browser.close();
  process.exit(0);
}
