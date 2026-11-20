// Seeds a Swapp instance with realistic demo data through the public REST API (all business rules apply).
// Usage: node seed.mjs [baseUrl]        Demo accounts all use the password "Passw0rd!".
import zlib from 'node:zlib';

const BASE = process.argv[2] || 'http://localhost:8080';
const PASSWORD = 'Passw0rd!';

// ------------------------------------------------------------------ tiny PNG generator

const CRC = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
const crc = (buf) => { let c = 0xffffffff; for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
  return Buffer.concat([len, td, c]);
};
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));

/** Diagonal gradient with a soft "product" disc, so photos read as distinct objects. */
function photo(w, h, from, to, disc) {
  const [a, b, d] = [hex(from), hex(to), hex(disc)];
  const rows = [];
  const cx = w * 0.5, cy = h * 0.52, r = Math.min(w, h) * 0.3;
  for (let y = 0; y < h; y++) {
    const row = Buffer.alloc(1 + w * 3);
    for (let x = 0; x < w; x++) {
      const t = (x / w + y / h) / 2;
      let px = [0, 1, 2].map((i) => a[i] + (b[i] - a[i]) * t);
      const dist = Math.hypot(x - cx, y - cy);
      if (dist < r) {
        const shade = 0.85 + 0.15 * (1 - dist / r);
        px = d.map((v) => Math.min(255, v * shade));
      } else if (dist < r + 6) {
        px = px.map((v) => v * 0.8);
      }
      row[1 + x * 3] = px[0]; row[2 + x * 3] = px[1]; row[3 + x * 3] = px[2];
    }
    rows.push(row);
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 2;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(Buffer.concat(rows))), chunk('IEND', Buffer.alloc(0))]);
}

// ------------------------------------------------------------------ API client

async function call(token, method, path, body) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  let payload;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const res = await fetch(BASE + path, { method, headers, body: payload });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error(`${method} ${path} → ${res.status}: ${data && data.message}`);
    err.status = res.status;
    throw err;
  }
  return data;
}

// Approximate centres of Bengaluru neighbourhoods used by the demo members.
const AREAS = {
  'Koramangala': [12.9352, 77.6245], 'Indiranagar': [12.9719, 77.6412], 'Jayanagar': [12.9250, 77.5938],
  'HSR Layout': [12.9116, 77.6389], 'Whitefield': [12.9698, 77.7500], 'Malleshwaram': [13.0031, 77.5643],
  'BTM Layout': [12.9166, 77.6101], 'Electronic City': [12.8452, 77.6602],
};
const areaOf = (location) => AREAS[location.split(',')[0].trim()];
// Deterministic jitter (up to ~600 m) so listings from one member don't sit on the exact same point.
let jitterSeed = 7;
const jitter = () => { jitterSeed = (jitterSeed * 9301 + 49297) % 233280; return (jitterSeed / 233280 - 0.5) * 0.011; };

async function account({ name, email, location, bio, colors }) {
  let auth;
  try {
    auth = await call(null, 'POST', '/api/auth/register', { email, password: PASSWORD, displayName: name, location });
  } catch (e) {
    if (e.status !== 409) throw e;
    auth = await call(null, 'POST', '/api/auth/login', { email, password: PASSWORD });
  }
  const token = auth.accessToken;
  const [latitude, longitude] = areaOf(location);
  await call(token, 'PUT', '/api/users/me', { displayName: name, bio, location, latitude, longitude });
  if (!auth.user.avatarUrl) {
    const fd = new FormData();
    fd.append('file', new Blob([photo(160, 160, colors[0], colors[1], colors[1])], { type: 'image/png' }), 'avatar.png');
    await call(token, 'POST', '/api/users/me/avatar', fd);
  }
  return { token, id: auth.user.id, name };
}

const categories = Object.fromEntries((await call(null, 'GET', '/api/categories')).map((c) => [c.slug, c.id]));

async function list(user, l) {
  const [lat, lng] = areaOf(user.location);
  const item = await call(user.token, 'POST', '/api/items', {
    title: l.title, description: l.description, categoryId: categories[l.category], condition: l.condition,
    listingType: l.type, location: l.location || user.location, latitude: lat + jitter(), longitude: lng + jitter(),
  });
  const fd = new FormData();
  const shots = l.photos || 1;
  for (let i = 0; i < shots; i++) {
    const [f, t, d] = l.palette;
    fd.append('files', new Blob([photo(800, 600, i % 2 ? t : f, i % 2 ? f : t, d)], { type: 'image/png' }), `p${i}.png`);
  }
  await call(user.token, 'POST', `/api/items/${item.id}/images`, fd);
  return item.id;
}

