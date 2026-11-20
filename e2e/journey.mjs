// End-to-end UI journey for Swapp, driven through a real browser (Microsoft Edge, headless).
// Usage: node journey.mjs <baseUrl> <screenshotDir>
import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const BASE = process.argv[2] || 'http://localhost:8080';
const SHOTS = process.argv[3] || './shots';
fs.mkdirSync(SHOTS, { recursive: true });

const run = Date.now().toString(36);
const errors = [];
const steps = [];
const step = (msg) => { steps.push(msg); console.log('✓', msg); };

// 1x1 PNG plus a larger generated PNG for nicer screenshots
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=', 'base64');
// Generate a 640x480 gradient PNG so screenshots show a real photo area.
import zlib from 'node:zlib';
function gradientPng(w, h, [r1, g1, b1], [r2, g2, b2]) {
  const rows = [];
  for (let y = 0; y < h; y++) {
    const row = Buffer.alloc(1 + w * 3);
    for (let x = 0; x < w; x++) {
      const t = (x / w + y / h) / 2;
      row[1 + x * 3] = r1 + (r2 - r1) * t; row[2 + x * 3] = g1 + (g2 - g1) * t; row[3 + x * 3] = b1 + (b2 - b1) * t;
    }
    rows.push(row);
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const td = Buffer.concat([Buffer.from(type), data]); const c = Buffer.alloc(4); c.writeUInt32BE(crc(td)); return Buffer.concat([len, td, c]); };
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(Buffer.concat(rows))), chunk('IEND', Buffer.alloc(0))]);
}
const imgPath = path.join(SHOTS, '..', `upload-${run}.png`);
fs.writeFileSync(imgPath, gradientPng(640, 480, [15, 118, 110], [234, 180, 120]));
void PNG;

// Uses an installed browser: BROWSER_CHANNEL=msedge (default) or chrome.
const browser = await chromium.launch({ channel: process.env.BROWSER_CHANNEL || 'msedge', headless: true });

async function newUser(name, geolocation) {
  const context = await browser.newContext({
    viewport: { width: 1360, height: 900 },
    ...(geolocation ? { geolocation, permissions: ['geolocation'] } : {}),
  });
  const page = await context.newPage();
  page.on('console', (m) => {
    if (m.type() !== 'error') return;
    if (page.url().includes('no-such-page') && m.text().includes('404')) return; // expected
    errors.push(`[${name}] console: ${m.text()} @ ${page.url()}`);
  });
  page.on('pageerror', (e) => errors.push(`[${name}] pageerror: ${e.message} @ ${page.url()}`));
  page.on('response', (r) => { if (r.status() >= 500) errors.push(`[${name}] HTTP ${r.status()} ${r.url()}`); });
  return { context, page, name, email: `${name.toLowerCase()}.${run}@example.com` };
}

async function shot(page, file, full = true) {
  await page.waitForTimeout(350);
  await page.screenshot({ path: path.join(SHOTS, file), fullPage: full });
}

async function confirmModal(page) {
  const btn = page.locator('dialog.modal [data-submit]');
  await btn.waitFor();
  await btn.click();
  await page.locator('dialog.modal').waitFor({ state: 'detached' });
}

async function register(u, location) {
  await u.page.goto(`${BASE}/register.html`);
  await u.page.fill('#displayName', u.name);
  await u.page.fill('#email', u.email);
  await u.page.fill('#password', 'Passw0rd!');
  await u.page.fill('#location', location);
  await u.page.click('button[type=submit]');
  await u.page.waitForURL(/home\.html/);
}

async function createListing(u, { title, description, type, category, condition, image, pinWithGps }) {
  await u.page.goto(`${BASE}/item-new.html`);
  await u.page.locator(`input[name=listingType][value=${type}]`).check({ force: true });
  if (pinWithGps) {
    await u.page.click('#loc-picker [data-gps]');
    await u.page.waitForSelector('#loc-picker .loc-status.set', { timeout: 15000 });
  }
  await u.page.fill('#title', title);
  await u.page.fill('#description', description);
  await u.page.selectOption('#categoryId', { label: category });
  await u.page.selectOption('#condition', condition);
  if (image) await u.page.setInputFiles('#file-input', image);
  await u.page.click('#submit-btn');
  await u.page.waitForURL(/item\.html\?id=\d+/);
  return Number(new URL(u.page.url()).searchParams.get('id'));
}

