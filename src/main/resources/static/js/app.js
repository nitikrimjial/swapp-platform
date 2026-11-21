// Page bootstrap: authentication state, header/footer, badge counts and realtime updates.
import { http, ApiError } from './api.js';
import { html, render, icon, avatar, toast, $, $$, raw } from './ui.js';

let currentUser = null;
let verificationRequired = false;
const listeners = new Map();
let socket = null;
let socketOpen = false;
let reconnectDelay = 1000;
let pollTimer = null;
let pingTimer = null;

export const getUser = () => currentUser;

/** Subscribe to realtime events ("notification", "message", "messages-read", "counts"). */
export function onRealtime(type, handler) {
  if (!listeners.has(type)) listeners.set(type, new Set());
  listeners.get(type).add(handler);
}
function emit(type, data) {
  (listeners.get(type) || []).forEach((h) => { try { h(data); } catch (e) { console.error(e); } });
}
export const isRealtimeConnected = () => socketOpen;

/**
 * Initialise a page.
 * auth: 'required' (redirect to login), 'guest' (redirect signed-in users away) or 'optional'.
 */
export async function initPage({ auth = 'optional', active = '' } = {}) {
  applyStoredTheme();
  try {
    const session = await http.get('/api/auth/session');
    currentUser = session.user;
    verificationRequired = Boolean(session.emailVerificationRequired);
  } catch (err) {
    currentUser = null;
    if (!(err instanceof ApiError) || err.status !== 401) console.warn('Could not load session', err);
  }
  if (auth === 'required' && !currentUser) {
    location.replace('/login.html?next=' + encodeURIComponent(location.pathname + location.search));
    return new Promise(() => {}); // halt page script while navigating
  }
  if (auth === 'guest' && currentUser) {
    location.replace('/home.html');
    return new Promise(() => {});
  }
  renderHeader(active);
  renderVerificationBanner();
  renderFooter();
  if (currentUser) {
    refreshCounts();
    connectRealtime();
  }
  return { user: currentUser };
}

// ---------------------------------------------------------------- header

