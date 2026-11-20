import { initPage, onRealtime, refreshCounts, isRealtimeConnected } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, $$, param, avatar, timeAgo, formatTime, formatDate, emptyState, errorState, loadingBlock, toast } from '../ui.js';

const { user } = await initPage({ auth: 'required', active: 'messages' });

const shell = $('#shell');
let conversations = [];
let active = null;          // { id, otherUser } or draft { draft: true, otherUser, itemId }
let messages = [];
let hasOlder = false;

render('#pane-empty', html`<div>${emptyState({ iconName: 'message', title: 'Select a conversation', text: 'Choose a conversation on the left, or message someone from a listing.' })}</div>`);

async function loadConversations() {
  try {
    const data = await http.get('/api/conversations', { size: 50 });
    conversations = data.content;
    renderConversations();
  } catch (err) {
    render('#convos', errorState(err.message, loadConversations));
  }
}

function renderConversations() {
  if (!conversations.length) {
    render('#convos', html`<div style="padding:24px">${emptyState({ iconName: 'message', title: 'No conversations yet', text: 'Message an owner from any listing to start chatting.', action: html`<a class="btn btn-primary btn-sm" href="/browse.html">Browse items</a>` })}</div>`);
    return;
  }
  render('#convos', conversations.map((c) => {
    const last = c.lastMessage;
    const preview = last ? (last.senderId === user.id ? 'You: ' : '') + last.body : 'No messages yet';
    return html`<a class="convo ${active && active.id === c.id ? 'active' : ''} ${c.unreadCount ? 'unread' : ''}" href="?c=${c.id}" data-id="${c.id}">
      ${avatar(c.otherUser)}
      <div class="convo-text"><div class="convo-top"><strong>${c.otherUser.displayName}</strong><time>${timeAgo(c.lastMessageAt)}</time></div>
      <div class="preview">${preview}</div></div>
      ${c.unreadCount ? html`<span class="unread-dot" aria-label="${c.unreadCount} unread"></span>` : ''}
    </a>`;
  }));
  $$('#convos .convo').forEach((a) => a.addEventListener('click', (e) => {
    e.preventDefault();
    openConversation(Number(a.dataset.id));
  }));
}

async function openConversation(id, push = true) {
  const known = conversations.find((c) => c.id === id);
  let convo = known;
  if (!convo) {
    try { convo = await http.get(`/api/conversations/${id}`); } catch (err) { toast(err.message, 'error'); return; }
  }
  active = { id: convo.id, otherUser: convo.otherUser };
  if (push) history.replaceState(null, '', `?c=${id}`);
  shell.classList.add('has-active');
  renderPaneShell();
  renderConversations();
  render('#chat-messages', loadingBlock());
  try {
    messages = await http.get(`/api/conversations/${id}/messages`, { size: 50 });
    hasOlder = messages.length === 50;
    renderMessages(true);
    markRead();
  } catch (err) {
    render('#chat-messages', errorState(err.message, () => openConversation(id, false)));
  }
}

async function openDraft(toId, itemId) {
  const existing = conversations.find((c) => c.otherUser.id === toId);
  if (existing) { openConversation(existing.id); return; }
  try {
    const profile = await http.get(`/api/users/${toId}`);
    active = { draft: true, otherUser: profile, itemId };
    shell.classList.add('has-active');
    renderPaneShell();
    messages = [];
    render('#chat-messages', html`<div class="chat-empty">${emptyState({ iconName: 'message', title: `Start a conversation with ${profile.displayName}`, text: 'Say hello and ask about the item. Keep it friendly!' })}</div>`);
  } catch (err) {
    toast(err.message, 'error');
  }
}

function renderPaneShell() {
  const other = active.otherUser;
  render('#pane', html`
    <div class="chat-head">
      <button class="icon-btn back-btn" id="back" aria-label="Back to conversations">${icon('arrowLeft')}</button>
      <a class="user-chip" href="/profile.html?id=${other.id}">${avatar(other)}<div><strong>${other.displayName}</strong>
        ${other.location ? html`<span>${other.location}</span>` : ''}</div></a>
    </div>
    <div class="chat-messages" id="chat-messages" aria-live="polite"></div>
    <form class="chat-compose" id="compose">
      <label class="sr-only" for="compose-body">Message</label>
      <textarea class="textarea" id="compose-body" maxlength="2000" placeholder="Write a message…" rows="1" required></textarea>
      <button class="btn btn-primary btn-icon" type="submit" aria-label="Send">${icon('send')}</button>
    </form>`);
  $('#back').addEventListener('click', () => {
    shell.classList.remove('has-active');
    active = null;
    history.replaceState(null, '', location.pathname);
    renderConversations();
  });
  const textarea = $('#compose-body');
  textarea.addEventListener('input', () => { textarea.style.height = 'auto'; textarea.style.height = Math.min(textarea.scrollHeight, 140) + 'px'; });
  textarea.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); $('#compose').requestSubmit(); }
  });
  $('#compose').addEventListener('submit', send);
  textarea.focus();
}

