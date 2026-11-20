import { initPage, onRealtime } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, param, itemCard, miniItem, statusBadge, userChip, timeAgo, formatDateTime,
  emptyState, errorState, loadingBlock, skeletonCards, pagination, toast, confirmDialog, withLoading,
} from '../ui.js';

const { user } = await initPage({ auth: 'optional', active: 'giveaways' });
render('#give-btn', html`${icon('gift')}Give something away`);

const TABS = [['browse', 'Free items', 'gift'], ['incoming', 'Requests for my items', 'inbox'], ['outgoing', 'My requests', 'send']];
let tab = TABS.some(([k]) => k === param('tab')) ? param('tab') : 'browse';
if (!user && tab !== 'browse') tab = 'browse';
let page = 0;
let typeFilter = '';
let statusFilter = '';

function renderTabs() {
  const visible = user ? TABS : TABS.slice(0, 1);
  render('#tabs', visible.map(([key, label, ic]) => html`<button class="tab ${key === tab ? 'active' : ''}" role="tab" data-tab="${key}">${icon(ic, 'icon-sm')}${label}</button>`));
  $$('#tabs .tab').forEach((t) => t.addEventListener('click', () => {
    tab = t.dataset.tab;
    page = 0;
    statusFilter = '';
    history.replaceState(null, '', tab === 'browse' ? location.pathname : `?tab=${tab}`);
    renderTabs();
    load();
  }));
}

function renderFilters() {
  if (tab === 'browse') {
    const opts = [['', 'All free items'], ['GIVEAWAY', 'Free giveaways'], ['DONATION', 'Donations']];
    render('#filters', html`<div class="chips" style="margin-bottom:18px">${opts.map(([k, l]) => html`<button class="chip ${k === typeFilter ? 'active' : ''}" data-type="${k}">${l}</button>`)}</div>`);
    $$('#filters [data-type]').forEach((b) => b.addEventListener('click', () => { typeFilter = b.dataset.type; page = 0; renderFilters(); load(); }));
  } else {
    const opts = [['', 'All'], ['PENDING', 'Pending'], ['ACCEPTED', 'Accepted'], ['COMPLETED', 'Completed'], ['DECLINED', 'Declined'], ['CANCELLED', 'Cancelled']];
    render('#filters', html`<div class="chips" style="margin-bottom:18px">${opts.map(([k, l]) => html`<button class="chip ${k === statusFilter ? 'active' : ''}" data-status="${k}">${l}</button>`)}</div>`);
    $$('#filters [data-status]').forEach((b) => b.addEventListener('click', () => { statusFilter = b.dataset.status; page = 0; renderFilters(); load(); }));
  }
}

async function load() {
  renderFilters();
  const content = $('#content');
  render('#pager', '');
  if (tab === 'browse') {
    content.className = 'item-grid';
    render(content, skeletonCards(8));
    try {
      const data = await http.get('/api/items', { type: typeFilter ? [typeFilter] : ['GIVEAWAY', 'DONATION'], page, size: 12 });
      if (!data.content.length) {
        content.className = '';
        render(content, emptyState({ iconName: 'gift', title: 'No free items right now', text: 'Check back soon, or be the first to give something away.', action: html`<a class="btn btn-primary" href="/item-new.html?type=GIVEAWAY">Give something away</a>` }));
        return;
      }
      render(content, data.content.map((i) => itemCard(i)));
      pagination($('#pager'), data, (p) => { page = p; load(); });
    } catch (err) {
      content.className = '';
      render(content, errorState(err.message, load));
    }
    return;
  }

  content.className = 'activity-list';
  render(content, loadingBlock());
  try {
    const data = await http.get(`/api/donation-requests/${tab}`, { status: statusFilter || undefined, page, size: 10 });
    if (!data.content.length) {
      render(content, emptyState(tab === 'incoming'
        ? { iconName: 'inbox', title: 'No requests yet', text: 'When someone asks for an item you are donating or giving away, it will appear here.' }
        : { iconName: 'send', title: 'No requests yet', text: 'Request a free item and the owner will be notified.', action: html`<button class="btn btn-primary" id="go-browse">Browse free items</button>` }));
      const go = $('#go-browse');
      if (go) go.addEventListener('click', () => { tab = 'browse'; renderTabs(); load(); });
      return;
    }
    render(content, data.content.map(requestCard));
    bind(content);
    pagination($('#pager'), data, (p) => { page = p; load(); });
  } catch (err) {
    render(content, errorState(err.message, load));
  }
}