const offer = (u, requested, offered, message) =>
  call(u.token, 'POST', '/api/offers', { requestedItemId: requested, offeredItemId: offered, message }).then((o) => o.id);
const say = async (from, to, body, itemId) => (await call(from.token, 'POST', '/api/conversations', { recipientId: to.id, itemId, body })).id;
const reply = (from, convoId, body) => call(from.token, 'POST', `/api/conversations/${convoId}/messages`, { body });

// ------------------------------------------------------------------ people

const B = ', Bengaluru';
const people = {
  priya: await account({ name: 'Priya Sharma', email: 'priya@swapp.demo', location: 'Koramangala' + B, bio: 'Designer, plant mum and serial declutterer. I love giving things a second home.', colors: ['#f6a5c0', '#b8326a'] }),
  arjun: await account({ name: 'Arjun Mehta', email: 'arjun@swapp.demo', location: 'Indiranagar' + B, bio: 'Weekend cyclist and gadget tinkerer. Always up for a fair swap.', colors: ['#9ad0f5', '#1e5f99'] }),
  ananya: await account({ name: 'Ananya Iyer', email: 'ananya@swapp.demo', location: 'Jayanagar' + B, bio: 'Bookworm and new mum. Swapping baby gear as the little one grows.', colors: ['#c9b6f2', '#5b3fa8'] }),
  rohan: await account({ name: 'Rohan Gupta', email: 'rohan@swapp.demo', location: 'HSR Layout' + B, bio: 'Musician and home cook. Moving soon, so lots of furniture going.', colors: ['#ffd29a', '#c2570c'] }),
  meera: await account({ name: 'Meera Nair', email: 'meera@swapp.demo', location: 'Whitefield' + B, bio: 'Software engineer by day, gardener by evening.', colors: ['#a7e3c4', '#16794a'] }),
  karthik: await account({ name: 'Karthik Rao', email: 'karthik@swapp.demo', location: 'Malleshwaram' + B, bio: 'Retired teacher. Donating books and tools to people who will use them.', colors: ['#f4c7a1', '#8a4b20'] }),
  sneha: await account({ name: 'Sneha Patel', email: 'sneha@swapp.demo', location: 'BTM Layout' + B, bio: 'Student on a budget. Trading what I have for what I need!', colors: ['#ffb3a7', '#c2341d'] }),
  vikram: await account({ name: 'Vikram Singh', email: 'vikram@swapp.demo', location: 'Electronic City' + B, bio: 'Fitness and outdoors. Trekking gear always welcome.', colors: ['#b6d7a8', '#3d6b2a'] }),
};
Object.entries({ priya: 'Koramangala', arjun: 'Indiranagar', ananya: 'Jayanagar', rohan: 'HSR Layout', meera: 'Whitefield', karthik: 'Malleshwaram', sneha: 'BTM Layout', vikram: 'Electronic City' })
  .forEach(([k, area]) => { people[k].location = area + B; });
const { priya, arjun, ananya, rohan, meera, karthik, sneha, vikram } = people;
console.log(`✓ ${Object.keys(people).length} members ready`);

// ------------------------------------------------------------------ listings

const P = {
  tech: ['#dfe7f2', '#9fb4d1', '#2b3a55'], bike: ['#d9f0e6', '#7cc4a4', '#1f5c45'], sofa: ['#f3e4d3', '#d1a67c', '#7a4a26'],
  book: ['#f6efd9', '#e0c27a', '#8a5a14'], kid: ['#fde3ec', '#f3a3c0', '#c2477a'], music: ['#e9e2f6', '#a993d9', '#4b3486'],
  sport: ['#e4f2da', '#9fcf7f', '#3d7a1f'], home: ['#e7f0f0', '#9ccac7', '#2f6f6b'], cloth: ['#f6e1df', '#e3a49b', '#9c3d31'],
  tool: ['#ececec', '#b9b9b9', '#4a4a4a'], plant: ['#e1f3df', '#8fd18a', '#23701f'],
};

const items = {};
const L = async (key, owner, l) => { items[key] = await list(owner, l); };

await L('ps4', arjun, { title: 'PlayStation 4 Slim (1TB) with 2 controllers', type: 'EXCHANGE', category: 'electronics', condition: 'GOOD', palette: P.tech, photos: 2,
  description: 'PS4 Slim 1TB in good working condition. Two DualShock controllers, HDMI cable and 3 games (FIFA 22, Uncharted 4, Spider-Man). Looking to swap for a road bike or a good mechanical keyboard.' });