function renderMessages(scrollToEnd) {
  const box = $('#chat-messages');
  if (!box) return;
  if (!messages.length) {
    render(box, html`<div class="chat-empty">${emptyState({ iconName: 'message', title: 'No messages yet', text: 'Say hello!' })}</div>`);
    return;
  }
  let lastDay = '';
  const parts = [];
  if (hasOlder) parts.push(html`<button class="btn btn-ghost btn-sm" id="load-older" style="align-self:center">Load earlier messages</button>`);
  for (const m of messages) {
    const day = formatDate(m.createdAt);
    if (day !== lastDay) { parts.push(html`<div class="day-sep">${day}</div>`); lastDay = day; }
    const mine = m.senderId === user.id;
    parts.push(html`<div class="msg ${mine ? 'mine' : ''}">
      ${m.item ? html`<a class="item-ref" href="/item.html?id=${m.item.id}"><span class="ref-thumb">${m.item.coverImageUrl ? html`<img src="${m.item.coverImageUrl}" alt="">` : icon('package')}</span><span class="ellipsis">${m.item.title}</span></a>` : ''}
      <div class="bubble">${m.body}</div>
      <time datetime="${m.createdAt}">${formatTime(m.createdAt)}${mine && m.read ? ' · Seen' : ''}</time>
    </div>`);
  }
  const prevHeight = box.scrollHeight;
  const prevTop = box.scrollTop;
  render(box, parts);
  if (scrollToEnd) box.scrollTop = box.scrollHeight;
  else box.scrollTop = box.scrollHeight - prevHeight + prevTop;
  const older = $('#load-older');
  if (older) older.addEventListener('click', loadOlder);
}

async function loadOlder() {
  if (!messages.length) return;
  try {
    const batch = await http.get(`/api/conversations/${active.id}/messages`, { before: messages[0].id, size: 50 });
    hasOlder = batch.length === 50;
    messages = batch.concat(messages);
    renderMessages(false);
  } catch (err) {
    toast(err.message, 'error');
  }
}

async function send(e) {
  e.preventDefault();
  const textarea = $('#compose-body');
  const body = textarea.value.trim();
  if (!body) return;
  const button = $('#compose button[type=submit]');
  button.disabled = true;
  try {
    if (active.draft) {
      const convo = await http.post('/api/conversations', { recipientId: active.otherUser.id, itemId: active.itemId || null, body });
      textarea.value = '';
      await loadConversations();
      await openConversation(convo.id);
    } else {
      const msg = await http.post(`/api/conversations/${active.id}/messages`, { body });
      textarea.value = '';
      textarea.style.height = '';
      messages.push(msg);
      renderMessages(true);
      bumpConversation(active.id, msg);
    }
  } catch (err) {
    toast(err.message, 'error');
  } finally {
    button.disabled = false;
    textarea.focus();
  }
}

function bumpConversation(id, msg, unreadDelta = 0) {
  const c = conversations.find((x) => x.id === id);
  if (!c) { loadConversations(); return; }
  c.lastMessage = msg;
  c.lastMessageAt = msg.createdAt;
  c.unreadCount = (c.unreadCount || 0) + unreadDelta;
  conversations = [c, ...conversations.filter((x) => x.id !== id)];
  renderConversations();
}

async function markRead() {
  if (!active || active.draft) return;
  const c = conversations.find((x) => x.id === active.id);
  if (c) { c.unreadCount = 0; renderConversations(); }
  try { await http.post(`/api/conversations/${active.id}/read`); refreshCounts(); } catch { /* non-critical */ }
}

onRealtime('message', (msg) => {
  if (active && !active.draft && msg.conversationId === active.id) {
    if (!messages.some((m) => m.id === msg.id)) messages.push(msg);
    renderMessages(true);
    bumpConversation(msg.conversationId, msg);
    if (document.visibilityState === 'visible') markRead();
  } else {
    bumpConversation(msg.conversationId, msg, 1);
  }
});
onRealtime('messages-read', (data) => {
  if (active && data.conversationId === active.id) {
    messages.forEach((m) => { if (m.senderId === user.id) m.read = true; });
    renderMessages(false);
  }
});
// Fallback when the WebSocket is unavailable: poll the open conversation.
onRealtime('poll', async () => {
  if (isRealtimeConnected()) return;
  await loadConversations();
  if (active && !active.draft) {
    try {
      const latest = await http.get(`/api/conversations/${active.id}/messages`, { size: 50 });
      if (latest.length && (!messages.length || latest[latest.length - 1].id !== messages[messages.length - 1].id)) {
        messages = latest;
        renderMessages(true);
        markRead();
      }
    } catch { /* ignore */ }
  }
});
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') markRead(); });

await loadConversations();
const c = Number(param('c'));
const to = Number(param('to'));
if (c) openConversation(c, false);
else if (to && to !== user.id) openDraft(to, Number(param('item')) || null);
