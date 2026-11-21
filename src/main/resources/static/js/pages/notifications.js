import { initPage, onRealtime, refreshCounts } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, $$, timeAgo, formatDateTime, emptyState, errorState, loadingBlock, toast, withLoading, safeNext } from '../ui.js';

await initPage({ auth: 'required', active: 'notifications' });

const STYLE = {
  OFFER_RECEIVED: ['inbox', 'tint-brand'], OFFER_ACCEPTED: ['checkCircle', 'tint-success'], OFFER_REJECTED: ['xCircle', 'tint-danger'],
  OFFER_CANCELLED: ['x', 'tint-warning'], EXCHANGE_CONFIRMED: ['check', 'tint-brand'], EXCHANGE_COMPLETED: ['sparkles', 'tint-success'],
  EXCHANGE_CANCELLED: ['xCircle', 'tint-danger'], DONATION_REQUESTED: ['hand', 'tint-donation'], DONATION_ACCEPTED: ['gift', 'tint-success'],
  DONATION_DECLINED: ['xCircle', 'tint-danger'], DONATION_CANCELLED: ['x', 'tint-warning'], DONATION_COMPLETED: ['heart', 'tint-success'],
  LISTING_REMOVED: ['trash', 'tint-danger'], REPORT_RESOLVED: ['shield', 'tint-brand'],
};

let unreadOnly = false;
let page = 0;
let items = [];
const list = $('#list');

async function load(reset = true) {
  if (reset) { page = 0; items = []; render(list, loadingBlock()); }
  try {
    const data = await http.get('/api/notifications', { unreadOnly, page, size: 20 });
    items = items.concat(data.content);
    if (!items.length) {
      render(list, html`<div class="card-body">${emptyState({ iconName: 'bell', title: unreadOnly ? "You're all caught up" : 'No notifications yet', text: 'We will let you know when something happens with your listings, offers or requests.' })}</div>`);
    } else {
      render(list, items.map(row));
      bind();
    }
    $('#more').classList.toggle('hidden', data.last);
  } catch (err) {
    render(list, errorState(err.message, () => load(true)));
  }
}

function row(n) {
  const [ic, tint] = STYLE[n.type] || ['bell', 'tint-brand'];
  return html`<a class="notif ${n.read ? '' : 'unread'}" href="${safeNext(n.link, '/notifications.html')}" data-id="${n.id}" data-read="${n.read ? '1' : '0'}">
    <span class="n-icon ${tint}">${icon(ic)}</span>
    <span class="n-text"><strong>${n.title}</strong><p>${n.body}</p></span>
    <span class="row" style="gap:8px"><time datetime="${n.createdAt}" title="${formatDateTime(n.createdAt)}">${timeAgo(n.createdAt)}</time>${n.read ? '' : html`<span class="unread-dot" aria-label="Unread"></span>`}</span>
  </a>`;
}

function bind() {
  $$('.notif', list).forEach((a) => a.addEventListener('click', async (e) => {
    if (a.dataset.read === '1') return;
    e.preventDefault();
    try { await http.post(`/api/notifications/${a.dataset.id}/read`); } catch { /* navigate regardless */ }
    location.href = a.getAttribute('href');
  }));
}

$$('#filter button').forEach((b) => b.addEventListener('click', () => {
  $$('#filter button').forEach((x) => x.classList.toggle('active', x === b));
  unreadOnly = b.dataset.unread === 'true';
  load(true);
}));
$('#more').addEventListener('click', (e) => withLoading(e.currentTarget, async () => { page += 1; await load(false); }));
$('#read-all').addEventListener('click', async (e) => {
  try {
    await withLoading(e.currentTarget, () => http.post('/api/notifications/read-all'));
    toast('All notifications marked as read');
    refreshCounts();
    load(true);
  } catch (err) {
    toast(err.message, 'error');
  }
});

onRealtime('notification', () => load(true));
load(true);