await L('keyboard', arjun, { title: 'Keychron K2 mechanical keyboard (brown switches)', type: 'EXCHANGE', category: 'electronics', condition: 'LIKE_NEW', palette: P.tech,
  description: 'Wireless/wired 75% layout, backlit, Gateron brown switches. Used for 4 months. Mac and Windows keycaps included.' });
await L('roadbike', priya, { title: 'Btwin Triban road bike, size M', type: 'EXCHANGE', category: 'bikes', condition: 'GOOD', palette: P.bike, photos: 3,
  description: 'Aluminium frame road bike, 16-speed, recently serviced (new chain and brake pads). A few scuffs on the top tube. Would love a gaming console or a camera in exchange.' });
await L('monstera', priya, { title: 'Monstera deliciosa in ceramic pot', type: 'GIVEAWAY', category: 'garden', condition: 'GOOD', palette: P.plant,
  description: 'Healthy 3-foot monstera with 9 leaves, in a white ceramic pot. Moving to a smaller flat and need a loving home for it. Free to whoever can pick it up.' });
await L('cribs', ananya, { title: 'Wooden baby cot with mattress', type: 'DONATION', category: 'baby', condition: 'GOOD', palette: P.kid, photos: 2,
  description: 'Solid pinewood cot (120x60) with an adjustable base and a breathable mattress. Our daughter has outgrown it. Would love it to go to a family who needs it.' });
await L('novels', ananya, { title: 'Box of 25 English novels', type: 'GIVEAWAY', category: 'books', condition: 'GOOD', palette: P.book,
  description: 'Mix of contemporary fiction, thrillers and a few classics: Murakami, Amitav Ghosh, Agatha Christie, Sally Rooney and more. Take the whole box.' });
await L('stroller', ananya, { title: 'Looking for a lightweight baby stroller', type: 'REQUEST', category: 'baby', condition: 'FAIR', palette: P.kid,
  description: 'Need a compact, foldable stroller for travel. Condition does not need to be perfect as long as the brakes and fold work. Happy to swap books or baby clothes.' });
await L('sofa', rohan, { title: '3-seater fabric sofa (grey)', type: 'DONATION', category: 'furniture', condition: 'FAIR', palette: P.sofa, photos: 2,
  description: 'Comfortable 3-seater, about 6 years old. Some fading on one arm but no tears. Moving out next week, so it has to go. You arrange the pick-up.' });
await L('guitar', rohan, { title: 'Yamaha F310 acoustic guitar', type: 'EXCHANGE', category: 'music', condition: 'GOOD', palette: P.music, photos: 2,
  description: 'Great beginner guitar, new strings, comes with a padded gig bag, capo and tuner. Looking to swap for a keyboard or a Bluetooth speaker.' });
await L('desk', rohan, { title: 'Study desk with drawer', type: 'EXCHANGE', category: 'furniture', condition: 'GOOD', palette: P.sofa,
  description: 'Engineered wood study desk (100x50cm) with a drawer and cable cutout. Sturdy and clean. Open to any useful swap.' });
await L('herbs', meera, { title: 'Herb starter kit: basil, mint, tulsi', type: 'GIVEAWAY', category: 'garden', condition: 'NEW', palette: P.plant,
  description: 'Six healthy seedlings in biodegradable pots, grown on my balcony. Perfect for a kitchen window. Collect from Whitefield.' });
await L('monitor', meera, { title: 'Dell 24" IPS monitor (P2419H)', type: 'EXCHANGE', category: 'electronics', condition: 'LIKE_NEW', palette: P.tech, photos: 2,
  description: 'Full HD IPS, height-adjustable stand, HDMI + DisplayPort. Upgraded to a bigger screen. Would swap for a standing-desk converter or an e-reader.' });
await L('kindle', meera, { title: 'Looking for: Kindle or any e-reader', type: 'REQUEST', category: 'electronics', condition: 'GOOD', palette: P.tech,
  description: 'Hoping to find a Kindle (any generation with a backlight). I can offer a monitor, plants or home-baked goodies!' });
await L('tools', karthik, { title: 'Bosch drill machine with bit set', type: 'DONATION', category: 'tools', condition: 'GOOD', palette: P.tool, photos: 2,
  description: '500W impact drill with 13 bits in a carry case. Works perfectly. I no longer do DIY, so I would rather it helps someone.' });
await L('encyclopedia', karthik, { title: 'Children\'s encyclopedia set (12 volumes)', type: 'DONATION', category: 'books', condition: 'GOOD', palette: P.book,
  description: 'Illustrated encyclopedia set, ideal for ages 8-14. Great for a school library, NGO or curious kids.' });