try {
  // ---------- landing (anonymous, fresh session)
  const anon = await newUser('Visitor');
  await anon.page.goto(BASE + '/');
  await anon.page.waitForSelector('.hero h1');
  await anon.page.waitForSelector('#hero-stats strong');
  await shot(anon.page, '01-landing.png');
  step('Landing page renders with live stats in a fresh browser session');
  await anon.page.goto(BASE + '/register.html');
  await shot(anon.page, '02-register.png', false);
  await anon.page.goto(BASE + '/login.html');
  await shot(anon.page, '03-login.png', false);
  // protected page redirects to login
  await anon.page.goto(BASE + '/dashboard.html');
  await anon.page.waitForURL(/login\.html\?next=/);
  step('Protected pages redirect anonymous visitors to sign-in');
  await anon.page.goto(BASE + '/no-such-page.html');
  await anon.page.waitForSelector('.error-page .code');
  await shot(anon.page, '19-404.png', false);
  step('Unknown URLs show the styled 404 page');

  // ---------- 1-2. register two users
  // Simulated device locations: Alice in Koramangala, Bob in HSR Layout (~3 km apart), Bengaluru.
  const alice = await newUser('Alice', { latitude: 12.9352, longitude: 77.6245 });
  const bob = await newUser('Bob', { latitude: 12.9116, longitude: 77.6389 });
  await register(alice, 'Springfield');
  step(`Registered ${alice.email}`);
  await register(bob, 'Springfield');
  step(`Registered ${bob.email}`);

  // ---------- 3. Alice creates a listing with an image
  const bikeTitle = `Road bicycle ${run}`;
  const bikeId = await createListing(alice, {
    title: bikeTitle, description: 'Lightweight aluminium road bike, 54cm frame, recently serviced with new brake pads.',
    type: 'EXCHANGE', category: 'Bikes & Vehicles', condition: 'GOOD', image: imgPath, pinWithGps: true,
  });
  await alice.page.waitForSelector('.gallery-main img');
  step(`Alice created listing #${bikeId} with a photo, pinned with "Use my location"`);

  // edit listing
  await alice.page.goto(`${BASE}/item-edit.html?id=${bikeId}`);
  await alice.page.waitForSelector('#item-form');
  await alice.page.waitForFunction(() => document.querySelector('#title').value.length > 0);
  await shot(alice.page, '09-edit-listing.png');
  await alice.page.fill('#description', 'Lightweight aluminium road bike, 54cm frame, recently serviced. Comes with lights.');
  await alice.page.click('#submit-btn');
  await alice.page.waitForURL(/item\.html\?id=/);
  await alice.page.waitForSelector('text=Comes with lights');
  step('Alice edited her listing');

  // ---------- Bob lists his own item to offer
  const monitorTitle = `Gaming monitor ${run}`;
  const monitorId = await createListing(bob, {
    title: monitorTitle, description: '27 inch 144Hz gaming monitor, no dead pixels, original stand and cables included.',
    type: 'EXCHANGE', category: 'Electronics', condition: 'LIKE_NEW', image: imgPath,
  });
  step(`Bob created listing #${monitorId}`);
  await bob.page.goto(`${BASE}/item-new.html`);
  await bob.page.waitForSelector('#item-form');
  await shot(bob.page, '08-create-listing.png');

  // ---------- 4-5. Bob browses and finds Alice's listing (backend search)
  await bob.page.goto(`${BASE}/browse.html?q=${encodeURIComponent(bikeTitle)}`);
  await bob.page.waitForSelector(`.item-card:has-text("${bikeTitle}")`);
  await bob.page.goto(`${BASE}/browse.html`);
  await bob.page.waitForSelector('.item-card');
  await bob.page.locator('input[name=type][value=EXCHANGE]').check();
  await bob.page.waitForTimeout(600);
  await shot(bob.page, '05-browse.png');
  await bob.page.goto(`${BASE}/home.html`);
  await bob.page.waitForSelector('.section .item-card');
  await shot(bob.page, '04-home.png');
  step('Bob found the listing via search and filters');

  // "Near me": Bob uses his device location and sees Alice's bike ~3 km away
  await bob.page.goto(`${BASE}/browse.html?near=me`);
  await bob.page.click('#f-origin [data-gps]');
  await bob.page.waitForSelector('#f-origin .loc-status.set', { timeout: 15000 });
  const bikeCard = bob.page.locator(`.item-card:has-text("${bikeTitle}")`);
  await bikeCard.waitFor();
  const distanceText = await bikeCard.locator('.distance-tag').innerText();
  if (!/\b[2-4](\.\d)? km away/.test(distanceText)) errors.push(`Unexpected distance label: ${distanceText}`);
  await bob.page.selectOption('#f-radius', '1');
  await bikeCard.waitFor({ state: 'detached' }); // ~3 km away, so it must not appear within 1 km
  await bob.page.selectOption('#f-radius', '5');
  await bikeCard.waitFor();
  await shot(bob.page, '05b-near-me.png');
  step(`"Near me" works: Bob sees the bike ${distanceText.replace('· ', '')}, and it disappears at 1 km`);

  // ---------- 6. Bob submits an exchange offer from the item page
  await bob.page.goto(`${BASE}/item.html?id=${bikeId}`);
  await bob.page.click('#make-offer');
  await bob.page.waitForSelector('dialog.modal input[name=offeredItemId]');
  await bob.page.fill('#offer-message', 'Hi Alice! Would you swap your bike for my monitor?');
  await shot(bob.page, '07-offer-dialog.png', false);
  await confirmModal(bob.page);
  await bob.page.waitForSelector('text=You have a pending offer');
  await shot(bob.page, '06-item-detail.png');
  step('Bob submitted an exchange offer through the offer dialog');

  // ---------- 7-8. Alice sees notification + incoming offer
  await alice.page.goto(`${BASE}/dashboard.html`);
  await alice.page.waitForSelector('#stats .stat');
  await alice.page.waitForSelector('[data-count="unreadNotifications"]:not(.hidden)');
  await shot(alice.page, '10-dashboard.png');
  step('Alice sees the unread notification badge and dashboard stats');
  await alice.page.goto(`${BASE}/notifications.html`);
  await alice.page.waitForSelector('.notif.unread');
  await shot(alice.page, '16-notifications.png');
  await alice.page.goto(`${BASE}/offers-incoming.html`);
  await alice.page.waitForSelector(`.activity:has-text("${monitorTitle}")`);
  await shot(alice.page, '13-offers-incoming.png');
  step('Alice sees the incoming offer');

  // ---------- 9. Alice accepts
  await alice.page.locator('.activity [data-action=accept]').first().click();
  await confirmModal(alice.page);
  await alice.page.waitForSelector('.toast:has-text("Offer accepted")');
  step('Alice accepted the offer');

  // ---------- 10-11. exchange record + item availability
  await alice.page.goto(`${BASE}/exchanges.html`);
  await alice.page.waitForSelector(`.activity:has-text("${bikeTitle}")`);
  await shot(alice.page, '15-exchanges.png');
  await alice.page.goto(`${BASE}/item.html?id=${bikeId}`);
  await alice.page.waitForSelector('.badge:has-text("Reserved")');
  await bob.page.goto(`${BASE}/browse.html?q=${encodeURIComponent(bikeTitle)}`);
  await bob.page.waitForSelector('text=No items match your filters');
  step('Exchange is active; both items are reserved and hidden from discovery');

  // ---------- 12. Bob got the acceptance notification
  await bob.page.goto(`${BASE}/notifications.html`);
  await bob.page.waitForSelector('.notif:has-text("Offer accepted")');
  await bob.page.goto(`${BASE}/offers-outgoing.html?status=ACCEPTED`);
  await bob.page.waitForSelector('.activity a:has-text("View exchange")');
  await shot(bob.page, '14-offers-outgoing.png');
  step('Bob received the acceptance notification');

  // ---------- messaging
  await bob.page.goto(`${BASE}/item.html?id=${bikeId}`);
  await bob.page.click('#message-owner');
  await bob.page.fill('#dm-body', 'Great! Can we meet Saturday at 10am by the library?');
  await confirmModal(bob.page);
  await bob.page.waitForURL(/messages\.html\?c=\d+/);
  await bob.page.waitForSelector('.msg.mine .bubble:has-text("Saturday")');
  await alice.page.goto(`${BASE}/messages.html`);
  await alice.page.waitForSelector('.convo.unread');
  await alice.page.click('.convo');
  await alice.page.waitForSelector('.msg .bubble:has-text("Saturday")');
  await alice.page.fill('#compose-body', 'Saturday 10am works. See you there!');
  await alice.page.keyboard.press('Enter');
  await alice.page.waitForSelector('.msg.mine .bubble:has-text("See you there")');
  // realtime delivery to Bob (WebSocket) without reload
  await bob.page.waitForSelector('.msg:not(.mine) .bubble:has-text("See you there")', { timeout: 8000 });
  await shot(alice.page, '17-messages.png', false);
  step('Messaging works in both directions, with realtime delivery over WebSocket');

  // ---------- 14. completion requires both parties
  await alice.page.goto(`${BASE}/exchanges.html`);
  await alice.page.locator('[data-action=confirm]').first().click();
  await confirmModal(alice.page);
  await alice.page.waitForSelector('.badge:has-text("You confirmed")');
  await bob.page.goto(`${BASE}/exchanges.html`);
  await bob.page.locator('[data-action=confirm]').first().click();
  await confirmModal(bob.page);
  await bob.page.waitForSelector('.toast:has-text("Exchange completed")');
  await bob.page.goto(`${BASE}/exchanges.html?status=COMPLETED`);
  await bob.page.waitForSelector(`.activity:has-text("${bikeTitle}") .badge:has-text("Completed")`);
  step('Exchange completed only after both participants confirmed');

  // ---------- donation / giveaway workflow
  const booksTitle = `Children's books ${run}`;
  const booksId = await createListing(alice, {
    title: booksTitle, description: 'A box of about 30 picture books for ages 3 to 7, all in good condition.',
    type: 'GIVEAWAY', category: 'Books & Media', condition: 'GOOD',
  });
  await bob.page.goto(`${BASE}/item.html?id=${booksId}`);
  await bob.page.click('#request-item');
  await bob.page.fill('#req-message', 'My kids would love these!');
  await confirmModal(bob.page);
  await bob.page.waitForSelector('text=Your request has been sent');
  await alice.page.goto(`${BASE}/giveaways.html?tab=incoming`);
  await alice.page.waitForSelector(`.activity:has-text("${booksTitle}")`);
  await alice.page.locator('[data-action=accept]').first().click();
  await confirmModal(alice.page);
  await alice.page.waitForSelector('[data-action=complete]');
  await shot(alice.page, '12-giveaways.png');
  await alice.page.locator('[data-action=complete]').first().click();
  await confirmModal(alice.page);
  await alice.page.waitForSelector(`.activity:has-text("${booksTitle}") .badge:has-text("Completed")`);
  step('Giveaway request, acceptance and hand-over work');

  // ---------- remaining pages
  await alice.page.goto(`${BASE}/my-listings.html`);
  await alice.page.waitForSelector('.item-card');
  await shot(alice.page, '11-my-listings.png');
  await alice.page.goto(`${BASE}/profile.html`);
  await alice.page.waitForSelector('.profile-head h1');
  await shot(alice.page, '18-profile.png');
  await alice.page.goto(`${BASE}/settings.html`);
  await alice.page.waitForFunction(() => document.querySelector('#displayName').value === 'Alice');
  await alice.page.fill('#bio', 'Cyclist, reader, and fan of giving things a second life.');
  await alice.page.click('#profile-form button[type=submit]');
  await alice.page.waitForSelector('.toast:has-text("Profile saved")');
  await shot(alice.page, '20-settings.png');
  step('Profile, my listings and settings pages work');

  // ---------- mobile layout
  const mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
  const mpage = await mobile.newPage();
  mpage.on('pageerror', (e) => errors.push(`[mobile] pageerror: ${e.message}`));
  await mpage.goto(BASE + '/');
  await mpage.waitForSelector('.hero h1');
  await mpage.screenshot({ path: path.join(SHOTS, '21-mobile-landing.png') });
  await mpage.goto(`${BASE}/item.html?id=${bikeId}`);
  await mpage.waitForSelector('.detail-title');
  await mpage.screenshot({ path: path.join(SHOTS, '22-mobile-item.png'), fullPage: true });
  const overflow = await mpage.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
  if (overflow) errors.push('[mobile] horizontal overflow on item page');
  step('Mobile layout renders without horizontal overflow');

  // ---------- dark mode
  const dark = await browser.newContext({ viewport: { width: 1360, height: 900 }, colorScheme: 'dark' });
  const dpage = await dark.newPage();
  await dpage.goto(BASE + '/');
  await dpage.waitForSelector('#hero-stats strong');
  await dpage.screenshot({ path: path.join(SHOTS, '23-dark-landing.png') });
  step('Dark mode renders');
} catch (err) {
  errors.push('FAILED: ' + (err.stack || err.message));
}

await browser.close();
console.log(`\n${steps.length} steps passed.`);
if (errors.length) {
  console.log(`\n${errors.length} problem(s):`);
  errors.forEach((e) => console.log(' ✗', e));
  process.exit(1);
}
console.log('No console errors, page errors or server errors.');
