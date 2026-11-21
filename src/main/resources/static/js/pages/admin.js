// Moderation queue: open reports, resolved reports (audit log) and suspended members.
// The server enforces the ADMIN role on every /api/admin endpoint; the check here only avoids a confusing page.
import { initPage } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, avatar, timeAgo, formatDateTime, emptyState, errorState, loadingBlock, pagination,
  openDialog, confirmDialog, toast, fieldError,
} from '../ui.js';

const { user } = await initPage({ auth: 'required' });
if (user.role !== 'ADMIN') {
  render('#main', html`<div class="container">${emptyState({ iconName: 'lock', title: 'Moderators only', text: 'This page is for the Swapp moderation team.' })}</div>`);
  await new Promise(() => {});
}

const REASONS = {
  SCAM: 'Scam or fraud', PROHIBITED_ITEM: 'Prohibited item', FAKE_LISTING: 'Fake or misleading', HARASSMENT: 'Harassment',
  SPAM: 'Spam', INAPPROPRIATE: 'Inappropriate', OTHER: 'Other',
};
const OUTCOMES = { DISMISSED: 'Dismissed', ITEM_REMOVED: 'Listing removed', USER_SUSPENDED: 'Member suspended' };
const TARGETS = { USER: 'Member', ITEM: 'Listing', MESSAGE: 'Message' };

let tab = 'OPEN';
let page = 0;
let summary = { openReports: 0, suspendedMembers: 0 };

async function renderTabs() {
  try { summary = await http.get('/api/admin/summary'); } catch { /* counts are optional */ }
  const tabs = [['OPEN', `Open reports (${summary.openReports})`], ['RESOLVED', 'Resolved'], ['SUSPENDED', `Suspended members (${summary.suspendedMembers})`]];
  render('#tabs', tabs.map(([key, label]) => html`<button class="tab ${key === tab ? 'active' : ''}" role="tab" aria-selected="${key === tab}" data-tab="${key}">${label}</button>`));
  $$('#tabs [data-tab]').forEach((b) => b.addEventListener('click', () => { tab = b.dataset.tab; page = 0; renderTabs(); load(); }));
}

async function load() {
  const box = $('#list');
  render(box, loadingBlock());
  try {
    if (tab === 'SUSPENDED') {
      const data = await http.get('/api/admin/users/suspended', { page, size: 20 });
      render(box, data.content.length ? data.content.map(suspendedCard)
        : emptyState({ iconName: 'shield', title: 'No suspended members', text: 'Suspended members appear here so they can be reinstated.' }));
      pagination($('#pager'), data, (p) => { page = p; load(); });
      $$('[data-reinstate]', box).forEach((b) => b.addEventListener('click', () => reinstate(Number(b.dataset.reinstate), b.dataset.name)));
      return;
    }
    const data = await http.get('/api/admin/reports', { status: tab, page, size: 20 });
    render(box, data.content.length ? data.content.map(reportCard)
      : emptyState({ iconName: 'checkCircle', title: tab === 'OPEN' ? 'All caught up' : 'Nothing resolved yet', text: tab === 'OPEN' ? 'There are no reports waiting for review.' : 'Resolved reports appear here as an audit log.' }));
    pagination($('#pager'), data, (p) => { page = p; load(); });
    $$('[data-action]', box).forEach((b) => b.addEventListener('click', () => {
      const report = data.content.find((r) => r.id === Number(b.dataset.id));
      decide(report, b.dataset.action);
    }));
  } catch (err) {
    render(box, errorState(err.message, load));
  }
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
      <span class="row" style="gap:8px">${avatar(u, 'sm')}<strong>${u.displayName}</strong></span>
      ${u.suspended ? html`<span class="badge badge-warning">Suspended</span>` : ''}${u.deleted ? html`<span class="badge">Deleted account</span>` : ''}
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

function suspendedCard(u) {
  return html`<article class="card"><div class="card-body row-wrap" style="justify-content:space-between">
    <div class="row" style="gap:12px">${avatar(u)}<div><strong>${u.displayName}</strong>
      <div class="small muted">Suspended ${timeAgo(u.suspendedAt)} · ${u.reason}</div></div></div>
    <button class="btn btn-secondary btn-sm" data-reinstate="${u.id}" data-name="${u.displayName}">Reinstate</button>
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
  const done = await openDialog({
    title: copy.title,
    submitText: copy.submit,
    danger: copy.danger,
    body: html`<p>${copy.text}</p>
      <div class="field"><label for="mod-note">${copy.label}</label>
        <textarea class="textarea" id="mod-note" name="note" maxlength="500" rows="3"></textarea></div>`,
    onSubmit: async (dialog) => {
      const form = dialog.querySelector('form');
      const note = form.elements.note.value.trim();
      if (copy.required && !note) { fieldError(form, 'note', 'A reason is required'); return false; }
      await http.post(`/api/admin/reports/${report.id}/resolve`, { action, note: note || null });
      return true;
    },
  });
  if (done) { toast('Report resolved.'); renderTabs(); load(); }
}

async function reinstate(id, name) {
  if (!(await confirmDialog({ title: `Reinstate ${name}?`, message: 'They can sign in again and are told by email. Their listings stay hidden until they mark them available.', confirmText: 'Reinstate' }))) return;
  try {
    await http.post(`/api/admin/users/${id}/reinstate`);
    toast(`${name} has been reinstated.`);
    renderTabs();
    load();
  } catch (err) {
    toast(err.message, 'error');
  }
}

renderTabs();
load();