await L('chess', karthik, { title: 'Wooden chess set with folding board', type: 'EXCHANGE', category: 'toys', condition: 'LIKE_NEW', palette: P.home,
  description: 'Handcrafted sheesham wood set, weighted pieces, 16-inch folding board that doubles as storage. Would swap for a carrom board.' });
await L('jacket', sneha, { title: 'Denim jacket, women\'s size M', type: 'EXCHANGE', category: 'clothing', condition: 'LIKE_NEW', palette: P.cloth,
  description: 'Levi\'s light-wash denim jacket, worn twice. Looking to swap for a winter jacket or a backpack.' });
await L('calculator', sneha, { title: 'Looking for a scientific calculator', type: 'REQUEST', category: 'other', condition: 'GOOD', palette: P.tool,
  description: 'Need a Casio fx-991 (or similar) for my engineering exams next month. Can swap my denim jacket or help with tutoring!' });
await L('speaker', sneha, { title: 'JBL Flip 4 Bluetooth speaker', type: 'EXCHANGE', category: 'electronics', condition: 'GOOD', palette: P.music,
  description: 'Waterproof portable speaker, around 10 hours of battery life. Small scratch on the grille, sounds perfect.' });
await L('tent', vikram, { title: 'Quechua 2-person camping tent', type: 'EXCHANGE', category: 'sports', condition: 'GOOD', palette: P.sport, photos: 2,
  description: 'Pop-up tent, used on 4 treks. Waterproof, all pegs included. Would swap for trekking poles or a headlamp.' });
await L('dumbbells', vikram, { title: 'Adjustable dumbbells (2 x 10kg)', type: 'EXCHANGE', category: 'sports', condition: 'GOOD', palette: P.sport,
  description: 'Pair of adjustable dumbbells with plates and collars. Moving to a gym membership, so they are free for a fair swap.' });
await L('yogamat', vikram, { title: 'Yoga mat and resistance bands', type: 'GIVEAWAY', category: 'sports', condition: 'GOOD', palette: P.sport,
  description: '6mm yoga mat plus a set of 5 resistance bands. Clean and ready to use.' });
await L('lamp', priya, { title: 'Brass table lamp', type: 'EXCHANGE', category: 'home', condition: 'LIKE_NEW', palette: P.home,
  description: 'Vintage-style brass lamp with a linen shade, E27 bulb included. Lovely warm light for a reading corner.' });
await L('mixer', rohan, { title: 'Philips mixer grinder (750W)', type: 'EXCHANGE', category: 'home', condition: 'GOOD', palette: P.home,
  description: 'Three jars, all blades sharp, works perfectly. Upgraded to a food processor.' });
await L('carrom', arjun, { title: 'Full-size carrom board with coins', type: 'EXCHANGE', category: 'toys', condition: 'GOOD', palette: P.home,
  description: 'Tournament-size carrom board, smooth surface, coins and striker included. Fun for the whole family.' });
console.log(`✓ ${Object.keys(items).length} listings with photos`);

// ------------------------------------------------------------------ offers & exchanges

// Completed exchange: PS4 <-> road bike (both confirmed) -> appears in history
const o1 = await offer(arjun, items.roadbike, items.ps4, 'Hi Priya! My PS4 with 3 games for your Triban? Happy to meet halfway.');
const ex1 = (await call(priya.token, 'POST', `/api/offers/${o1}/accept`)).exchangeId;
await call(priya.token, 'POST', `/api/exchanges/${ex1}/confirm`);
await call(arjun.token, 'POST', `/api/exchanges/${ex1}/confirm`);

// Completed exchange: chess set <-> carrom board
const o2 = await offer(arjun, items.chess, items.carrom, 'Karthik sir, would you take my carrom board for your chess set?');
const ex2 = (await call(karthik.token, 'POST', `/api/offers/${o2}/accept`)).exchangeId;
await call(arjun.token, 'POST', `/api/exchanges/${ex2}/confirm`);
await call(karthik.token, 'POST', `/api/exchanges/${ex2}/confirm`);

// Active exchange, one side confirmed: guitar <-> JBL speaker
const o3 = await offer(sneha, items.guitar, items.speaker, 'I have always wanted to learn guitar! JBL Flip 4 for your Yamaha?');
const ex3 = (await call(rohan.token, 'POST', `/api/offers/${o3}/accept`)).exchangeId;
await call(sneha.token, 'POST', `/api/exchanges/${ex3}/confirm`);

