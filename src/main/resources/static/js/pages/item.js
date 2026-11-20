import { initPage } from '../app.js';
import { http } from '../api.js';
import {
  html, render, icon, $, $$, param, typeBadge, itemStatusBadge, CONDITIONS, LISTING_TYPES,
  avatar, timeAgo, formatDate, emptyState, errorState, toast, openDialog, confirmDialog, itemMedia,
} from '../ui.js';

const { user } = await initPage({ auth: 'optional' });
const root = $('#item-root');
const id = Number(param('id'));

if (!id) {
  render(root, emptyState({ iconName: 'search', title: 'Item not found', text: 'This link looks incomplete.', action: html`<a class="btn btn-primary" href="/browse.html">Browse items</a>` }));
} else {
  load();
}

async function load() {
  try {
    const item = await http.get(`/api/items/${id}`);
    document.title = `${item.title} · Swapp`;
    renderItem(item);
  } catch (err) {
    if (err.status === 404) {
      render(root, emptyState({ iconName: 'package', title: 'This listing is no longer available', text: 'It may have been removed by its owner.', action: html`<a class="btn btn-primary" href="/browse.html">Browse other items</a>` }));
    } else {
      render(root, errorState(err.message, load));
    }
  }
}

function renderItem(item) {
  const v = item.viewer;
  const type = LISTING_TYPES[item.listingType];
  render(root, html`
    <nav class="small muted" style="margin-bottom:16px"><a href="/browse.html">Browse</a> /
      <a href="/browse.html?categoryId=${item.category.id}">${item.category.name}</a></nav>
    <div class="detail-grid">
      <div class="detail-main">
        <div class="gallery-main" id="gallery-main"></div>
        <div class="gallery-thumbs" id="gallery-thumbs"></div>
        <div class="card detail-description"><div class="card-body">
          <h2>Description</h2>
          <div class="description">${item.description}</div>
        </div></div>
      </div>
      <div class="detail-side">
        <div class="card"><div class="card-body">
          <div class="row-wrap">${typeBadge(item.listingType)}${itemStatusBadge(item.status)}</div>
          <h1 class="detail-title">${item.title}</h1>
          <dl class="meta-list">
            <dt>${icon('star', 'icon-sm')}${item.listingType === 'REQUEST' ? 'Min. condition' : 'Condition'}</dt><dd>${CONDITIONS[item.condition]}</dd>
            <dt>${icon(item.category.icon, 'icon-sm')}Category</dt><dd>${item.category.name}</dd>
            <dt>${icon('pin', 'icon-sm')}Location</dt><dd>${item.location}</dd>
            <dt>${icon('clock', 'icon-sm')}Listed</dt><dd title="${formatDate(item.createdAt)}">${timeAgo(item.createdAt)}</dd>
          </dl>
          <div id="actions" style="margin-top:22px"></div>
        </div></div>
        <div class="card"><div class="card-body">
          <div class="row">
            <a class="user-chip" href="/profile.html?id=${item.owner.id}" style="flex:1">${avatar(item.owner, 'lg')}
              <div class="ellipsis"><span class="small muted">${item.listingType === 'REQUEST' ? 'Requested by' : 'Listed by'}</span>
              <strong>${item.owner.displayName}</strong>${item.owner.location ? html`<span>${icon('pin', 'icon-sm')} ${item.owner.location}</span>` : ''}</div></a>
            ${!v.owner ? html`<button class="btn btn-secondary btn-sm" id="message-owner">${icon('message')}Message</button>` : ''}
          </div>
        </div></div>
        <p class="small muted" style="display:flex;gap:8px;align-items:flex-start">${icon('shield', 'icon-sm')}
          <span>Meet in a public place, check the item before swapping, and keep conversations on Swapp.</span></p>
      </div>
    </div>`);

  renderGallery(item);
  renderActions(item, type);
  const msg = $('#message-owner');
  if (msg) msg.addEventListener('click', () => {
    if (!user) {
      location.href = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
      return;
    }
    startConversation(item, `Hi ${item.owner.displayName.split(' ')[0]}, I'm interested in "${item.title}".`);
  });
}

