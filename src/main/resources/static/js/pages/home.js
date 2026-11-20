import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, itemCard, skeletonCards, emptyState, param, $ } from '../ui.js';

const { user } = await initPage({ auth: 'optional', active: 'home' });

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

const sections = [
  { key: 'near', title: user && user.location ? `New near ${user.location}` : 'Newest listings', query: { location: user && user.location ? user.location : undefined }, href: user && user.location ? `/browse.html?location=${encodeURIComponent(user.location)}` : '/browse.html' },
  { key: 'exchange', title: 'Up for exchange', query: { type: 'EXCHANGE' }, href: '/browse.html?type=EXCHANGE' },
  { key: 'free', title: 'Free & donations', query: { type: ['GIVEAWAY', 'DONATION'] }, href: '/giveaways.html' },
  { key: 'request', title: 'People are looking for', query: { type: 'REQUEST' }, href: '/browse.html?type=REQUEST' },
];

render('#sections', sections.map((s) => html`<section class="section" id="sec-${s.key}">
  <div class="section-head"><h2>${s.title}</h2><a href="${s.href}">See all ${icon('arrowRight', 'icon-sm')}</a></div>
  <div class="item-grid" data-grid>${skeletonCards(4)}</div></section>`));

let anyContent = false;
await Promise.all(sections.map(async (s) => {
  const section = $(`#sec-${s.key}`);
  const grid = section.querySelector('[data-grid]');
  try {
    const page = await http.get('/api/items', { ...s.query, size: 8, excludeMine: true });
    if (!page.content.length) {
      if (s.key === 'near' && s.query.location) {
        // Nothing local yet: fall back to everything.
        const all = await http.get('/api/items', { size: 8, excludeMine: true });
        if (all.content.length) {
          section.querySelector('h2').textContent = 'Newest listings';
          render(grid, all.content.map((i) => itemCard(i)));
          anyContent = true;
          return;
        }
      }
      section.remove();
      return;
    }
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
