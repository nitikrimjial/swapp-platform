import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, itemCard, skeletonCards, emptyState, $, param, toast } from '../ui.js';

const { user } = await initPage({ auth: 'optional' });
if (param('deleted')) {
  toast('Your account has been deleted. Thank you for being part of Swapp.', 'success', { duration: 8000 });
  history.replaceState(null, '', '/');
}

render('#fc1-icon', icon('bike'));
render('#fc2-icon', icon('monitor'));
render('#fc3-icon', icon('book'));
render('#swap-bubble', icon('swap'));
render('#see-all-icon', icon('arrowRight', 'icon-sm'));

if (user) {
  render('#hero-eyebrow', html`Welcome back, ${user.displayName.split(' ')[0]}`);
  render('#hero-cta', html`<a class="btn btn-primary btn-lg" href="/home.html">Go to your feed</a>
    <a class="btn btn-secondary btn-lg" href="/item-new.html">${icon('plus')}List an item</a>`);
  render('#cta-band-actions', html`<a class="btn btn-accent btn-lg" href="/item-new.html">List an item now</a>`);
}

const steps = [
  ['plus', 'List an item', 'Snap a few photos, pick a category and choose whether to exchange, donate, give away or request.'],
  ['search', 'Discover', 'Browse and filter listings near you by category, condition and type.'],
  ['swap', 'Offer or request', 'Offer one of your items in exchange, or request a donation. Owners accept or decline.'],
  ['checkCircle', 'Meet & confirm', 'Coordinate in private messages. The exchange completes when both of you confirm.'],
];
render('#steps', steps.map(([ic, title, text], i) => html`<div class="feature">
  <div class="step-num">${i + 1}</div><h3>${title}</h3><p>${text}</p></div>`));

const ways = [
  ['swap', 'tint-brand', 'Exchange', 'Trade an item you no longer need for one you do. Make offers and accept the best one.', '/browse.html?type=EXCHANGE'],
  ['heart', 'tint-donation', 'Donate', 'Pass things on to people and causes who need them. You choose who receives it.', '/giveaways.html'],
  ['gift', 'tint-accent', 'Give away', 'Clearing out? List it for free and let a neighbour collect it.', '/giveaways.html'],
  ['hand', 'tint-request', 'Request', "Looking for something? Post a request and let the community help you out.", '/browse.html?type=REQUEST'],
];
render('#ways', ways.map(([ic, tint, title, text, href]) => html`<a class="feature" href="${href}" style="color:inherit;text-decoration:none">
  <div class="f-icon ${tint}">${icon(ic, 'icon-lg')}</div><h3>${title}</h3><p>${text}</p></a>`));

const trust = [
  ['shield', 'tint-brand', 'Secure accounts', 'Passwords are hashed and sessions use secure, HttpOnly cookies.'],
  ['users', 'tint-success', 'Two-sided confirmation', 'Exchanges only complete when both people confirm the hand-over.'],
  ['message', 'tint-request', 'Private messaging', 'Chat privately to agree on time and place. Only participants can read it.'],
  ['bell', 'tint-accent', 'Real-time updates', 'Get notified the moment someone makes, accepts or declines an offer.'],
];
render('#trust', trust.map(([ic, tint, title, text]) => html`<div class="feature">
  <div class="f-icon ${tint}">${icon(ic, 'icon-lg')}</div><h3>${title}</h3><p>${text}</p></div>`));

const fresh = $('#fresh-items');
render(fresh, skeletonCards(4));

try {
  const [stats, items] = await Promise.all([http.get('/api/stats'), http.get('/api/items', { size: 8 })]);
  const fmt = (n) => new Intl.NumberFormat().format(n);
  render('#hero-stats', html`
    <div><strong>${fmt(stats.activeListings)}</strong><span>active listings</span></div>
    <div><strong>${fmt(stats.members)}</strong><span>members</span></div>
    <div><strong>${fmt(stats.completedSwaps)}</strong><span>completed swaps</span></div>`);
  if (items.content.length) {
    render(fresh, items.content.map((i) => itemCard(i)));
  } else {
    render(fresh, emptyState({
      iconName: 'sparkles', title: 'Be the first to list something',
      text: 'The community is just getting started. Your listing could be the first one people see.',
      action: html`<a class="btn btn-primary" href="${user ? '/item-new.html' : '/register.html'}">List an item</a>`,
    }));
    fresh.classList.remove('item-grid');
  }
} catch {
  $('#fresh-section').classList.add('hidden');
}
