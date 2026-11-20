import { initPage } from '../app.js';
import { http, ApiError } from '../api.js';
import {
  html, render, icon, $, $$, param, CONDITIONS, LISTING_TYPES, toast, withLoading, showErrors, clearErrors,
  fieldError, errorState, emptyState, confirmDialog, safeNext,
} from '../ui.js';

const MAX_IMAGES = 6;
const MAX_BYTES = 5 * 1024 * 1024;
const ACCEPTED = ['image/jpeg', 'image/png', 'image/gif', 'image/webp'];

const mode = document.body.dataset.mode; // create | edit
const { user } = await initPage({ auth: 'required', active: mode === 'create' ? 'new' : '' });
const root = $('#form-root');

let item = null;              // existing item (edit mode)
let queued = [];              // { file, url } not yet uploaded
let categories = [];

try {
  categories = await http.get('/api/categories');
  if (mode === 'edit') {
    const id = Number(param('id'));
    if (!id) throw new ApiError(404, 'Listing not found');
    item = await http.get(`/api/items/${id}`);
    if (!item.viewer.owner) throw new ApiError(403, 'You can only edit your own listings.');
    if (!['AVAILABLE', 'UNAVAILABLE'].includes(item.status)) {
      render(root, emptyState({ iconName: 'lock', title: 'This listing cannot be edited', text: 'Listings that are reserved in an active exchange or already completed are locked.', action: html`<a class="btn btn-primary" href="/item.html?id=${item.id}">Back to listing</a>` }));
      throw null;
    }
  }
  renderForm();
} catch (err) {
  if (err) render(root, err.status === 404 || err.status === 403 ? emptyState({ iconName: 'alert', title: err.message, action: html`<a class="btn btn-primary" href="/my-listings.html">My listings</a>` }) : errorState(err.message, () => location.reload()));
}

function renderForm() {
  const initialType = item ? item.listingType : (LISTING_TYPES[param('type')] ? param('type') : 'EXCHANGE');
  render(root, html`<form id="item-form" class="stack" novalidate>
    <div class="card"><div class="card-body">
      <h2>What kind of listing is this?</h2>
      <div class="field" style="margin-bottom:0">
        <div class="choice-grid" role="radiogroup" aria-label="Listing type">
          ${Object.entries(LISTING_TYPES).map(([key, t]) => html`<label class="choice">
            <input type="radio" name="listingType" value="${key}" ${key === initialType ? 'checked' : ''}>
            <div class="choice-icon badge-${t.cls}">${icon(t.icon)}</div>
            <div><strong>${t.label}</strong><span class="small">${t.desc}</span></div></label>`)}
        </div>
      </div>
    </div></div>

    <div class="card"><div class="card-body">
      <h2>Details</h2>
      <div class="field">
        <label for="title" id="title-label">Title</label>
        <input class="input" id="title" name="title" maxlength="120" required placeholder="e.g. Trek road bike, 54cm frame">
      </div>
      <div class="field">
        <label for="description">Description</label>
        <textarea class="textarea" id="description" name="description" maxlength="4000" required rows="6" placeholder="Describe the item: size, age, any wear or defects, and what you'd like in return."></textarea>
        <span class="char-count" id="desc-count">0 / 4000</span>
      </div>
      <div class="form-grid cols-2">
        <div class="field">
          <label for="categoryId">Category</label>
          <select class="select" id="categoryId" name="categoryId" required>
            <option value="">Choose a category</option>
            ${categories.map((c) => html`<option value="${c.id}">${c.name}</option>`)}
          </select>
        </div>
        <div class="field">
          <label for="condition" id="condition-label">Condition</label>
          <select class="select" id="condition" name="condition" required>
            <option value="">Choose condition</option>
            ${Object.entries(CONDITIONS).map(([k, v]) => html`<option value="${k}">${v}</option>`)}
          </select>
        </div>
      </div>
      <div class="field" style="margin-bottom:0">
        <label for="location">Location</label>
        <div class="input-group">${icon('pin')}<input class="input" id="location" name="location" maxlength="120" required placeholder="Neighbourhood or city"></div>
        <span class="hint">Only share a general area. Arrange exact meeting points in messages.</span>
      </div>
    </div></div>

    <div class="card"><div class="card-body">
      <h2>Photos</h2>
      <p class="muted small">Up to ${MAX_IMAGES} photos (JPEG, PNG, GIF or WebP, max 5 MB each). The first photo is the cover.</p>
      <label class="dropzone" id="dropzone" tabindex="0">
        ${icon('upload')}<strong>Click to add photos or drag them here</strong>
        <span class="small">Good photos get more offers</span>
        <input type="file" id="file-input" accept="${ACCEPTED.join(',')}" multiple class="sr-only">
      </label>
      <div class="thumb-grid" id="thumbs"></div>
    </div></div>

    <div class="row" style="justify-content:flex-end">
      <a class="btn btn-ghost" href="${item ? '/item.html?id=' + item.id : '/my-listings.html'}">Cancel</a>
      <button type="submit" class="btn btn-primary btn-lg" id="submit-btn" data-loading-text="${item ? 'Saving' : 'Publishing'}">${item ? 'Save changes' : 'Publish listing'}</button>
    </div>
  </form>`);

  const form = $('#item-form');
  const f = form.elements;
  if (item) {
    f.title.value = item.title;
    f.description.value = item.description;
    f.categoryId.value = item.category.id;
    f.condition.value = item.condition;
    f.location.value = item.location;
  } else if (user.location) {
    f.location.value = user.location;
  }
  const count = () => { $('#desc-count').textContent = `${f.description.value.length} / 4000`; };
  f.description.addEventListener('input', count);
  count();

  const typeChanged = () => {
    const type = $('input[name=listingType]:checked', form).value;
    $('#title-label').textContent = type === 'REQUEST' ? 'What are you looking for?' : 'Title';
    $('#condition-label').textContent = type === 'REQUEST' ? 'Minimum acceptable condition' : 'Condition';
  };
  $$('input[name=listingType]', form).forEach((r) => r.addEventListener('change', typeChanged));
  typeChanged();

  const input = $('#file-input');
  const zone = $('#dropzone');
  input.addEventListener('change', () => { addFiles(input.files); input.value = ''; });
  zone.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); input.click(); } });
  ['dragenter', 'dragover'].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.add('dragover'); }));
  ['dragleave', 'drop'].forEach((ev) => zone.addEventListener(ev, (e) => { e.preventDefault(); zone.classList.remove('dragover'); }));
  zone.addEventListener('drop', (e) => addFiles(e.dataTransfer.files));

  renderThumbs();
  form.addEventListener('submit', (e) => { e.preventDefault(); submit(form); });
}

