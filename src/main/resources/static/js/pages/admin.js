// Admin panel: overview (numbers and charts), report queue, member and listing search, and the staff audit log.
// The server enforces the ADMIN role on every /api/admin endpoint; the check here only avoids a confusing page.
import { initPage } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, avatar, timeAgo, formatDate, formatDateTime, emptyState, errorState, loadingBlock,
  pagination, openDialog, confirmDialog, toast, fieldError, itemStatusBadge, typeBadge,
} from '../ui.js';

const { user: me } = await initPage({ auth: 'required' });
if (me.role !== 'ADMIN') {
  render('#main', html`<div class="container">${emptyState({ iconName: 'lock', title: 'Staff only', text: 'This page is for the Swapp team.' })}</div>`);
  await new Promise(() => {});
}

const REASONS = {
  SCAM: 'Scam or fraud', PROHIBITED_ITEM: 'Prohibited item', FAKE_LISTING: 'Fake or misleading', HARASSMENT: 'Harassment',
  SPAM: 'Spam', INAPPROPRIATE: 'Inappropriate', OTHER: 'Other',
};
const OUTCOMES = { DISMISSED: 'Dismissed', ITEM_REMOVED: 'Listing removed', USER_SUSPENDED: 'Member suspended' };
const TARGETS = { USER: 'Member', ITEM: 'Listing', MESSAGE: 'Message' };
const SIGN_IN = { PASSWORD: 'Email and password', GOOGLE: 'Google' };
const TABS = [['overview', 'Overview'], ['reports', 'Reports'], ['members', 'Members'], ['listings', 'Listings'], ['audit', 'Audit log']];

// ---------------------------------------------------------------- state lives in the URL, so Back and reload work

const DEFAULTS = { tab: 'overview', days: '30', status: 'OPEN', q: '', filter: 'ALL', itemStatus: '', owner: '', user: '', page: '0' };

function readState() {
  const p = new URLSearchParams(location.search);
  const s = {};
  for (const [key, fallback] of Object.entries(DEFAULTS)) s[key] = p.get(key) ?? fallback;
  if (!TABS.some(([key]) => key === s.tab)) s.tab = 'overview';
  return s;
}

let state = readState();
let openReports = 0;

function go(changes, { replace = false } = {}) {
  state = { ...state, ...changes };
  const p = new URLSearchParams();
  for (const [key, value] of Object.entries(state)) if (value !== DEFAULTS[key] && value !== '') p.set(key, value);
  const query = p.toString();
  const url = location.pathname + (query ? `?${query}` : '');
  history[replace ? 'replaceState' : 'pushState'](null, '', url);
  show();
}

const openTab = (tab, extra = {}) => go({ ...DEFAULTS, tab, days: state.days, ...extra });
const openMember = (id) => openTab('members', { user: String(id) });

window.addEventListener('popstate', () => { state = readState(); show(); });

// Any element with data-member opens that member's record; data-tab switches tab.
document.addEventListener('click', (e) => {
  const member = e.target.closest('[data-member]');
  if (member) { e.preventDefault(); openMember(member.dataset.member); return; }
  const tab = e.target.closest('[data-goto]');
  if (tab) { e.preventDefault(); openTab(tab.dataset.goto, tab.dataset.filter ? { filter: tab.dataset.filter } : {}); }
});

function renderTabs() {
  render('#tabs', TABS.map(([key, label]) => html`<button class="tab ${key === state.tab ? 'active' : ''}" role="tab"
    aria-selected="${key === state.tab}" data-goto="${key}">${label}${key === 'reports' && openReports ? html`<span class="pill">${openReports}</span>` : ''}</button>`));
}

async function show() {
  renderTabs();
  const view = $('#view');
  render(view, loadingBlock());
  try {
    if (state.tab === 'overview') await showOverview(view);
    else if (state.tab === 'reports') await showReports(view);
    else if (state.tab === 'members') await (state.user ? showMember(view) : showMembers(view));
    else if (state.tab === 'listings') await showListings(view);
    else await showAudit(view);
  } catch (err) {
    render(view, errorState(err.message, show));
  }
}

const memberLink = (m) => (m.deleted
  ? html`<span class="muted">Deleted member</span>`
  : html`<button type="button" class="link-btn" data-member="${m.id}">${m.displayName}</button>`);

// ---------------------------------------------------------------- overview