// Active exchange, nobody confirmed yet: monitor <-> keyboard
const o4 = await offer(arjun, items.monitor, items.keyboard, 'Meera, my Keychron for your Dell monitor? Both barely used.');
await call(meera.token, 'POST', `/api/offers/${o4}/accept`);

// Cancelled exchange: dumbbells <-> mixer (items released again)
const o5 = await offer(rohan, items.dumbbells, items.mixer, 'Need some weights for home workouts. Mixer for your dumbbells?');
const ex5 = (await call(vikram.token, 'POST', `/api/offers/${o5}/accept`)).exchangeId;
await call(rohan.token, 'POST', `/api/exchanges/${ex5}/cancel`);

// Pending offers (waiting for a decision)
await offer(sneha, items.tent, items.jacket, 'Planning my first trek! Would you consider my denim jacket?');
await offer(priya, items.desk, items.lamp, 'Your desk would be perfect for my study corner. My brass lamp in return?');
await offer(rohan, items.tent, items.mixer, 'The mixer for your tent? Great for cooking at base camp, haha.');

// Declined and withdrawn offers
const o9 = await offer(vikram, items.lamp, items.dumbbells, 'Dumbbells for the lamp?');
await call(priya.token, 'POST', `/api/offers/${o9}/reject`);
const o10 = await offer(priya, items.jacket, items.lamp, 'Lamp for your jacket?');
await call(priya.token, 'POST', `/api/offers/${o10}/cancel`);
console.log('✓ offers: 2 completed exchanges, 2 active, 1 cancelled, 3 pending, 1 declined, 1 withdrawn');

// ------------------------------------------------------------------ donations & giveaways

const reqCot = await call(sneha.token, 'POST', '/api/donation-requests', { itemId: items.cribs, message: 'My sister is expecting in January. This would be a huge help!' });
await call(meera.token, 'POST', '/api/donation-requests', { itemId: items.cribs, message: 'For a friend who runs a small daycare.' });
await call(ananya.token, 'POST', `/api/donation-requests/${reqCot.id}/accept`);
await call(ananya.token, 'POST', `/api/donation-requests/${reqCot.id}/complete`);

const reqBooks = await call(karthik.token, 'POST', '/api/donation-requests', { itemId: items.novels, message: 'For the reading corner at our community centre.' });
await call(ananya.token, 'POST', `/api/donation-requests/${reqBooks.id}/accept`);

await call(vikram.token, 'POST', '/api/donation-requests', { itemId: items.monstera, message: 'My balcony needs some green! Can pick up this weekend.' });
await call(meera.token, 'POST', '/api/donation-requests', { itemId: items.monstera, message: 'I will take great care of it, promise!' });
await call(sneha.token, 'POST', '/api/donation-requests', { itemId: items.tools, message: 'Setting up my first flat. A drill would be amazing.' });
await call(priya.token, 'POST', '/api/donation-requests', { itemId: items.sofa, message: 'Furnishing a new place on a shoestring. Can pick up Saturday.' });
console.log('✓ donation requests: 1 handed over, 1 reserved, 5 pending');

// ------------------------------------------------------------------ conversations

const c1 = await say(arjun, priya, 'Hi Priya! Is the Triban still available? What size is the frame exactly?', items.roadbike);
await reply(priya, c1, 'Hey Arjun! Yes it is. Size M, suits riders around 5\'7" to 5\'11".');
await reply(arjun, c1, 'Perfect, I am 5\'9". Sent you an offer with my PS4.');
await reply(priya, c1, 'Accepted! Shall we meet at Cubbon Park gate on Sunday, 8am?');
await reply(arjun, c1, 'Deal. See you there! 🚲');

const c2 = await say(sneha, rohan, 'Hi Rohan! Does the guitar have any issues with the neck or action?', items.guitar);
await reply(rohan, c2, 'None at all, action is nice and low. I will throw in a few beginner song books too.');
await reply(sneha, c2, 'Amazing, thank you! I confirmed on my side after picking it up.');

const c3 = await say(karthik, ananya, 'Hello Ananya, thank you for accepting my request for the novels. When can I collect them?', items.novels);
await reply(ananya, c3, 'You are welcome! Any evening after 6pm works. I am near Jayanagar 4th Block.');

const c4 = await say(meera, arjun, 'Hi! When would be a good time to swap the monitor and keyboard?', items.monitor);
await reply(arjun, c4, 'How about Saturday afternoon? I can come to Whitefield.');
console.log('✓ 4 conversations with message history');

console.log(`\nDone. Demo accounts (password "${PASSWORD}"): ${Object.keys(people).map((k) => k + '@swapp.demo').join(', ')}`);