function imageCount() {
  return (item ? item.images.length : 0) + queued.length;
}

async function addFiles(fileList) {
  const files = Array.from(fileList);
  const rejected = [];
  const accepted = [];
  for (const file of files) {
    if (!ACCEPTED.includes(file.type)) rejected.push(`${file.name}: unsupported format`);
    else if (file.size > MAX_BYTES) rejected.push(`${file.name}: larger than 5 MB`);
    else if (imageCount() + accepted.length >= MAX_IMAGES) rejected.push(`${file.name}: maximum of ${MAX_IMAGES} photos`);
    else accepted.push(file);
  }
  if (rejected.length) toast(rejected.join(' · '), 'error', { title: 'Some photos were skipped' });
  if (!accepted.length) return;

  if (item) {
    // Edit mode: upload immediately so the gallery reflects the server state.
    const fd = new FormData();
    accepted.forEach((f) => fd.append('files', f));
    try {
      $('#dropzone').classList.add('dragover');
      item = await http.post(`/api/items/${item.id}/images`, fd);
      toast(accepted.length === 1 ? 'Photo added' : `${accepted.length} photos added`);
    } catch (err) {
      toast(err.message, 'error');
    } finally {
      $('#dropzone').classList.remove('dragover');
    }
  } else {
    accepted.forEach((file) => queued.push({ file, url: URL.createObjectURL(file) }));
  }
  renderThumbs();
}