function renderHeader(active) {
  const header = document.createElement('header');
  header.className = 'site-header';
  const u = currentUser;
  const navLink = (href, key, iconName, label) =>
    html`<a class="nav-link ${active === key ? 'active' : ''}" href="${href}">${icon(iconName)}<span class="label-text">${label}</span></a>`;

  render(header, html`<div class="container">
    <a class="logo" href="${u ? '/home.html' : '/'}" aria-label="Swapp home"><span class="logo-mark">${icon('swap')}</span>Swapp</a>
    <form class="header-search" role="search" action="/browse.html">
      <div class="input-group">${icon('search')}<input class="input" type="search" name="q" placeholder="Search bikes, books, furniture…" aria-label="Search listings"></div>
    </form>
    <nav class="header-nav" aria-label="Main">
      ${navLink('/browse.html', 'browse', 'grid', 'Browse')}
      ${navLink('/browse.html?near=me', 'near', 'pin', 'Near me')}
      ${navLink('/giveaways.html', 'giveaways', 'gift', 'Free stuff')}
      ${u ? html`
        <a class="btn btn-primary btn-sm hide-sm" href="/item-new.html">${icon('plus')}List an item</a>
        <a class="icon-btn hide-sm ${active === 'messages' ? 'active' : ''}" href="/messages.html" aria-label="Messages">${icon('message')}<span class="count-badge hidden" data-count="unreadMessages"></span></a>
        <a class="icon-btn ${active === 'notifications' ? 'active' : ''}" href="/notifications.html" aria-label="Notifications">${icon('bell')}<span class="count-badge hidden" data-count="unreadNotifications"></span></a>
        <div class="dropdown">
          <button class="avatar-btn" id="user-menu-btn" aria-haspopup="true" aria-expanded="false" aria-label="Account menu">${avatar(u)}</button>
          <div class="menu hidden" id="user-menu" role="menu">
            <div class="menu-header"><strong class="ellipsis">${u.displayName}</strong><span class="small muted ellipsis">${u.email}</span></div>
            <a href="/dashboard.html" role="menuitem">${icon('dashboard')}Dashboard</a>
            <a href="/profile.html" role="menuitem">${icon('user')}My profile</a>
            <a href="/my-listings.html" role="menuitem">${icon('package')}My listings</a>
            <a href="/offers-incoming.html" role="menuitem">${icon('inbox')}Incoming offers</a>
            <a href="/offers-outgoing.html" role="menuitem">${icon('outbox')}Outgoing offers</a>
            <a href="/exchanges.html" role="menuitem">${icon('swap')}Exchanges</a>
            <a href="/giveaways.html?tab=incoming" role="menuitem">${icon('gift')}Donations &amp; giveaways</a>
            <a href="/messages.html" role="menuitem">${icon('message')}Messages</a>
            ${u.role === 'ADMIN' ? html`<a href="/admin.html" role="menuitem">${icon('shield')}Moderation</a>` : ''}
            <hr>
            <a href="/settings.html" role="menuitem">${icon('settings')}Settings</a>
            <button type="button" class="danger" id="logout-btn" role="menuitem">${icon('logout')}Sign out</button>
          </div>
        </div>` : html`
        <a class="btn btn-ghost btn-sm" href="/login.html">Sign in</a>
        <a class="btn btn-primary btn-sm" href="/register.html">Join free</a>`}
    </nav>
  </div>`);
  document.body.prepend(header);

  if (u) {
    const btn = $('#user-menu-btn');
    const menu = $('#user-menu');
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const open = menu.classList.toggle('hidden') === false;
      btn.setAttribute('aria-expanded', String(open));
    });
    document.addEventListener('click', (e) => { if (!menu.contains(e.target)) menu.classList.add('hidden'); });
    document.addEventListener('keydown', (e) => { if (e.key === 'Escape') menu.classList.add('hidden'); });
    $('#logout-btn').addEventListener('click', logout);

    const bottom = document.createElement('nav');
    bottom.className = 'bottom-nav';
    bottom.setAttribute('aria-label', 'Mobile');
    render(bottom, html`
      <a href="/home.html" class="${active === 'home' ? 'active' : ''}">${icon('home')}Home</a>
      <a href="/browse.html" class="${active === 'browse' ? 'active' : ''}">${icon('search')}Browse</a>
      <a href="/item-new.html" aria-label="List an item"><span class="plus">${icon('plus')}</span></a>
      <a href="/messages.html" class="${active === 'messages' ? 'active' : ''}">${icon('message')}Chats<span class="count-badge hidden" data-count="unreadMessages"></span></a>
      <a href="/dashboard.html" class="${active === 'dashboard' ? 'active' : ''}">${icon('user')}You</a>`);
    document.body.appendChild(bottom);
  }
  const q = new URLSearchParams(location.search).get('q');
  const searchInput = $('.header-search input');
  if (q && searchInput && location.pathname === '/browse.html') searchInput.value = q;
}

/** Reminds signed-in members to confirm their email; explains what is blocked when verification is required. */
function renderVerificationBanner() {
  const u = currentUser;
  if (!u || u.emailVerified || location.pathname === '/verify-email.html') return;
  const bar = document.createElement('div');
  bar.className = 'container';
  bar.style.paddingTop = '16px';
  render(bar, html`<div class="banner warning" role="status" style="margin-bottom:0;align-items:center;flex-wrap:wrap">
    ${icon('mail')}<div style="flex:1;min-width:220px"><strong>Confirm your email address.</strong>
    We sent a link to ${u.email}.${verificationRequired ? ' You can browse now, but you need to confirm it before posting, making offers or messaging.' : ''}</div>
    <button type="button" class="btn btn-secondary btn-sm" id="resend-verification">Resend email</button></div>`);
  document.querySelector('.site-header').after(bar);
  const button = bar.querySelector('#resend-verification');
  button.addEventListener('click', async () => {
    button.disabled = true;
    try {
      await http.post('/api/users/me/verification-email');
      toast(`We sent a new link to ${u.email}. It expires in 2 days.`);
    } catch (err) {
      toast(err.message, 'error');
      button.disabled = false;
    }
  });
}

