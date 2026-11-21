import { initPage } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, param, avatar, itemCard, miniItem, statusBadge, formatDate, emptyState, errorState,
  skeletonCards, loadingBlock, pagination, toast,
} from '../ui.js';
import { reportDialog, toggleBlock } from '../safety.js';

const { user } = await initPage({ auth: 'optional' });
const requested = Number(param('id'));
const id = requested || (user && user.id);

if (!id) {
  location.replace('/login.html?next=' + encodeURIComponent('/profile.html'));
  await new Promise(() => {});
}
const own = user && user.id === id;
let tab = 'listings';
let page = 0;

try {
  const p = await http.get(`/api/users/${id}`);
  document.title = `${p.displayName} · Swapp`;
  render('#profile-head', html`<div class="profile-head">
    ${avatar(p, 'xl')}
    <div class="info">
      <h1>${p.displayName}</h1>
      <div class="row-wrap muted small">
        ${p.location ? html`<span class="row" style="gap:5px">${icon('pin', 'icon-sm')}${p.location}</span>` : ''}
        <span class="row" style="gap:5px">${icon('calendar', 'icon-sm')}Member since ${formatDate(p.memberSince)}</span>
      </div>
      ${p.bio ? html`<p style="margin:12px 0 0;max-width:640px">${p.bio}</p>` : own ? html`<p class="muted" style="margin:12px 0 0">Add a short bio so people know who they're swapping with.</p>` : ''}
      <div class="profile-stats">
        <div><strong>${p.activeListings}</strong><span>Active listings</span></div>
        <div><strong>${p.completedExchanges}</strong><span>Completed exchanges</span></div>
        <div><strong>${p.itemsGivenAway}</strong><span>Items given away</span></div>
      </div>
    </div>
    <div class="row-wrap">
      ${own ? html`<a class="btn btn-secondary" href="/settings.html">${icon('edit')}Edit profile</a>`
        : html`<button class="btn btn-primary" id="message-btn">${icon('message')}Message</button>`}
      ${user && !own ? html`<button class="btn btn-ghost btn-sm" id="block-btn"></button>
        <button class="btn btn-ghost btn-sm" id="report-btn">${icon('alert', 'icon-sm')}Report</button>` : ''}
    </div>
  </div>`);
  const blockBtn = $('#block-btn');
  if (blockBtn) {
    let blocked = Boolean(p.blockedByMe);
    const label = () => render(blockBtn, html`${icon('shield', 'icon-sm')}${blocked ? 'Unblock' : 'Block'}`);
    label();
    blockBtn.addEventListener('click', async () => {
      try {
        const next = await toggleBlock(p, blocked);
        if (next !== null) { blocked = next; label(); }
      } catch (err) {
        toast(err.message, 'error');
      }
    });
    $('#report-btn').addEventListener('click', () => reportDialog('USER', p.id).catch((err) => toast(err.message, 'error')));
  }
  const mb = $('#message-btn');
  if (mb) mb.addEventListener('click', () => {
    if (!user) { location.href = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`; return; }
    location.href = `/messages.html?to=${p.id}`;
  });
  renderTabs();
  load();
} catch (err) {
  render('#profile-head', err.status === 404 ? emptyState({ iconName: 'user', title: 'User not found' }) : errorState(err.message, () => location.reload()));
}

function renderTabs() {
  const tabs = [['listings', own ? 'My available listings' : 'Available listings']];
  if (own) tabs.push(['history', 'Exchange history']);
  render('#tabs', tabs.map(([k, l]) => html`<button class="tab ${k === tab ? 'active' : ''}" role="tab" data-tab="${k}">${l}</button>`));
  $$('#tabs .tab').forEach((t) => t.addEventListener('click', () => { tab = t.dataset.tab; page = 0; renderTabs(); load(); }));
}

async function load() {
  const box = $('#tab-content');
  render('#pager', '');
  if (tab === 'listings') {
    box.className = 'item-grid';
    render(box, skeletonCards(4));
    try {
      const data = await http.get('/api/items', { ownerId: id, page, size: 12 });
      if (!data.content.length) {
        box.className = '';
        render(box, emptyState({ iconName: 'package', title: 'No available listings', text: own ? 'List something to share with the community.' : 'Check back later.', action: own ? html`<a class="btn btn-primary" href="/item-new.html">List an item</a>` : '' }));
        return;
      }
      render(box, data.content.map((i) => itemCard(i)));
      pagination($('#pager'), data, (p) => { page = p; load(); });
    } catch (err) {
      box.className = '';
      render(box, errorState(err.message, load));
    }
    return;
  }
  box.className = 'activity-list';
  render(box, loadingBlock());
  try {
    const data = await http.get('/api/exchanges', { page, size: 10 });
    if (!data.content.length) {
      render(box, emptyState({ iconName: 'swap', title: 'No exchanges yet', text: 'Your accepted, completed and cancelled exchanges will be listed here.' }));
      return;
    }
    render(box, data.content.map((e) => {
      const iAmOwner = e.owner.id === user.id;
      const other = iAmOwner ? e.requester : e.owner;
      return html`<a class="activity" href="/exchanges.html?id=${e.id}" style="display:block;color:inherit;text-decoration:none">
        <div class="activity-head"><span>With <strong>${other.displayName}</strong> · <span class="muted small">${formatDate(e.completedAt || e.cancelledAt || e.createdAt)}</span></span>${statusBadge(e.status)}</div>
        <div class="swap-row">${miniItem(iAmOwner ? e.ownerItem : e.requesterItem, 'You gave')}<div class="swap-icon">${icon('swap')}</div>${miniItem(iAmOwner ? e.requesterItem : e.ownerItem, 'You received')}</div>
      </a>`;
    }));
    pagination($('#pager'), data, (p) => { page = p; load(); });
  } catch (err) {
    render(box, errorState(err.message, load));
  }
}
