// UI helpers: safe HTML templating, formatting, shared components, toasts and dialogs.
import { iconSvg } from './icons.js';

// ---------------------------------------------------------------- safe templating

class SafeHtml {
  constructor(value) { this.value = value; }
  toString() { return this.value; }
}

const ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;', '`': '&#96;' };
export function esc(value) {
  return String(value ?? '').replace(/[&<>"'`]/g, (c) => ESCAPES[c]);
}

function interpolate(value) {
  if (value instanceof SafeHtml) return value.value;
  if (Array.isArray(value)) return value.map(interpolate).join('');
  if (value === false || value === null || value === undefined) return '';
  return esc(value);
}

/** Tagged template: every interpolated value is HTML-escaped unless it is itself produced by html``/raw(). */
export function html(strings, ...values) {
  let out = strings[0];
  values.forEach((v, i) => { out += interpolate(v) + strings[i + 1]; });
  return new SafeHtml(out);
}

export const raw = (s) => new SafeHtml(s);
export const icon = (name, cls) => raw(iconSvg(name, cls));

export function render(el, content) {
  if (typeof el === 'string') el = document.querySelector(el);
  if (el) el.innerHTML = interpolate(content);
  return el;
}

export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));

// ---------------------------------------------------------------- formatting

const rtf = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });
export function timeAgo(iso) {
  if (!iso) return '';
  const seconds = Math.round((new Date(iso).getTime() - Date.now()) / 1000);
  const abs = Math.abs(seconds);
  if (abs < 45) return 'just now';
  if (abs < 3600) return rtf.format(Math.round(seconds / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(seconds / 3600), 'hour');
  if (abs < 604800) return rtf.format(Math.round(seconds / 86400), 'day');
  return formatDate(iso);
}
export function formatDate(iso) {
  return iso ? new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '';
}
export function formatDateTime(iso) {
  return iso ? new Date(iso).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' }) : '';
}
export function formatTime(iso) {
  return iso ? new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' }) : '';
}

export const CONDITIONS = { NEW: 'New', LIKE_NEW: 'Like new', GOOD: 'Good', FAIR: 'Fair', FOR_PARTS: 'For parts' };
export const LISTING_TYPES = {
  EXCHANGE: { label: 'Exchange', icon: 'swap', cls: 'exchange', desc: 'Trade it for something you need' },
  DONATION: { label: 'Donation', icon: 'heart', cls: 'donation', desc: 'Donate it to someone who needs it' },
  GIVEAWAY: { label: 'Free', icon: 'gift', cls: 'giveaway', desc: 'Give it away for free' },
  REQUEST: { label: 'Request', icon: 'hand', cls: 'request', desc: 'Ask the community for an item' },
};
const ITEM_STATUS = {
  AVAILABLE: ['Available', 'success'], RESERVED: ['Reserved', 'warning'], UNAVAILABLE: ['Hidden', 'neutral'],
  COMPLETED: ['Completed', 'info'], REMOVED: ['Removed', 'neutral'],
};
const FLOW_STATUS = {
  PENDING: ['Pending', 'warning'], ACCEPTED: ['Accepted', 'success'], REJECTED: ['Declined', 'danger'],
  DECLINED: ['Declined', 'danger'], CANCELLED: ['Cancelled', 'neutral'], COMPLETED: ['Completed', 'info'],
  ACTIVE: ['Active', 'success'],
};

export function typeBadge(type) {
  const t = LISTING_TYPES[type] || LISTING_TYPES.EXCHANGE;
  return html`<span class="badge badge-${t.cls}">${icon(t.icon)}${t.label}</span>`;
}
export function itemStatusBadge(status) {
  const [label, cls] = ITEM_STATUS[status] || [status, 'neutral'];
  return html`<span class="badge badge-${cls}">${label}</span>`;
}
export function statusBadge(status) {
  const [label, cls] = FLOW_STATUS[status] || [status, 'neutral'];
  return html`<span class="badge badge-${cls}">${label}</span>`;
}

// ---------------------------------------------------------------- components

export function initials(name) {
  return (name || '?').split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0].toUpperCase()).join('');
}

export function avatar(user, size = '') {
  const cls = `avatar ${size ? 'avatar-' + size : ''}`;
  if (user && user.avatarUrl) return html`<img class="${cls}" src="${user.avatarUrl}" alt="" loading="lazy">`;
  return html`<span class="${cls}" aria-hidden="true">${initials(user && user.displayName)}</span>`;
}

export function userChip(user, sub) {
  return html`<a class="user-chip" href="/profile.html?id=${user.id}">${avatar(user)}<div class="ellipsis">
    <strong class="ellipsis">${user.displayName}</strong>${sub ? html`<span>${sub}</span>` : ''}</div></a>`;
}