const METRICS = [
  ['signUps', 'New members'], ['listings', 'New listings'], ['completedSwaps', 'Completed exchanges and giveaways'], ['reports', 'Reports filed'],
];
const shortDay = (iso) => new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });

/** A step that gives a round top value for the y-axis (1, 2, 5, 10, 20, 50, ...). */
function niceMax(max) {
  if (max <= 4) return 4;
  const pow = 10 ** Math.floor(Math.log10(max));
  for (const m of [1, 2, 5, 10]) if (m * pow >= max) return m * pow;
  return 10 * pow;
}

/** One small-multiple bar chart: a single measure per day, on its own scale. The title names the series. */
function barChart(days, key, title) {
  const values = days.map((d) => d[key]);
  const total = values.reduce((a, b) => a + b, 0);
  const top = niceMax(Math.max(...values));
  const W = 360, H = 110, slot = W / days.length, gap = Math.min(2, slot * 0.25), barW = slot - gap;
  const radius = Math.min(4, barW / 2);
  const bars = days.map((d, i) => {
    const h = d[key] === 0 ? 0 : Math.max(2, (d[key] / top) * H);
    const x = i * slot + gap / 2, y = H - h, r = Math.min(radius, h);
    // Rounded at the data end only; square where the bar meets the baseline.
    const path = `M${x},${H} V${y + r} Q${x},${y} ${x + r},${y} H${x + barW - r} Q${x + barW},${y} ${x + barW},${y + r} V${H} Z`;
    return html`<rect class="chart-hit" x="${i * slot}" y="0" width="${slot}" height="${H}" tabindex="0" role="img"
      aria-label="${shortDay(d.date)}: ${d[key]}" data-i="${i}"></rect>${h ? html`<path class="chart-bar" d="${path}"></path>` : html`<path class="chart-bar" d=""></path>`}`;
  });
  return html`<section class="chart-card" data-chart="${key}">
    <div class="chart-head"><span class="chart-title">${title}</span><span class="chart-total">${total}</span></div>
    <div class="chart-sub">last ${days.length} days</div>
    <div class="chart-plot">
      <span class="chart-max">${top}</span>
      <svg viewBox="0 0 ${W} ${H}" role="group" aria-label="${title}, ${total} in the last ${days.length} days">
        <line class="chart-grid-line" x1="0" x2="${W}" y1="0.5" y2="0.5"></line>
        <line class="chart-grid-line" x1="0" x2="${W}" y1="${H / 2}" y2="${H / 2}"></line>
        ${bars}
        <line class="chart-base" x1="0" x2="${W}" y1="${H}" y2="${H}"></line>
      </svg>
    </div>
    <div class="chart-axis"><span>${shortDay(days[0].date)}</span><span>${shortDay(days[days.length - 1].date)}</span></div>
  </section>`;
}

function wireChartTooltips(root, days) {
  $$('[data-chart]', root).forEach((card) => {
    const key = card.dataset.chart;
    const plot = $('.chart-plot', card);
    let tip = null;
    const hide = () => { if (tip) { tip.remove(); tip = null; } };
    const showTip = (hit) => {
      const d = days[Number(hit.dataset.i)];
      if (!tip) { tip = document.createElement('div'); tip.className = 'chart-tip'; plot.append(tip); }
      render(tip, html`<span>${shortDay(d.date)}</span>${d[key]}`);
      const box = plot.getBoundingClientRect(), r = hit.getBoundingClientRect();
      const half = tip.offsetWidth / 2;
      const x = Math.min(Math.max(r.left + r.width / 2 - box.left, half), box.width - half);
      tip.style.left = `${x}px`;
      tip.style.top = '-6px';
    };
    $$('.chart-hit', card).forEach((hit) => {
      hit.addEventListener('pointerenter', () => showTip(hit));
      hit.addEventListener('focus', () => showTip(hit));
      hit.addEventListener('pointerleave', hide);
      hit.addEventListener('blur', hide);
    });
  });
}

