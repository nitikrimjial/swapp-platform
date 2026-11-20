import { initPage, onRealtime } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, param, miniItem, statusBadge, userChip, timeAgo, formatDateTime, emptyState, errorState,
  loadingBlock, pagination, toast, confirmDialog, withLoading,
} from '../ui.js';

const direction = document.body.dataset.direction; // incoming | outgoing
const incoming = direction === 'incoming';
await initPage({ auth: 'required' });

const TABS = [['PENDING', 'Pending'], ['ACCEPTED', 'Accepted'], ['REJECTED', 'Declined'], ['CANCELLED', 'Cancelled'], ['', 'All']];
let status = TABS.some(([k]) => k === param('status')) ? param('status') : 'PENDING';
let page = 0;

function renderTabs() {
  render('#tabs', TABS.map(([key, label]) => html`<button class="tab ${key === status ? 'active' : ''}" role="tab" aria-selected="${String(key === status)}" data-status="${key}">${label}</button>`));
  $$('#tabs .tab').forEach((t) => t.addEventListener('click', () => {
    status = t.dataset.status;
    page = 0;
    history.replaceState(null, '', status ? `?status=${status}` : location.pathname);
    renderTabs();
    load();
  }));
}

async function load() {
  const list = $('#list');
  render(list, loadingBlock());
  render('#pager', '');
  try {
    const data = await http.get(`/api/offers/${direction}`, { status: status || undefined, page, size: 10 });
    if (!data.content.length) {
      render(list, emptyState(incoming
        ? { iconName: 'inbox', title: status === 'PENDING' ? 'No pending offers' : 'Nothing here yet', text: 'When someone offers an item for one of your listings, it will appear here.', action: html`<a class="btn btn-primary" href="/item-new.html">${icon('plus')}List an item</a>` }
        : { iconName: 'send', title: status === 'PENDING' ? 'No pending offers' : 'Nothing here yet', text: 'Find something you like and offer one of your items in exchange.', action: html`<a class="btn btn-primary" href="/browse.html?type=EXCHANGE">Browse exchange listings</a>` }));
      return;
    }
    render(list, data.content.map(offerCard));
    bindActions(list);
    pagination($('#pager'), data, (p) => { page = p; load(); });
  } catch (err) {
    render(list, errorState(err.message, load));
  }
}

function offerCard(o) {
  const other = incoming ? o.offerer : o.recipient;
  const theirs = incoming ? o.offeredItem : o.requestedItem;
  const yours = incoming ? o.requestedItem : o.offeredItem;
  return html`<article class="activity" data-id="${o.id}">
    <div class="activity-head">
      ${userChip(other, incoming ? `offered ${timeAgo(o.createdAt)}` : `you offered ${timeAgo(o.createdAt)}`)}
      ${statusBadge(o.status)}
    </div>
    <div class="swap-row">
      ${miniItem(theirs, incoming ? 'They offer' : 'You want')}
      <div class="swap-icon">${icon('swap')}</div>
      ${miniItem(yours, incoming ? 'For your' : 'You offer')}
    </div>
    ${o.message ? html`<p class="quote">${o.message}</p>` : ''}
    <div class="activity-foot">
      <span class="small muted">${o.resolutionNote && o.status !== 'PENDING' ? o.resolutionNote + ' · ' : ''}Updated ${formatDateTime(o.updatedAt)}</span>
      <div class="row-wrap">
        <a class="btn btn-ghost btn-sm" href="/messages.html?to=${other.id}&item=${theirs.id}">${icon('message')}Message</a>
        ${o.status === 'PENDING' && incoming ? html`
          <button class="btn btn-danger-ghost btn-sm" data-action="reject">Decline</button>
          <button class="btn btn-primary btn-sm" data-action="accept">${icon('check')}Accept offer</button>` : ''}
        ${o.status === 'PENDING' && !incoming ? html`<button class="btn btn-danger-ghost btn-sm" data-action="cancel">Withdraw offer</button>` : ''}
        ${o.status === 'ACCEPTED' && o.exchangeId ? html`<a class="btn btn-primary btn-sm" href="/exchanges.html?id=${o.exchangeId}">View exchange ${icon('arrowRight', 'icon-sm')}</a>` : ''}
      </div>
    </div>
  </article>`;
}

const ACTIONS = {
  accept: {
    confirm: { title: 'Accept this offer?', message: 'Both items will be reserved for this exchange, and any other pending offers involving them will be closed automatically.', confirmText: 'Accept offer', iconName: 'swap' },
    done: 'Offer accepted! Coordinate the hand-over in Exchanges.',
  },
  reject: {
    confirm: { title: 'Decline this offer?', message: 'The other person will be notified. Your item stays available.', confirmText: 'Decline', danger: true },
    done: 'Offer declined',
  },
  cancel: {
    confirm: { title: 'Withdraw your offer?', message: 'The owner will be notified that you withdrew it.', confirmText: 'Withdraw', danger: true },
    done: 'Offer withdrawn',
  },
};

function bindActions(list) {
  $$('[data-action]', list).forEach((btn) => btn.addEventListener('click', async () => {
    const id = btn.closest('[data-id]').dataset.id;
    const action = ACTIONS[btn.dataset.action];
    if (!(await confirmDialog(action.confirm))) return;
    try {
      const result = await withLoading(btn, () => http.post(`/api/offers/${id}/${btn.dataset.action}`));
      toast(action.done, 'success', result.exchangeId ? { link: `/exchanges.html?id=${result.exchangeId}` } : {});
      load();
    } catch (err) {
      toast(err.message, 'error');
      load();
    }
  }));
}

onRealtime('notification', (n) => { if (n.type.startsWith('OFFER_')) load(); });
renderTabs();
load();
