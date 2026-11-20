import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, $$, param, itemCard, emptyState, errorState, skeletonCards, pagination, toast, confirmDialog, withLoading } from '../ui.js';

await initPage({ auth: 'required' });
render('#new-btn', html`${icon('plus')}List an item`);

const TABS = [['', 'All'], ['AVAILABLE', 'Available'], ['RESERVED', 'Reserved'], ['UNAVAILABLE', 'Hidden'], ['COMPLETED', 'Completed']];
let status = TABS.some(([k]) => k === param('status')) ? param('status') : '';
let page = 0;
const grid = $('#grid');

function renderTabs() {
  render('#tabs', TABS.map(([k, l]) => html`<button class="tab ${k === status ? 'active' : ''}" role="tab" data-status="${k}">${l}</button>`));
  $$('#tabs .tab').forEach((t) => t.addEventListener('click', () => {
    status = t.dataset.status;
    page = 0;
    history.replaceState(null, '', status ? `?status=${status}` : location.pathname);
    renderTabs();
    load();
  }));
}

function actions(item) {
  if (item.status === 'RESERVED') {
    return html`<div class="item-actions"><a class="btn btn-secondary btn-sm btn-block" href="${item.listingType === 'EXCHANGE' ? '/exchanges.html' : '/giveaways.html?tab=incoming'}">${icon('clock')}View hand-over</a></div>`;
  }
  if (item.status === 'COMPLETED') return '';
  return html`<div class="item-actions" data-id="${item.id}">
    <a class="btn btn-secondary btn-sm" href="/item-edit.html?id=${item.id}" aria-label="Edit ${item.title}">${icon('edit')}Edit</a>
    <button class="btn btn-ghost btn-sm" data-action="toggle" data-status="${item.status}">${item.status === 'AVAILABLE' ? html`${icon('eyeOff')}Hide` : html`${icon('eye')}Show`}</button>
    <button class="btn btn-ghost btn-sm btn-icon" data-action="delete" aria-label="Delete ${item.title}" style="margin-left:auto;color:var(--danger)">${icon('trash')}</button>
  </div>`;
}

async function load() {
  grid.className = 'item-grid';
  render(grid, skeletonCards(8));
  render('#pager', '');
  try {
    const data = await http.get('/api/users/me/items', { status: status || undefined, page, size: 12 });
    if (!data.content.length) {
      grid.className = '';
      render(grid, emptyState({ iconName: 'package', title: status ? 'No listings with this status' : "You haven't listed anything yet", text: 'Listing takes a minute. Add photos and a short description and you are done.', action: html`<a class="btn btn-primary" href="/item-new.html">${icon('plus')}List an item</a>` }));
      return;
    }
    render(grid, data.content.map((i) => itemCard(i, { showStatus: true, actions: actions(i) })));
    bind(data.content);
    pagination($('#pager'), data, (p) => { page = p; load(); });
  } catch (err) {
    grid.className = '';
    render(grid, errorState(err.message, load));
  }
}

function bind(items) {
  $$('[data-action]', grid).forEach((btn) => btn.addEventListener('click', async () => {
    const id = Number(btn.closest('[data-id]').dataset.id);
    const item = items.find((i) => i.id === id);
    if (btn.dataset.action === 'toggle') {
      const target = item.status === 'AVAILABLE' ? 'UNAVAILABLE' : 'AVAILABLE';
      if (target === 'UNAVAILABLE' && !(await confirmDialog({ title: 'Hide this listing?', message: 'It will disappear from search and any pending offers or requests will be closed.', confirmText: 'Hide listing' }))) return;
      try {
        await withLoading(btn, () => http.patch(`/api/items/${id}/status`, { status: target }));
        toast(target === 'AVAILABLE' ? 'Listing is visible again' : 'Listing hidden');
        load();
      } catch (err) { toast(err.message, 'error'); }
    } else {
      if (!(await confirmDialog({ title: 'Delete this listing?', message: `"${item.title}" will be removed. Pending offers and requests will be closed.`, confirmText: 'Delete', danger: true }))) return;
      try {
        await withLoading(btn, () => http.del(`/api/items/${id}`));
        toast('Listing deleted');
        load();
      } catch (err) { toast(err.message, 'error'); }
    }
  }));
}

renderTabs();
load();