function renderGallery(item) {
  const main = $('#gallery-main');
  const thumbs = $('#gallery-thumbs');
  if (!item.images.length) {
    render(main, itemMedia({ ...item, coverImageUrl: null }));
    return;
  }
  let index = 0;
  const show = (i) => {
    index = (i + item.images.length) % item.images.length;
    render(main, html`<img src="${item.images[index].url}" alt="${item.title}, photo ${index + 1} of ${item.images.length}">
      ${item.images.length > 1 ? html`<button class="nav-arrow prev" aria-label="Previous photo">${icon('chevronLeft')}</button>
      <button class="nav-arrow next" aria-label="Next photo">${icon('chevronRight')}</button>` : ''}`);
    $$('button', thumbs).forEach((b, bi) => b.classList.toggle('active', bi === index));
    const prev = $('.prev', main);
    if (prev) {
      prev.addEventListener('click', () => show(index - 1));
      $('.next', main).addEventListener('click', () => show(index + 1));
    }
  };
  if (item.images.length > 1) {
    render(thumbs, item.images.map((img, i) => html`<button aria-label="Show photo ${i + 1}"><img src="${img.url}" alt=""></button>`));
    $$('button', thumbs).forEach((b, i) => b.addEventListener('click', () => show(i)));
  }
  show(0);
}