function requestCard(r) {
  const mine = tab === 'outgoing';
  const other = mine ? r.owner : r.requester;
  const first = other.displayName.split(' ')[0];
  let actions = '';
  if (!mine && r.status === 'PENDING') {
    actions = html`<button class="btn btn-danger-ghost btn-sm" data-action="decline">Decline</button>
      <button class="btn btn-primary btn-sm" data-action="accept">${icon('check')}Give to ${first}</button>`;
  } else if (!mine && r.status === 'ACCEPTED') {
    actions = html`<button class="btn btn-danger-ghost btn-sm" data-action="cancel">Call off</button>
      <button class="btn btn-primary btn-sm" data-action="complete">${icon('checkCircle')}Mark handed over</button>`;
  } else if (mine && (r.status === 'PENDING' || r.status === 'ACCEPTED')) {
    actions = html`<button class="btn btn-danger-ghost btn-sm" data-action="cancel">Withdraw request</button>`;
  }
  return html`<article class="activity" data-id="${r.id}">
    <div class="activity-head">${userChip(other, mine ? `owner · requested ${timeAgo(r.createdAt)}` : `requested ${timeAgo(r.createdAt)}`)}${statusBadge(r.status)}</div>
    ${miniItem(r.item, r.item.listingType === 'DONATION' ? 'Donation' : 'Free giveaway')}
    ${r.message ? html`<p class="quote">${r.message}</p>` : ''}
    ${mine && r.status === 'ACCEPTED' ? html`<div class="banner success" style="margin:14px 0 0">${icon('checkCircle')}<span>${first} reserved this item for you. Message them to arrange pick-up.</span></div>` : ''}
    <div class="activity-foot">
      <span class="small muted">${r.resolutionNote && r.status !== 'PENDING' ? r.resolutionNote + ' · ' : ''}Updated ${formatDateTime(r.updatedAt)}</span>
      <div class="row-wrap"><a class="btn btn-ghost btn-sm" href="/messages.html?to=${other.id}&item=${r.item.id}">${icon('message')}Message</a>${actions}</div>
    </div>
  </article>`;
}

const ACTIONS = {
  accept: { confirm: { title: 'Give the item to this person?', message: 'The item will be reserved for them and other pending requests will be declined automatically.', confirmText: 'Accept request', iconName: 'gift' }, done: 'Request accepted. Arrange the pick-up in messages.' },
  decline: { confirm: { title: 'Decline this request?', message: 'They will be notified. The item stays available.', confirmText: 'Decline', danger: true }, done: 'Request declined' },
  cancel: { confirm: { title: 'Cancel this request?', message: 'The other person will be notified. If the item was reserved, it becomes available again.', confirmText: 'Yes, cancel', danger: true }, done: 'Request cancelled' },
  complete: { confirm: { title: 'Mark as handed over?', message: 'Confirm that the item has been collected. The listing will be marked completed.', confirmText: 'Mark handed over', iconName: 'checkCircle' }, done: 'Marked as handed over. Thank you for sharing!' },
};

function bind(root) {
  $$('[data-action]', root).forEach((btn) => btn.addEventListener('click', async () => {
    const id = btn.closest('[data-id]').dataset.id;
    const action = ACTIONS[btn.dataset.action];
    if (!(await confirmDialog(action.confirm))) return;
    try {
      await withLoading(btn, () => http.post(`/api/donation-requests/${id}/${btn.dataset.action}`));
      toast(action.done);
      load();
    } catch (err) {
      toast(err.message, 'error');
      load();
    }
  }));
}

onRealtime('notification', (n) => { if (n.type.startsWith('DONATION_') && tab !== 'browse') load(); });
renderTabs();
load();
