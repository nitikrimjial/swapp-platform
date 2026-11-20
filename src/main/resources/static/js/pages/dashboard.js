import { initPage, onRealtime } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, itemCard, miniItem, avatar, timeAgo, emptyState, loadingBlock, safeNext } from '../ui.js';

const { user } = await initPage({ auth: 'required', active: 'dashboard' });
render('#title', html`Hi, ${user.displayName.split(' ')[0]}`);
render('#new-btn', html`${icon('plus')}List an item`);

render('#quick', [
  ['/item-new.html', 'plus', 'tint-brand', 'List an item'],
  ['/browse.html', 'search', 'tint-request', 'Browse items'],
  ['/offers-outgoing.html', 'send', 'tint-accent', 'My offers'],
  ['/giveaways.html?tab=incoming', 'gift', 'tint-donation', 'Donation requests'],
  ['/messages.html', 'message', 'tint-success', 'Messages'],
  ['/settings.html', 'settings', 'tint-warning', 'Settings'],
].map(([href, ic, tint, label]) => html`<a class="quick-action" href="${href}"><span class="qa-icon ${tint}">${icon(ic)}</span>${label}</a>`));

['#attention', '#exchanges', '#activity'].forEach((s) => render(s, loadingBlock()));

async function loadAll() {
  const [summary, offers, exchanges, items, notifications] = await Promise.all([
    http.get('/api/users/me/summary'),
    http.get('/api/offers/incoming', { status: 'PENDING', size: 3 }),
    http.get('/api/exchanges', { status: 'ACTIVE', size: 3 }),
    http.get('/api/users/me/items', { size: 4 }),
    http.get('/api/notifications', { size: 6 }),
  ]);

  render('#stats', [
    ['/my-listings.html', 'package', 'tint-brand', summary.activeListings, 'Active listings'],
    ['/offers-incoming.html', 'inbox', 'tint-accent', summary.pendingIncomingOffers, 'Offers to review'],
    ['/exchanges.html', 'swap', 'tint-success', summary.activeExchanges, 'Active exchanges'],
    ['/giveaways.html?tab=incoming', 'gift', 'tint-donation', summary.pendingDonationRequests, 'Donation requests'],
    ['/messages.html', 'message', 'tint-request', summary.unreadMessages, 'Unread messages'],
  ].map(([href, ic, tint, value, label]) => html`<a class="stat" href="${href}"><span class="stat-icon ${tint}">${icon(ic)}</span>
    <span class="stat-value">${value}</span><span class="stat-label">${label}</span></a>`));

  render('#attention', offers.content.length
    ? offers.content.map((o) => html`<div class="list-row">
        ${avatar(o.offerer, 'sm')}
        <div style="flex:1;min-width:0"><strong>${o.offerer.displayName}</strong> <span class="muted">offered</span> <strong>${o.offeredItem.title}</strong>
          <span class="muted">for your</span> <strong>${o.requestedItem.title}</strong>
          <div class="small muted">${timeAgo(o.createdAt)}</div></div>
        <a class="btn btn-secondary btn-sm" href="/offers-incoming.html">Review</a></div>`)
    : html`<p class="muted" style="margin:0">${icon('checkCircle', 'icon-sm')} No offers waiting for you.</p>`);

  render('#exchanges', exchanges.content.length
    ? exchanges.content.map((e) => {
      const iAmOwner = e.owner.id === user.id;
      const other = iAmOwner ? e.requester : e.owner;
      const confirmed = iAmOwner ? e.ownerConfirmedAt : e.requesterConfirmedAt;
      return html`<div class="list-row">
        <div class="swap-row" style="flex:1;min-width:0">${miniItem(iAmOwner ? e.ownerItem : e.requesterItem, 'You give')}${miniItem(iAmOwner ? e.requesterItem : e.ownerItem, 'You get')}</div>
        <div style="text-align:right"><div class="small muted">with ${other.displayName}</div>
          <a class="btn ${confirmed ? 'btn-secondary' : 'btn-primary'} btn-sm" href="/exchanges.html?id=${e.id}">${confirmed ? 'Waiting' : 'Confirm'}</a></div>
      </div>`;
    })
    : html`<p class="muted" style="margin:0">No active exchanges right now.</p>`);

  const grid = $('#listings');
  if (items.content.length) {
    render(grid, items.content.map((i) => itemCard(i, { showStatus: true })));
  } else {
    grid.className = '';
    render(grid, emptyState({ iconName: 'package', title: 'You have no listings yet', text: 'List something you no longer need. It takes a minute.', action: html`<a class="btn btn-primary" href="/item-new.html">${icon('plus')}List an item</a>` }));
  }

  render('#activity', notifications.content.length
    ? notifications.content.map((n) => html`<a class="list-row" href="${safeNext(n.link, '/notifications.html')}" style="color:inherit;text-decoration:none">
        <span class="unread-dot" style="${n.read ? 'visibility:hidden' : ''}"></span>
        <div style="min-width:0"><strong class="small">${n.title}</strong><div class="small muted">${timeAgo(n.createdAt)}</div></div></a>`)
    : html`<p class="muted" style="margin:0">No activity yet.</p>`);
}

try {
  await loadAll();
} catch (err) {
  render('#attention', html`<p class="error-state">${err.message}</p>`);
}
onRealtime('notification', () => loadAll().catch(() => {}));
