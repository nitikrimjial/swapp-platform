import { initPage, onRealtime } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, param, miniItem, statusBadge, userChip, formatDateTime, emptyState, errorState,
  loadingBlock, pagination, toast, confirmDialog, withLoading,
} from '../ui.js';

const { user } = await initPage({ auth: 'required' });

const TABS = [['ACTIVE', 'Active'], ['COMPLETED', 'Completed'], ['CANCELLED', 'Cancelled'], ['', 'All']];
const focusId = Number(param('id')) || null;
let status = TABS.some(([k]) => k === param('status')) ? param('status') : (focusId ? '' : 'ACTIVE');
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
    const data = await http.get('/api/exchanges', { status: status || undefined, page, size: 10 });
    let items = data.content;
    if (focusId && page === 0 && !items.some((e) => e.id === focusId)) {
      try { items = [await http.get(`/api/exchanges/${focusId}`), ...items]; } catch { /* not visible */ }
    }
    if (!items.length) {
      render(list, emptyState({
        iconName: 'swap', title: status === 'ACTIVE' ? 'No active exchanges' : 'No exchanges here',
        text: 'When you accept an offer, or someone accepts yours, the exchange will be tracked here.',
        action: html`<a class="btn btn-primary" href="/offers-incoming.html">Review incoming offers</a>`,
      }));
      return;
    }
    render(list, items.map(card));
    bind(list);
    pagination($('#pager'), data, (p) => { page = p; load(); });
    const focused = focusId && $(`[data-id="${focusId}"]`, list);
    if (focused) focused.scrollIntoView({ behavior: 'smooth', block: 'center' });
  } catch (err) {
    render(list, errorState(err.message, load));
  }
}

function card(e) {
  const iAmOwner = e.owner.id === user.id;
  const other = iAmOwner ? e.requester : e.owner;
  const myItem = iAmOwner ? e.ownerItem : e.requesterItem;
  const theirItem = iAmOwner ? e.requesterItem : e.ownerItem;
  const myConfirm = iAmOwner ? e.ownerConfirmedAt : e.requesterConfirmedAt;
  const theirConfirm = iAmOwner ? e.requesterConfirmedAt : e.ownerConfirmedAt;
  const first = other.displayName.split(' ')[0];

  const steps = [
    { done: true, text: html`Offer accepted · ${formatDateTime(e.createdAt)}` },
    { done: !!myConfirm, text: myConfirm ? html`You confirmed the hand-over · ${formatDateTime(myConfirm)}` : 'Waiting for your confirmation' },
    { done: !!theirConfirm, text: theirConfirm ? html`${first} confirmed · ${formatDateTime(theirConfirm)}` : html`Waiting for ${first} to confirm` },
  ];
  if (e.status === 'COMPLETED') steps.push({ done: true, text: html`Exchange completed · ${formatDateTime(e.completedAt)}` });
  if (e.status === 'CANCELLED') steps.push({ cancel: true, text: html`Cancelled by ${e.cancelledBy && e.cancelledBy.id === user.id ? 'you' : e.cancelledBy ? e.cancelledBy.displayName : 'a participant'} · ${formatDateTime(e.cancelledAt)}` });

  return html`<article class="activity ${e.id === focusId ? 'highlight' : ''}" data-id="${e.id}">
    <div class="activity-head">${userChip(other, 'Exchange partner')}${statusBadge(e.status)}</div>
    <div class="swap-row">
      ${miniItem(myItem, 'You give')}
      <div class="swap-icon">${icon('swap')}</div>
      ${miniItem(theirItem, 'You receive')}
    </div>
    <ul class="timeline">${steps.map((s) => html`<li class="${s.done ? 'done' : s.cancel ? 'cancel' : ''}"><span class="dot">${icon(s.cancel ? 'x' : s.done ? 'check' : 'clock')}</span>${s.text}</li>`)}</ul>
    <div class="activity-foot">
      <span class="small muted">Exchange #${e.id}</span>
      <div class="row-wrap">
        <a class="btn btn-ghost btn-sm" href="/messages.html?to=${other.id}&item=${theirItem.id}">${icon('message')}Message ${first}</a>
        ${e.status === 'ACTIVE' ? html`
          <button class="btn btn-danger-ghost btn-sm" data-action="cancel">Cancel exchange</button>
          ${myConfirm ? html`<span class="badge badge-success">${icon('check')}You confirmed</span>`
            : html`<button class="btn btn-primary btn-sm" data-action="confirm">${icon('checkCircle')}Mark as exchanged</button>`}` : ''}
      </div>
    </div>
  </article>`;
}

function bind(list) {
  $$('[data-action]', list).forEach((btn) => btn.addEventListener('click', async () => {
    const id = btn.closest('[data-id]').dataset.id;
    const confirm = btn.dataset.action === 'confirm';
    const ok = await confirmDialog(confirm
      ? { title: 'Confirm the hand-over?', message: 'Only confirm once you have received the other item. The exchange completes when both of you confirm, and this cannot be undone.', confirmText: 'Yes, we exchanged', iconName: 'checkCircle' }
      : { title: 'Cancel this exchange?', message: 'Both items will become available again and the other person will be notified.', confirmText: 'Cancel exchange', danger: true });
    if (!ok) return;
    try {
      const result = await withLoading(btn, () => http.post(`/api/exchanges/${id}/${btn.dataset.action}`));
      toast(confirm ? (result.status === 'COMPLETED' ? 'Exchange completed. Enjoy your new item!' : 'Confirmed. Waiting for the other person.') : 'Exchange cancelled');
      load();
    } catch (err) {
      toast(err.message, 'error');
      load();
    }
  }));
}

onRealtime('notification', (n) => { if (n.type.startsWith('EXCHANGE_') || n.type === 'OFFER_ACCEPTED') load(); });
renderTabs();
load();