async function showOverview(view) {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
  const data = await http.get('/api/admin/overview', { days: state.days, tz });
  const t = data.totals;
  openReports = t.openReports;
  const tile = (iconName, value, label, attrs) => {
    const body = html`<span class="stat-icon">${icon(iconName)}</span><span class="stat-value">${value}</span><span class="stat-label">${label}</span>`;
    return attrs ? html`<a class="stat" href="#" ${attrs}>${body}</a>` : html`<div class="stat">${body}</div>`;
  };
  render(view, html`
    <div class="stat-grid">
      ${tile('users', t.members, 'Members', html`data-goto="members"`)}
      ${tile('package', t.activeListings, 'Available listings', html`data-goto="listings"`)}
      ${tile('swap', t.completedSwaps, 'Completed exchanges and giveaways')}
      ${tile('alert', t.openReports, 'Open reports', html`data-goto="reports"`)}
      ${tile('shield', t.suspendedMembers, 'Suspended members', html`data-goto="members" data-filter="SUSPENDED"`)}
    </div>
    <div class="row-wrap" style="justify-content:space-between;margin:28px 0 14px">
      <h2 style="margin:0;font-size:1.15rem">Activity per day</h2>
      <div class="segmented" role="group" aria-label="Time range">
        ${[['7', '7 days'], ['30', '30 days'], ['90', '90 days']].map(([d, label]) => html`<button type="button" data-days="${d}" class="${d === state.days ? 'active' : ''}" aria-pressed="${d === state.days}">${label}</button>`)}
      </div>
    </div>
    <div class="chart-grid">${METRICS.map(([key, title]) => barChart(data.days, key, title))}</div>
    <details class="table-view"><summary>Show these numbers as a table</summary>
      <div class="table-scroll"><table class="data-table">
        <thead><tr><th scope="col">Day</th>${METRICS.map(([, title]) => html`<th scope="col">${title}</th>`)}</tr></thead>
        <tbody>${[...data.days].reverse().map((d) => html`<tr><td>${shortDay(d.date)}</td>${METRICS.map(([key]) => html`<td>${d[key]}</td>`)}</tr>`)}</tbody>
      </table></div>
    </details>
    <p class="small muted" style="margin-top:14px">Days follow your time zone (${data.timeZone}). Today is still filling up.</p>`);
  $$('[data-days]', view).forEach((b) => b.addEventListener('click', () => go({ days: b.dataset.days }, { replace: true })));
  wireChartTooltips(view, data.days);
  renderTabs(); // the open-report count is known now
}

// ---------------------------------------------------------------- reports

async function showReports(view) {
  const data = await http.get('/api/admin/reports', { status: state.status, page: state.page, size: 20 });
  if (state.status === 'OPEN') openReports = data.totalElements;
  render(view, html`
    <div class="chips" style="margin-bottom:16px">
      ${[['OPEN', 'Waiting for review'], ['RESOLVED', 'Resolved']].map(([key, label]) => html`<button type="button" class="chip ${key === state.status ? 'active' : ''}" data-status="${key}">${label}</button>`)}
    </div>
    <div class="stack" id="list">${data.content.length ? data.content.map(reportCard)
      : emptyState({ iconName: 'checkCircle', title: state.status === 'OPEN' ? 'All caught up' : 'Nothing resolved yet', text: state.status === 'OPEN' ? 'There are no reports waiting for review.' : 'Reports you have decided on appear here.' })}</div>
    <div id="pager" style="margin-top:18px"></div>`);
  $$('[data-status]', view).forEach((b) => b.addEventListener('click', () => go({ status: b.dataset.status, page: '0' })));
  pagination($('#pager'), data, (p) => go({ page: String(p) }));
  $$('[data-action]', view).forEach((b) => b.addEventListener('click', () => {
    decide(data.content.find((r) => r.id === Number(b.dataset.id)), b.dataset.action);
  }));
}