function renderActions(item, type) {
  const box = $('#actions');
  const v = item.viewer;
  const signInHref = `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;

  if (v.owner) {
    render(box, html`
      ${item.status === 'RESERVED' ? html`<div class="banner warning">${icon('clock')}<span>This item is reserved in an active ${item.listingType === 'EXCHANGE' ? 'exchange' : 'hand-over'}.</span></div>` : ''}
      ${v.pendingOffersReceived ? html`<a class="banner info" href="/offers-incoming.html">${icon('inbox')}<span><strong>${v.pendingOffersReceived}</strong> pending ${v.pendingOffersReceived === 1 ? 'offer' : 'offers'} for this item. Review now →</span></a>` : ''}
      ${v.pendingDonationRequests ? html`<a class="banner info" href="/giveaways.html?tab=incoming">${icon('inbox')}<span><strong>${v.pendingDonationRequests}</strong> ${v.pendingDonationRequests === 1 ? 'person wants' : 'people want'} this item. Review requests →</span></a>` : ''}
      <div class="row-wrap">
        ${['AVAILABLE', 'UNAVAILABLE'].includes(item.status) ? html`
          <a class="btn btn-primary" href="/item-edit.html?id=${item.id}">${icon('edit')}Edit listing</a>
          <button class="btn btn-secondary" id="toggle-status">${item.status === 'AVAILABLE' ? html`${icon('eyeOff')}Mark unavailable` : html`${icon('eye')}Mark available`}</button>` : ''}
        ${item.status !== 'RESERVED' ? html`<button class="btn btn-danger-ghost" id="delete-item">${icon('trash')}Delete</button>` : ''}
      </div>`);
    const toggle = $('#toggle-status');
    if (toggle) toggle.addEventListener('click', () => toggleStatus(item));
    const del = $('#delete-item');
    if (del) del.addEventListener('click', () => deleteItem(item));
    return;
  }

  if (item.status !== 'AVAILABLE') {
    const msg = item.status === 'RESERVED' ? 'This item is currently reserved for someone else.' : 'This item is no longer available.';
    render(box, html`<div class="banner warning">${icon('info')}<span>${msg}</span></div>
      <a class="btn btn-secondary btn-block" href="/browse.html?categoryId=${item.category.id}">See similar items</a>`);
    return;
  }

  if (!v.authenticated) {
    const label = { EXCHANGE: 'Sign in to make an offer', DONATION: 'Sign in to request this item', GIVEAWAY: 'Sign in to request this item', REQUEST: 'Sign in to help out' }[item.listingType];
    render(box, html`<a class="btn btn-primary btn-lg btn-block" href="${signInHref}">${label}</a>
      <p class="small muted" style="margin:10px 0 0;text-align:center">New here? <a href="/register.html?next=${encodeURIComponent(location.pathname + location.search)}">Create a free account</a></p>`);
    return;
  }

  if (item.listingType === 'EXCHANGE') {
    if (v.pendingOfferId) {
      render(box, html`<div class="banner success">${icon('checkCircle')}<span>You have a pending offer on this item. We'll notify you when the owner responds.</span></div>
        <div class="row-wrap"><a class="btn btn-secondary" href="/offers-outgoing.html">View my offers</a>
        <button class="btn btn-ghost" id="offer-again">${icon('plus')}Offer another item</button></div>`);
      $('#offer-again').addEventListener('click', () => makeOffer(item));
    } else {
      render(box, html`<button class="btn btn-primary btn-lg btn-block" id="make-offer">${icon('swap')}Make an exchange offer</button>
        <p class="small muted" style="margin:10px 0 0;text-align:center">Offer one of your own listed items in return.</p>`);
      $('#make-offer').addEventListener('click', () => makeOffer(item));
    }
  } else if (item.listingType === 'DONATION' || item.listingType === 'GIVEAWAY') {
    if (v.donationRequestId) {
      render(box, html`<div class="banner ${v.donationRequestStatus === 'ACCEPTED' ? 'success' : 'info'}">${icon(v.donationRequestStatus === 'ACCEPTED' ? 'checkCircle' : 'clock')}
        <span>${v.donationRequestStatus === 'ACCEPTED' ? 'The owner reserved this item for you. Message them to arrange pick-up.' : 'Your request has been sent. The owner will choose who receives it.'}</span></div>
        <div class="row-wrap"><a class="btn btn-secondary" href="/giveaways.html?tab=outgoing">View my requests</a>
        <button class="btn btn-danger-ghost" id="cancel-request">Withdraw request</button></div>`);
      $('#cancel-request').addEventListener('click', async () => {
        if (!(await confirmDialog({ title: 'Withdraw your request?', message: 'The owner will be notified. You can request again later while the item is available.', confirmText: 'Withdraw', danger: true }))) return;
        try { await http.post(`/api/donation-requests/${v.donationRequestId}/cancel`); toast('Request withdrawn'); load(); } catch (err) { toast(err.message, 'error'); }
      });
    } else {
      render(box, html`<button class="btn btn-primary btn-lg btn-block" id="request-item">${icon(LISTING_TYPES[item.listingType].icon)}Request this item</button>
        <p class="small muted" style="margin:10px 0 0;text-align:center">The owner picks who receives it. It's free.</p>`);
      $('#request-item').addEventListener('click', () => requestItem(item));
    }
  } else {
    render(box, html`<button class="btn btn-primary btn-lg btn-block" id="help-out">${icon('hand')}I have this, let's talk</button>
      <p class="small muted" style="margin:10px 0 0;text-align:center">Start a private conversation with ${item.owner.displayName}.</p>`);
    $('#help-out').addEventListener('click', () => startConversation(item, `Hi! I saw your request for "${item.title}". I think I can help.`));
  }
}