function renderFooter() {
  const footer = document.createElement('footer');
  footer.className = 'site-footer';
  render(footer, html`<div class="container">
    <div class="footer-grid">
      <div><a class="logo" href="/"><span class="logo-mark">${icon('swap')}</span>Swapp</a>
        <p class="muted small" style="margin-top:12px;max-width:340px">Swapp helps neighbours exchange, donate and share everyday things, so less ends up in landfill and more ends up in use.</p></div>
      <div><h4>Discover</h4><ul><li><a href="/browse.html">Browse items</a></li><li><a href="/giveaways.html">Free &amp; donations</a></li><li><a href="/browse.html?type=REQUEST">Requests</a></li></ul></div>
      <div><h4>Your Swapp</h4><ul><li><a href="/dashboard.html">Dashboard</a></li><li><a href="/exchanges.html">Exchanges</a></li><li><a href="/messages.html">Messages</a></li></ul></div>
      <div><h4>Account</h4><ul>${currentUser
        ? html`<li><a href="/settings.html">Settings</a></li><li><a href="/profile.html">Profile</a></li>`
        : html`<li><a href="/register.html">Create account</a></li><li><a href="/login.html">Sign in</a></li>`}</ul></div>
    </div>
    <div class="footer-bottom"><span>© ${new Date().getFullYear()} Swapp. Built for communities.</span><span>Exchange · Donate · Share</span></div>
  </div>`);
  document.body.appendChild(footer);
}

async function logout() {
  try { await http.post('/api/auth/logout'); } catch { /* cookie is cleared server-side; navigate anyway */ }
  location.href = '/';
}

// ---------------------------------------------------------------- counts

export async function refreshCounts() {
  if (!currentUser) return null;
  try {
    const counts = await http.get('/api/users/me/summary');
    $$('[data-count]').forEach((el) => {
      const n = counts[el.dataset.count] || 0;
      el.textContent = n > 99 ? '99+' : String(n);
      el.classList.toggle('hidden', n === 0);
    });
    emit('counts', counts);
    return counts;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- realtime

function connectRealtime() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
  try {
    socket = new WebSocket(`${scheme}://${location.host}/ws`);
  } catch {
    startPolling();
    return;
  }
  socket.addEventListener('open', () => {
    socketOpen = true;
    reconnectDelay = 1000;
    stopPolling();
  });
  socket.addEventListener('message', (event) => {
    let msg;
    try { msg = JSON.parse(event.data); } catch { return; }
    if (msg.type === 'notification') {
      const n = msg.data;
      toast(n.body, 'info', { title: n.title, link: n.link });
      refreshCounts();
    } else if (msg.type === 'message') {
      refreshCounts();
    }
    emit(msg.type, msg.data);
  });
  socket.addEventListener('close', (event) => {
    socketOpen = false;
    startPolling();
    // 1008 = closed on purpose by the server (session revoked, too many tabs): don't hammer it, polling covers us.
    const delay = event.code === 1008 ? 30000 : reconnectDelay;
    setTimeout(connectRealtime, delay);
    reconnectDelay = Math.min(reconnectDelay * 2, 30000);
  });
  // One keep-alive timer for the page, not one per reconnect.
  if (!pingTimer) pingTimer = setInterval(() => { if (socketOpen) socket.send('ping'); }, 25000);
}

function startPolling() {
  if (pollTimer) return;
  pollTimer = setInterval(async () => {
    const counts = await refreshCounts();
    if (counts) emit('poll', counts);
  }, 20000);
}
function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
}

// ---------------------------------------------------------------- theme

export function applyStoredTheme() {
  let theme = null;
  try { theme = localStorage.getItem('swapp.theme'); } catch { /* storage unavailable */ }
  if (theme === 'light' || theme === 'dark') document.documentElement.dataset.theme = theme;
  else delete document.documentElement.dataset.theme;
}

export function setTheme(theme) {
  try {
    if (theme === 'system') localStorage.removeItem('swapp.theme');
    else localStorage.setItem('swapp.theme', theme);
  } catch { /* ignore */ }
  applyStoredTheme();
}

export { raw };