function reportCard(r) {
  const u = r.reportedUser;
  return html`<article class="card"><div class="card-body stack">
    <div class="row-wrap" style="justify-content:space-between">
      <div class="row-wrap"><span class="badge badge-danger">${REASONS[r.reason]}</span><span class="badge">${TARGETS[r.targetType]}</span>
        ${r.resolution ? html`<span class="badge badge-success">${OUTCOMES[r.resolution]}</span>` : ''}</div>
      <span class="small muted">Report #${r.id} · ${timeAgo(r.createdAt)}</span>
    </div>
    <div class="row-wrap small">
      <span class="muted">Reported:</span>
      <span class="row" style="gap:8px">${avatar(u, 'sm')}${memberLink(u)}</span>
      ${u.suspended ? html`<span class="badge badge-warning">Suspended</span>` : ''}
      ${u.openReports > 1 ? html`<span class="badge badge-warning">${u.openReports} open reports</span>` : ''}
      <span class="muted">· by ${r.reporter.displayName}</span>
    </div>
    ${r.itemId ? html`<p class="small" style="margin:0">Listing: <a href="/item.html?id=${r.itemId}">${r.itemTitle}</a> <span class="muted">(${r.itemStatus.toLowerCase()})</span></p>` : ''}
    ${r.details ? html`<blockquote style="margin:0;padding-left:12px;border-left:3px solid var(--border)"><span class="small muted">Reporter's note</span><br>${r.details}</blockquote>` : ''}
    <div><span class="small muted">What the reporter saw</span>
      <div style="white-space:pre-wrap;background:var(--surface-2, var(--bg));border-radius:var(--radius);padding:12px;margin-top:4px;max-height:220px;overflow:auto">${r.evidence}</div></div>
    ${r.status === 'OPEN' ? html`<div class="row-wrap" style="justify-content:flex-end">
      <button class="btn btn-secondary btn-sm" data-action="DISMISS" data-id="${r.id}">Dismiss</button>
      ${r.targetType === 'ITEM' && r.itemStatus !== 'REMOVED' ? html`<button class="btn btn-danger-ghost btn-sm" data-action="REMOVE_ITEM" data-id="${r.id}">${icon('trash', 'icon-sm')}Remove listing</button>` : ''}
      ${!u.suspended && !u.deleted ? html`<button class="btn btn-danger btn-sm" data-action="SUSPEND_USER" data-id="${r.id}">${icon('shield', 'icon-sm')}Suspend member</button>` : ''}
    </div>` : html`<p class="small muted" style="margin:0">${OUTCOMES[r.resolution]} by ${r.resolvedBy} · ${formatDateTime(r.resolvedAt)}${r.resolutionNote ? html` · “${r.resolutionNote}”` : ''}</p>`}
  </div></article>`;
}

async function decide(report, action) {
  const copy = {
    DISMISS: { title: 'Dismiss report', submit: 'Dismiss', label: 'Internal note (optional)', required: false, danger: false,
      text: 'No action is taken. The reporter is told the report was reviewed.' },
    REMOVE_ITEM: { title: 'Remove listing', submit: 'Remove listing', label: 'Note to the owner (optional)', required: false, danger: true,
      text: `"${report.itemTitle}" is taken down and its open offers and requests are closed. The owner is notified by email.` },
    SUSPEND_USER: { title: `Suspend ${report.reportedUser.displayName}`, submit: 'Suspend member', label: 'Reason (shown to the member)', required: true, danger: true,
      text: 'They are signed out everywhere, their listings are hidden, and exchanges or hand-overs in progress are cancelled. Other open reports about them are resolved too.' },
  }[action];
  const done = await noteDialog(copy, (note) => http.post(`/api/admin/reports/${report.id}/resolve`, { action, note: note || null }));
  if (done) { toast('Report resolved.'); show(); }
}

/** A confirmation dialog with one text box. Resolves true once the request has succeeded. */
function noteDialog({ title, submit, label, required, danger, text }, send) {
  return openDialog({
    title,
    submitText: submit,
    danger,
    body: html`<p>${text}</p>
      <div class="field"><label for="mod-note">${label}</label>
        <textarea class="textarea" id="mod-note" name="note" maxlength="500" rows="3"></textarea></div>`,
    onSubmit: async (dialog) => {
      const form = dialog.querySelector('form');
      const note = form.elements.note.value.trim();
      if (required && !note) { fieldError(form, 'note', 'A reason is required'); return false; }
      await send(note);
      return true;
    },
  });
}

// ---------------------------------------------------------------- members

const roleBadge = (u) => (u.role === 'ADMIN' ? html`<span class="badge badge-info">Admin</span>` : '');

async function showMembers(view) {
  const params = { filter: state.filter, page: state.page, size: 20 };
  if (state.q) params.q = state.q;
  const data = await http.get('/api/admin/users', params);
  render(view, html`
    <form class="admin-toolbar" id="search" role="search">
      <input class="input" type="search" name="q" value="${state.q}" placeholder="Search by name or email" aria-label="Search members by name or email" maxlength="100">
      <select class="select" name="filter" aria-label="Show">
        ${[['ALL', 'All members'], ['SUSPENDED', 'Suspended'], ['UNVERIFIED', 'Email not confirmed'], ['STAFF', 'Staff']].map(([key, label]) => html`<option value="${key}" ${key === state.filter ? html`selected` : ''}>${label}</option>`)}
      </select>
      <button class="btn btn-primary" type="submit">${icon('search')}Search</button>
    </form>
    <p class="small muted" style="margin:0 0 10px">${data.totalElements} ${data.totalElements === 1 ? 'member' : 'members'}</p>
    ${data.content.length ? html`<div class="admin-list">${data.content.map((u) => html`
      <a class="admin-row" href="/admin.html?tab=members&user=${u.id}" data-member="${u.id}">
        ${avatar(u)}
        <span class="grow"><strong>${u.displayName}</strong><span class="small muted">${u.email}</span></span>
        <span class="badges">${roleBadge(u)}${u.suspended ? html`<span class="badge badge-warning">Suspended</span>` : ''}${u.emailVerified ? '' : html`<span class="badge badge-neutral hide-xs">Email not confirmed</span>`}</span>
        <span class="small muted nowrap hide-sm">Joined ${formatDate(u.createdAt)}</span>
        ${icon('chevronRight')}
      </a>`)}</div>`
      : emptyState({ iconName: 'users', title: 'No members found', text: 'Try a different name or email, or change the filter.' })}
    <div id="pager" style="margin-top:18px"></div>`);
  const form = $('#search');
  const submit = () => go({ q: form.elements.q.value.trim(), filter: form.elements.filter.value, page: '0' });
  form.addEventListener('submit', (e) => { e.preventDefault(); submit(); });
  form.elements.filter.addEventListener('change', submit);
  pagination($('#pager'), data, (p) => go({ page: String(p) }));
}

async function showMember(view) {
  let u;
  try {
    u = await http.get(`/api/admin/users/${encodeURIComponent(state.user)}`);
  } catch (err) {
    if (err.status !== 404 && err.status !== 400) throw err;
    render(view, emptyState({ iconName: 'user', title: 'Member not found', text: 'This member does not exist.' }));
    return;
  }
  const a = u.activity;
  const isMe = u.id === me.id;
  const stat = (value, label) => html`<div class="stat"><span class="stat-value">${value}</span><span class="stat-label">${label}</span></div>`;
  render(view, html`
    <p style="margin:0 0 14px"><button type="button" class="link-btn" data-goto="members">${icon('arrowLeft', 'icon-sm')} All members</button></p>
    <div class="card"><div class="card-body stack">
      <div class="admin-detail-head">
        ${avatar(u, 'lg')}
        <div class="grow">
          <h2>${u.deleted ? 'Deleted member' : u.displayName}</h2>
          <div class="row-wrap">${roleBadge(u)}
            ${u.deleted ? html`<span class="badge badge-neutral">Account deleted</span>` : ''}
            ${u.suspendedAt ? html`<span class="badge badge-warning">Suspended</span>` : ''}
            ${!u.deleted && !u.suspendedAt ? html`<span class="badge badge-success">Active</span>` : ''}</div>
        </div>
        <div class="row-wrap">
          ${!u.deleted && !u.suspendedAt ? html`<a class="btn btn-secondary btn-sm" href="/profile.html?id=${u.id}">${icon('eye', 'icon-sm')}Public profile</a>` : ''}
          ${u.suspendedAt && !u.deleted ? html`<button class="btn btn-secondary btn-sm" id="reinstate">Reinstate</button>` : ''}
          ${!u.suspendedAt && !u.deleted && u.role !== 'ADMIN' ? html`<button class="btn btn-danger btn-sm" id="suspend">${icon('shield', 'icon-sm')}Suspend</button>` : ''}
        </div>
      </div>
      ${u.suspendedAt ? html`<div class="banner warning" role="status"><div><strong>Suspended ${formatDateTime(u.suspendedAt)}</strong><br>${u.suspensionReason}</div></div>` : ''}
      ${u.role === 'ADMIN' && !u.deleted ? html`<p class="small muted" style="margin:0">${isMe ? 'This is your own account.' : 'Admins cannot be suspended from here.'}</p>` : ''}
      ${u.deleted ? html`<p class="small muted" style="margin:0">This member deleted their account. Their personal details were erased; only the history other members rely on is kept.</p>` : html`
      <dl class="meta-list">
        <dt>${icon('mail', 'icon-sm')}Email</dt><dd style="overflow-wrap:anywhere">${u.email} ${u.emailVerified ? html`<span class="badge badge-success">Confirmed</span>` : html`<span class="badge badge-neutral">Not confirmed</span>`}</dd>
        <dt>${icon('lock', 'icon-sm')}Signs in with</dt><dd>${u.signInMethods.map((m) => SIGN_IN[m] || m).join(', ') || 'Nothing set'}</dd>
        <dt>${icon('calendar', 'icon-sm')}Joined</dt><dd>${formatDate(u.createdAt)}</dd>
        ${u.location ? html`<dt>${icon('pin', 'icon-sm')}Location</dt><dd>${u.location}</dd>` : ''}
        ${u.bio ? html`<dt>${icon('info', 'icon-sm')}About</dt><dd style="font-weight:500;overflow-wrap:anywhere">${u.bio}</dd>` : ''}
      </dl>`}
    </div></div>

    <div class="stat-grid" style="margin-top:14px">
      ${stat(a.liveListings, 'Listings')}${stat(a.completedExchanges, 'Completed exchanges')}
      ${stat(a.openReportsAbout, 'Open reports about them')}${stat(a.totalReportsAbout, 'Reports about them, all time')}
      ${stat(a.reportsFiled, 'Reports they filed')}
    </div>

    <h3 style="margin:28px 0 10px">Latest listings</h3>
    ${u.recentListings.length ? html`<div class="admin-list">${u.recentListings.map((i) => listingRow(i, false))}</div>
      ${a.liveListings > u.recentListings.length ? html`<p class="small" style="margin:10px 0 0"><a href="/admin.html?tab=listings&owner=${u.id}" id="all-listings">See all their listings</a></p>` : ''}`
      : html`<p class="muted">No listings.</p>`}

    <h3 style="margin:28px 0 10px">Staff actions on this member</h3>
    ${u.recentActions.length ? html`<div class="admin-list">${u.recentActions.map((e) => auditRow(e, false))}</div>` : html`<p class="muted">None.</p>`}`);

  $('#suspend')?.addEventListener('click', async () => {
    const done = await noteDialog({ title: `Suspend ${u.displayName}`, submit: 'Suspend member', label: 'Reason (shown to the member)', required: true, danger: true,
      text: 'They are signed out everywhere, their listings are hidden, and exchanges or hand-overs in progress are cancelled. They are told the reason by email.' },
    (note) => http.post(`/api/admin/users/${u.id}/suspend`, { reason: note }));
    if (done) { toast(`${u.displayName} has been suspended.`); show(); }
  });
  $('#reinstate')?.addEventListener('click', async () => {
    if (!(await confirmDialog({ title: `Reinstate ${u.displayName}?`, message: 'They can sign in again and are told by email. Their listings stay hidden until they mark them available.', confirmText: 'Reinstate' }))) return;
    try {
      await http.post(`/api/admin/users/${u.id}/reinstate`);
      toast(`${u.displayName} has been reinstated.`);
      show();
    } catch (err) {
      toast(err.message, 'error');
    }
  });
  wireListingRemoval(view, u.recentListings);
}

// ---------------------------------------------------------------- listings

const ITEM_STATUSES = [['', 'Any status'], ['AVAILABLE', 'Available'], ['RESERVED', 'Reserved'], ['UNAVAILABLE', 'Hidden'], ['COMPLETED', 'Completed'], ['REMOVED', 'Removed']];

function listingRow(i, withOwner = true) {
  const live = i.status !== 'REMOVED';
  const title = live ? html`<a href="/item.html?id=${i.id}"><strong>${i.title}</strong></a>` : html`<strong>${i.title}</strong>`;
  return html`<div class="admin-row">
    ${i.coverUrl ? html`<img class="admin-thumb" src="${i.coverUrl}" alt="" loading="lazy">` : html`<span class="admin-thumb" aria-hidden="true">${icon('package')}</span>`}
    <span class="grow">${title}<span class="small muted">${i.category} · listed ${formatDate(i.createdAt)}${withOwner ? html` · by ${memberLink(i.owner)}` : ''}</span></span>
    <span class="badges"><span class="hide-xs">${typeBadge(i.listingType)}</span>${i.status === 'REMOVED' ? html`<span class="badge badge-danger">Removed</span>` : itemStatusBadge(i.status)}</span>
    ${live ? html`<button class="btn btn-danger-ghost btn-sm" data-remove="${i.id}" aria-label="Remove listing ${i.title}">${icon('trash', 'icon-sm')}<span class="hide-sm">Remove</span></button>` : ''}
  </div>`;
}

function wireListingRemoval(root, listings) {
  $$('[data-remove]', root).forEach((b) => b.addEventListener('click', async () => {
    const item = listings.find((i) => i.id === Number(b.dataset.remove));
    const done = await noteDialog({ title: 'Remove listing', submit: 'Remove listing', label: 'Reason (shown to the owner)', required: true, danger: true,
      text: `"${item.title}" is taken down and its open offers and requests are closed. The owner is told the reason by email.` },
    (note) => http.post(`/api/admin/items/${item.id}/remove`, { reason: note }));
    if (done) { toast('Listing removed.'); show(); }
  }));
}

async function showListings(view) {
  const params = { page: state.page, size: 20 };
  if (state.q) params.q = state.q;
  if (state.itemStatus) params.status = state.itemStatus;
  if (state.owner) params.ownerId = state.owner;
  const data = await http.get('/api/admin/items', params);
  render(view, html`
    <form class="admin-toolbar" id="search" role="search">
      <input class="input" type="search" name="q" value="${state.q}" placeholder="Search by title" aria-label="Search listings by title" maxlength="100">
      <select class="select" name="status" aria-label="Status">
        ${ITEM_STATUSES.map(([key, label]) => html`<option value="${key}" ${key === state.itemStatus ? html`selected` : ''}>${label}</option>`)}
      </select>
      <button class="btn btn-primary" type="submit">${icon('search')}Search</button>
    </form>
    <p class="small muted" style="margin:0 0 10px">${data.totalElements} ${data.totalElements === 1 ? 'listing' : 'listings'}${state.owner ? html` from one member · <button type="button" class="link-btn" data-goto="listings">show everyone's</button>` : ''}</p>
    ${data.content.length ? html`<div class="admin-list">${data.content.map((i) => listingRow(i))}</div>`
      : emptyState({ iconName: 'package', title: 'No listings found', text: 'Try a different title or status.' })}
    <div id="pager" style="margin-top:18px"></div>`);
  const form = $('#search');
  const submit = () => go({ q: form.elements.q.value.trim(), itemStatus: form.elements.status.value, page: '0' });
  form.addEventListener('submit', (e) => { e.preventDefault(); submit(); });
  form.elements.status.addEventListener('change', submit);
  pagination($('#pager'), data, (p) => go({ page: String(p) }));
  wireListingRemoval(view, data.content);
}

// ---------------------------------------------------------------- audit log

function auditRow(e, withTarget = true) {
  const target = withTarget ? memberLink(e.targetUser) : 'this member';
  const listing = e.itemTitle ? html`“${e.itemTitle}”` : 'a listing';
  const what = {
    REPORT_DISMISSED: html`dismissed report #${e.reportId} about ${target}`,
    LISTING_REMOVED: html`removed the listing ${listing}${withTarget ? html` by ${target}` : ''}${e.reportId ? html` (report #${e.reportId})` : ''}`,
    MEMBER_SUSPENDED: html`suspended ${target}${e.reportId ? html` (report #${e.reportId})` : ''}`,
    MEMBER_REINSTATED: html`reinstated ${target}`,
  }[e.action] || e.action;
  return html`<div class="admin-row" style="align-items:flex-start">
    <span class="grow" style="white-space:normal"><span style="white-space:normal"><strong>${e.actor.displayName}</strong> ${what}</span>
      ${e.note ? html`<span class="admin-note small" style="white-space:normal">${e.note}</span>` : ''}</span>
    <time class="small muted nowrap" datetime="${e.createdAt}" title="${formatDateTime(e.createdAt)}">${timeAgo(e.createdAt)}</time>
  </div>`;
}

async function showAudit(view) {
  const data = await http.get('/api/admin/audit', { page: state.page, size: 30 });
  render(view, html`
    <p class="small muted" style="margin:0 0 12px">Every action taken by staff, newest first. Entries cannot be edited or deleted.</p>
    ${data.content.length ? html`<div class="admin-list">${data.content.map((e) => auditRow(e))}</div>`
      : emptyState({ iconName: 'list', title: 'No staff actions yet', text: 'Suspensions, reinstatements, listing removals and dismissed reports are recorded here.' })}
    <div id="pager" style="margin-top:18px"></div>`);
  pagination($('#pager'), data, (p) => go({ page: String(p) }));
}

// ---------------------------------------------------------------- start

try { openReports = (await http.get('/api/admin/summary')).openReports; } catch { /* the count is optional */ }
show();