export function itemMedia(item, cls = '') {
  if (item.coverImageUrl) return html`<img src="${item.coverImageUrl}" alt="${item.title}" loading="lazy" class="${cls}">`;
  const t = LISTING_TYPES[item.listingType] || LISTING_TYPES.EXCHANGE;
  return html`<div class="placeholder-media ph-${t.cls}">${icon((item.category && item.category.icon) || 'box')}</div>`;
}

export function itemCard(item, { actions, showStatus } = {}) {
  return html`<article class="item-card-wrap">
    <a class="item-card" href="/item.html?id=${item.id}">
      <div class="item-media">${itemMedia(item)}${typeBadge(item.listingType)}
        ${showStatus || item.status !== 'AVAILABLE' ? html`<span class="status-flag">${itemStatusBadge(item.status)}</span>` : ''}
      </div>
      <div class="item-body">
        <div class="item-title">${item.title}</div>
        <div class="item-meta">${icon('pin')}<span class="ellipsis">${item.location}</span></div>
        <div class="item-foot"><span>${CONDITIONS[item.condition] || ''}</span><span>${timeAgo(item.createdAt)}</span></div>
      </div>
    </a>
    ${actions || ''}
  </article>`;
}

export function miniItem(item, kicker) {
  if (!item) return '';
  return html`<a class="mini-item" href="/item.html?id=${item.id}">
    <div class="thumb">${itemMedia(item)}</div>
    <div class="info">${kicker ? html`<div class="kicker">${kicker}</div>` : ''}<strong>${item.title}</strong>
      <span class="small muted">${CONDITIONS[item.condition] || ''}${item.location ? ' · ' + item.location : ''}</span></div>
  </a>`;
}

export function emptyState({ iconName = 'package', title, text, action }) {
  return html`<div class="empty-state"><div class="empty-icon">${icon(iconName)}</div>
    <h3>${title}</h3>${text ? html`<p>${text}</p>` : ''}${action || ''}</div>`;
}

export function errorState(message, retry) {
  const id = 'retry-' + Math.random().toString(36).slice(2);
  setTimeout(() => { const b = document.getElementById(id); if (b && retry) b.onclick = retry; });
  return html`<div class="empty-state"><div class="empty-icon tint-danger">${icon('alert')}</div>
    <h3>Something went wrong</h3><p>${message}</p>
    ${retry ? html`<button class="btn btn-secondary" id="${id}">${icon('refresh')}Try again</button>` : ''}</div>`;
}

export function skeletonCards(n = 8) {
  return raw(Array.from({ length: n }, () => '<div class="skeleton-card"><div class="skeleton skeleton-media"></div><div class="skeleton skeleton-line"></div><div class="skeleton skeleton-line short"></div></div>').join(''));
}

export const loadingBlock = () => raw('<div class="loading-block"><span class="spinner" role="status" aria-label="Loading"></span></div>');

export function pagination(container, page, onChange) {
  if (!page || page.totalPages <= 1) { render(container, ''); return; }
  render(container, html`<nav class="pagination" aria-label="Pagination">
    <button class="btn btn-secondary btn-sm" data-p="${page.page - 1}" ${page.page === 0 ? raw('disabled') : ''}>${icon('chevronLeft')}Previous</button>
    <span class="page-info">Page ${page.page + 1} of ${page.totalPages}</span>
    <button class="btn btn-secondary btn-sm" data-p="${page.page + 1}" ${page.last ? raw('disabled') : ''}>Next${icon('chevronRight')}</button>
  </nav>`);
  $$('button[data-p]', container).forEach((b) => b.addEventListener('click', () => {
    onChange(Number(b.dataset.p));
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }));
}

// ---------------------------------------------------------------- buttons & forms

export async function withLoading(button, task) {
  if (!button) return task();
  const original = button.innerHTML;
  button.classList.add('is-loading');
  button.disabled = true;
  button.innerHTML = '<span class="spinner"></span>' + (button.dataset.loadingText || button.textContent.trim());
  try {
    return await task();
  } finally {
    button.classList.remove('is-loading');
    button.disabled = false;
    button.innerHTML = original;
  }
}

export function clearErrors(form) {
  $$('.field.has-error', form).forEach((f) => f.classList.remove('has-error'));
  $$('.error-text', form).forEach((e) => e.remove());
  const alert = $('.form-alert', form);
  if (alert) alert.remove();
}