function renderThumbs() {
  const existing = item ? item.images.map((img, i) => ({ kind: 'saved', id: img.id, url: img.url, index: i })) : [];
  const pending = queued.map((q, i) => ({ kind: 'queued', url: q.url, index: i }));
  const all = [...existing, ...pending];
  render('#thumbs', all.map((t, pos) => html`<div class="thumb">
    <img src="${t.url}" alt="Photo ${pos + 1}">
    ${pos === 0 ? html`<span class="badge badge-exchange cover-tag">Cover</span>` : ''}
    <div class="thumb-actions">
      ${pos > 0 ? html`<button type="button" data-cover="${pos}" aria-label="Make cover photo" title="Make cover">${icon('star', 'icon-sm')}</button>` : html`<span></span>`}
      <button type="button" data-remove="${pos}" aria-label="Remove photo" title="Remove">${icon('trash', 'icon-sm')}</button>
    </div></div>`));
  $$('#thumbs [data-remove]').forEach((b) => b.addEventListener('click', () => removeAt(all[Number(b.dataset.remove)])));
  $$('#thumbs [data-cover]').forEach((b) => b.addEventListener('click', () => makeCover(all[Number(b.dataset.cover)], all)));
  $('#dropzone').classList.toggle('hidden', imageCount() >= MAX_IMAGES);
}

async function removeAt(t) {
  if (t.kind === 'queued') {
    URL.revokeObjectURL(queued[t.index].url);
    queued.splice(t.index, 1);
    renderThumbs();
    return;
  }
  if (!(await confirmDialog({ title: 'Remove this photo?', message: 'The photo will be deleted from your listing.', confirmText: 'Remove', danger: true }))) return;
  try {
    item = await http.del(`/api/items/${item.id}/images/${t.id}`);
    renderThumbs();
  } catch (err) {
    toast(err.message, 'error');
  }
}

async function makeCover(t, all) {
  if (t.kind === 'queued') {
    const [moved] = queued.splice(t.index, 1);
    queued.unshift(moved);
    if (item && item.images.length) toast('New photos are added after existing ones. Upload first, then set the cover.', 'info');
    renderThumbs();
    return;
  }
  const order = [t.id, ...all.filter((x) => x.kind === 'saved' && x.id !== t.id).map((x) => x.id)];
  try {
    item = await http.put(`/api/items/${item.id}/images/order`, { imageIds: order });
    renderThumbs();
  } catch (err) {
    toast(err.message, 'error');
  }
}

function collect(form) {
  const f = form.elements;
  return {
    title: f.title.value.trim(),
    description: f.description.value.trim(),
    categoryId: f.categoryId.value ? Number(f.categoryId.value) : null,
    condition: f.condition.value || null,
    listingType: $('input[name=listingType]:checked', form).value,
    location: f.location.value.trim(),
  };
}

function validate(form, data) {
  let ok = true;
  if (data.title.length < 3) { fieldError(form, 'title', 'Title must be at least 3 characters'); ok = false; }
  if (data.description.length < 10) { fieldError(form, 'description', 'Description must be at least 10 characters'); ok = false; }
  if (!data.categoryId) { fieldError(form, 'categoryId', 'Choose a category'); ok = false; }
  if (!data.condition) { fieldError(form, 'condition', 'Choose a condition'); ok = false; }
  if (!data.location) { fieldError(form, 'location', 'Location is required'); ok = false; }
  if (!ok) $('.has-error input, .has-error textarea, .has-error select', form).focus();
  return ok;
}

async function submit(form) {
  clearErrors(form);
  const data = collect(form);
  if (!validate(form, data)) return;
  const button = $('#submit-btn');
  try {
    await withLoading(button, async () => {
      if (item) {
        if (data.listingType !== item.listingType && (item.viewer.pendingOffersReceived || item.viewer.pendingDonationRequests)) {
          const ok = await confirmDialog({ title: 'Change listing type?', message: 'Pending offers or requests made under the current type will be closed and their senders notified.', confirmText: 'Change type' });
          if (!ok) return;
        }
        await http.put(`/api/items/${item.id}`, data);
        toast('Listing updated');
        location.href = `/item.html?id=${item.id}`;
        return;
      }
      const created = await http.post('/api/items', data);
      if (queued.length) {
        const fd = new FormData();
        queued.forEach((q) => fd.append('files', q.file));
        try {
          await http.post(`/api/items/${created.id}/images`, fd);
        } catch (err) {
          toast(`Listing published, but photos failed to upload: ${err.message}`, 'error', { duration: 8000 });
          location.href = `/item-edit.html?id=${created.id}`;
          return;
        }
      }
      const returnTo = param('returnTo');
      location.href = returnTo ? safeNext(returnTo) : `/item.html?id=${created.id}&created=1`;
    });
  } catch (err) {
    showErrors(form, err);
  }
}