async function makeOffer(item) {
  let mine;
  try {
    mine = await http.get('/api/users/me/offerable-items');
  } catch (err) {
    toast(err.message, 'error');
    return;
  }
  if (!mine.length) {
    await openDialog({
      title: 'List an item to offer',
      body: html`<div class="modal-icon info">${icon('package')}</div>
        <p>To make an exchange offer you need at least one available item listed for <strong>exchange</strong>. List one now, then come back to make your offer.</p>`,
      submitText: 'List an item',
      onSubmit: () => { location.href = `/item-new.html?type=EXCHANGE&returnTo=${encodeURIComponent('/item.html?id=' + item.id)}`; return false; },
    });
    return;
  }
  const result = await openDialog({
    title: `Make an offer for "${item.title}"`,
    wide: true,
    submitText: 'Send offer',
    body: html`<p class="muted" style="margin-top:0">Choose one of your items to offer in exchange.</p>
      <div class="field"><div class="choice-grid" role="radiogroup" aria-label="Your items">
      ${mine.map((m, i) => html`<label class="choice"><input type="radio" name="offeredItemId" value="${m.id}" ${i === 0 ? 'checked' : ''}>
        <div class="choice-icon" style="overflow:hidden;padding:0;width:52px;height:52px">${itemMedia(m)}</div>
        <div style="min-width:0"><strong class="ellipsis">${m.title}</strong><span class="small">${CONDITIONS[m.condition]}</span></div></label>`)}
      </div></div>
      <div class="field"><label for="offer-message">Message <span class="muted">(optional)</span></label>
        <textarea class="textarea" id="offer-message" name="message" maxlength="1000" placeholder="Say hello and explain why it's a good swap…"></textarea></div>`,
    onSubmit: async (dialog) => {
      const offeredItemId = Number($('input[name=offeredItemId]:checked', dialog).value);
      const message = $('#offer-message', dialog).value.trim();
      return http.post('/api/offers', { requestedItemId: item.id, offeredItemId, message: message || null });
    },
  });
  if (result) {
    toast('Offer sent! The owner has been notified.', 'success', { link: '/offers-outgoing.html' });
    load();
  }
}

async function requestItem(item) {
  const result = await openDialog({
    title: `Request "${item.title}"`,
    submitText: 'Send request',
    body: html`<p class="muted" style="margin-top:0">Tell ${item.owner.displayName} a little about why you'd like it. A friendly note helps!</p>
      <div class="field"><label for="req-message">Message <span class="muted">(optional)</span></label>
      <textarea class="textarea" id="req-message" name="message" maxlength="1000" placeholder="e.g. This would be perfect for my daughter's new room…"></textarea></div>`,
    onSubmit: (dialog) => {
      const message = $('#req-message', dialog).value.trim();
      return http.post('/api/donation-requests', { itemId: item.id, message: message || null });
    },
  });
  if (result) {
    toast('Request sent! The owner has been notified.');
    load();
  }
}

async function startConversation(item, suggested) {
  const result = await openDialog({
    title: `Message ${item.owner.displayName}`,
    submitText: 'Send message',
    body: html`<div class="field"><label for="dm-body">Your message</label>
      <textarea class="textarea" id="dm-body" name="body" maxlength="2000" required>${suggested}</textarea></div>`,
    onSubmit: (dialog) => {
      const body = $('#dm-body', dialog).value.trim();
      if (!body) throw Object.assign(new Error('Message cannot be empty'), { fieldErrors: { body: 'Message cannot be empty' } });
      return http.post('/api/conversations', { recipientId: item.owner.id, itemId: item.id, body });
    },
  });
  if (result) location.href = `/messages.html?c=${result.id}`;
}

async function toggleStatus(item) {
  const target = item.status === 'AVAILABLE' ? 'UNAVAILABLE' : 'AVAILABLE';
  if (target === 'UNAVAILABLE' && !(await confirmDialog({
    title: 'Hide this listing?',
    message: 'It will disappear from search, and any pending offers or requests for it will be closed.',
    confirmText: 'Mark unavailable',
  }))) return;
  try {
    await http.patch(`/api/items/${item.id}/status`, { status: target });
    toast(target === 'AVAILABLE' ? 'Listing is visible again' : 'Listing hidden');
    load();
  } catch (err) {
    toast(err.message, 'error');
  }
}

async function deleteItem(item) {
  if (!(await confirmDialog({
    title: 'Delete this listing?',
    message: `"${item.title}" will be removed permanently. Pending offers and requests will be closed and the people involved notified.`,
    confirmText: 'Delete listing', danger: true,
  }))) return;
  try {
    await http.del(`/api/items/${item.id}`);
    toast('Listing deleted');
    location.href = '/my-listings.html';
  } catch (err) {
    toast(err.message, 'error');
  }
}