export function showErrors(form, err) {
  clearErrors(form);
  const fields = (err && err.fieldErrors) || {};
  let shown = false;
  for (const [name, message] of Object.entries(fields)) {
    const input = form.querySelector(`[name="${CSS.escape(name)}"]`);
    const field = input && input.closest('.field');
    if (field) {
      field.classList.add('has-error');
      field.insertAdjacentHTML('beforeend', `<span class="error-text">${esc(message)}</span>`);
      shown = true;
    }
  }
  if (!shown || !Object.keys(fields).length) {
    form.insertAdjacentHTML('afterbegin', `<div class="form-alert error" role="alert">${iconSvg('alert')}<span>${esc(err.message)}</span></div>`);
  }
  const first = $('.has-error input, .has-error textarea, .has-error select', form);
  if (first) first.focus();
}

export function fieldError(form, name, message) {
  const input = form.querySelector(`[name="${name}"]`);
  const field = input && input.closest('.field');
  if (!field) return;
  field.classList.add('has-error');
  field.insertAdjacentHTML('beforeend', `<span class="error-text">${esc(message)}</span>`);
}

export function param(name) {
  return new URLSearchParams(location.search).get(name);
}

/** Only allow same-origin relative redirects (prevents open redirects via ?next=). */
export function safeNext(value, fallback = '/home.html') {
  if (value && value.startsWith('/') && !value.startsWith('//') && !value.includes('\\')) return value;
  return fallback;
}

// ---------------------------------------------------------------- toasts

function toastStack() {
  let stack = $('.toast-stack');
  if (!stack) {
    stack = document.createElement('div');
    stack.className = 'toast-stack';
    stack.setAttribute('aria-live', 'polite');
    document.body.appendChild(stack);
  }
  return stack;
}

export function toast(message, type = 'success', { title, link, duration = 4500 } = {}) {
  const el = document.createElement('div');
  el.className = `toast ${type}`;
  el.setAttribute('role', type === 'error' ? 'alert' : 'status');
  const iconName = type === 'error' ? 'alert' : type === 'info' ? 'bell' : 'checkCircle';
  el.innerHTML = interpolate(html`<span class="toast-icon">${icon(iconName)}</span>
    <div>${title ? html`<strong>${title}</strong>` : ''}<span>${message}</span>
    ${link ? html` <a href="${link}">View</a>` : ''}</div>
    <button class="toast-close" aria-label="Dismiss">${icon('x', 'icon-sm')}</button>`);
  const remove = () => { el.classList.add('leaving'); setTimeout(() => el.remove(), 200); };
  el.querySelector('.toast-close').addEventListener('click', remove);
  toastStack().appendChild(el);
  setTimeout(remove, duration);
}

// ---------------------------------------------------------------- dialogs

/**
 * Opens a modal dialog. `body` is SafeHtml; `onSubmit(dialog)` may return false to keep it open
 * or throw to show an error. Resolves with the onSubmit result, or null when dismissed.
 */
export function openDialog({ title, body, submitText = 'Confirm', cancelText = 'Cancel', danger = false, wide = false, onSubmit, hideSubmit = false }) {
  return new Promise((resolve) => {
    const dialog = document.createElement('dialog');
    dialog.className = `modal${wide ? ' wide' : ''}`;
    dialog.innerHTML = interpolate(html`<form method="dialog" novalidate>
      <div class="modal-head"><h2>${title}</h2>
        <button type="button" class="btn btn-ghost btn-icon btn-sm" data-close aria-label="Close">${icon('x')}</button></div>
      <div class="modal-body">${body}</div>
      <div class="modal-foot">
        <button type="button" class="btn btn-secondary" data-close>${cancelText}</button>
        ${hideSubmit ? '' : html`<button type="submit" class="btn ${danger ? 'btn-danger' : 'btn-primary'}" data-submit>${submitText}</button>`}
      </div></form>`);
    document.body.appendChild(dialog);
    let result = null;
    const close = () => { dialog.close(); };
    dialog.addEventListener('close', () => { dialog.remove(); resolve(result); });
    $$('[data-close]', dialog).forEach((b) => b.addEventListener('click', close));
    dialog.addEventListener('click', (e) => { if (e.target === dialog) close(); });
    const form = $('form', dialog);
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearErrors(form);
      const btn = $('[data-submit]', dialog);
      try {
        const value = await withLoading(btn, () => (onSubmit ? onSubmit(dialog) : true));
        if (value === false) return;
        result = value === undefined ? true : value;
        close();
      } catch (err) {
        showErrors(form, err);
      }
    });
    dialog.showModal();
    const focusable = $('textarea, input:not([type=hidden]):not([type=radio]), [data-submit]', dialog);
    if (focusable) focusable.focus();
  });
}

export function confirmDialog({ title, message, confirmText = 'Confirm', danger = false, iconName }) {
  return openDialog({
    title,
    submitText: confirmText,
    danger,
    body: html`<div class="modal-icon ${danger ? 'danger' : 'info'}">${icon(iconName || (danger ? 'alert' : 'info'))}</div><p>${message}</p>`,
  }).then((r) => r === true);
}
