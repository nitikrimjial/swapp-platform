import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, itemCard, skeletonCards, emptyState, param, toast, $ } from '../ui.js';
import { currentPosition, reverseGeocode, savedOrigin, saveOrigin } from '../location.js';

const { user } = await initPage({ auth: 'optional', active: 'home' });
const NEAR_RADIUS_KM = 10;

render('#search-icon', icon('search'));
if (user) {
  const hour = new Date().getHours();
  // Late night (before 5am) reads as evening, not morning.
  const part = hour >= 5 && hour < 12 ? 'Good morning' : hour >= 12 && hour < 17 ? 'Good afternoon' : 'Good evening';
  render('#greeting', html`${part}, ${user.displayName.split(' ')[0]}`);
}
if (param('welcome') && user) {
  render('#welcome', html`<div class="banner success">${icon('sparkles')}<div><strong>Welcome to Swapp!</strong>
    Start by <a href="/item-new.html">listing something you no longer use</a> or browse what your neighbours are sharing.</div></div>`);
}

const categories = await http.get('/api/categories').catch(() => []);
render('#categories', categories.map((c) => html`<a class="chip" href="/browse.html?categoryId=${c.id}">${icon(c.icon)}${c.name}</a>`));

// ---------------------------------------------------------------- near you

function homeOrigin() {
  if (user && user.latitude !== null && user.latitude !== undefined) {
    return { lat: user.latitude, lng: user.longitude, name: user.location || 'your home' };
  }
  return savedOrigin();
}

async function renderNear() {
  const box = $('#near');
  const origin = homeOrigin();
  if (!origin) {
    render(box, html`<div class="near-cta">
      <span class="near-icon">${icon('pin', 'icon-lg')}</span>
      <div class="near-text"><h3>See what's near you</h3><p>Share your approximate location to find items within ${NEAR_RADIUS_KM} km. It stays on this device unless you save it in Settings.</p></div>
      <div class="row-wrap"><button class="btn btn-primary" id="near-gps">${icon('pin')}Use my location</button>
        <a class="btn btn-ghost" href="/browse.html">Choose a place</a></div></div>`);
    $('#near-gps').addEventListener('click', async (e) => {
      const btn = e.currentTarget;
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner"></span>Locating…';
      try {
        const pos = await currentPosition();
        saveOrigin({ ...pos, name: (await reverseGeocode(pos.lat, pos.lng)) || 'My location' });
        renderNear();
      } catch (err) {
        toast(err.message, 'error');
        renderNear();
      }
    });
    return;
  }
  const browseHref = `/browse.html?lat=${origin.lat}&lng=${origin.lng}&place=${encodeURIComponent(origin.name)}&radius=${NEAR_RADIUS_KM}&sort=distance`;
  render(box, html`<div class="section-head"><h2>Near ${origin.name}</h2><a href="${browseHref}">See all nearby ${icon('arrowRight', 'icon-sm')}</a></div>
    <div class="item-grid" data-grid>${skeletonCards(4)}</div>`);
  const grid = box.querySelector('[data-grid]');
  try {
    const page = await http.get('/api/items', { lat: origin.lat, lng: origin.lng, radiusKm: NEAR_RADIUS_KM, sort: 'distance', size: 8, excludeMine: true });
    if (page.content.length) {
      render(grid, page.content.map((i) => itemCard(i)));
    } else {
      grid.className = '';
      render(grid, emptyState({ iconName: 'pin', title: `Nothing within ${NEAR_RADIUS_KM} km yet`, text: 'Be the first to list something in your area, or widen your search.',
        action: html`<a class="btn btn-secondary" href="${browseHref.replace(`radius=${NEAR_RADIUS_KM}`, 'radius=50')}">Search within 50 km</a>` }));
    }
  } catch {
    box.innerHTML = '';
  }
}

// ---------------------------------------------------------------- other sections

const sections = [
  { key: 'new', title: 'Newest listings', query: {}, href: '/browse.html' },
  { key: 'exchange', title: 'Up for exchange', query: { type: 'EXCHANGE' }, href: '/browse.html?type=EXCHANGE' },
  { key: 'free', title: 'Free & donations', query: { type: ['GIVEAWAY', 'DONATION'] }, href: '/giveaways.html' },
  { key: 'request', title: 'People are looking for', query: { type: 'REQUEST' }, href: '/browse.html?type=REQUEST' },
];

render('#sections', sections.map((s) => html`<section class="section" id="sec-${s.key}">
  <div class="section-head"><h2>${s.title}</h2><a href="${s.href}">See all ${icon('arrowRight', 'icon-sm')}</a></div>
  <div class="item-grid" data-grid>${skeletonCards(4)}</div></section>`));

renderNear();
let anyContent = false;
await Promise.all(sections.map(async (s) => {
  const section = $(`#sec-${s.key}`);
  const grid = section.querySelector('[data-grid]');
  try {
    const page = await http.get('/api/items', { ...s.query, size: 8, excludeMine: true });
    if (!page.content.length) { section.remove(); return; }
    anyContent = true;
    render(grid, page.content.map((i) => itemCard(i)));
  } catch {
    section.remove();
  }
}));

if (!anyContent) {
  render('#sections', html`<div class="section">${emptyState({
    iconName: 'sparkles', title: 'Nothing listed yet',
    text: 'Be the first in your community to share something. It only takes a minute.',
    action: html`<a class="btn btn-primary" href="/item-new.html">${icon('plus')}List an item</a>`,
  })}</div>`);
}
