import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, itemCard, skeletonCards, emptyState, errorState, pagination, CONDITIONS, LISTING_TYPES, $, $$ } from '../ui.js';
import { locationPicker, savedOrigin, saveOrigin, round3 } from '../location.js';

const { user } = await initPage({ auth: 'optional', active: new URLSearchParams(location.search).get('near') === 'me' ? 'near' : 'browse' });

$$('[data-icon]').forEach((el) => { el.outerHTML = icon(el.dataset.icon).toString(); });
render('#toggle-filters', html`${icon('sliders')}Filters`);
if (user) $('#f-mine-wrap').classList.remove('hidden');

const form = $('#filter-form');
const results = $('#results');
const params = new URLSearchParams(location.search);

function initialOrigin() {
  const lat = Number(params.get('lat'));
  const lng = Number(params.get('lng'));
  if (params.get('lat') && params.get('lng') && Number.isFinite(lat) && Number.isFinite(lng)) {
    return { lat: round3(lat), lng: round3(lng), name: params.get('place') || 'Selected location' };
  }
  if (params.get('near') === 'me') {
    if (user && user.latitude !== null && user.latitude !== undefined) {
      return { lat: user.latitude, lng: user.longitude, name: user.location || 'My home' };
    }
    return savedOrigin();
  }
  return null;
}

const state = {
  q: params.get('q') || '',
  categoryId: params.get('categoryId') || '',
  conditions: params.getAll('condition'),
  location: params.get('location') || '',
  type: params.getAll('type'),
  posted: params.get('posted') || '',
  photos: params.get('photos') === '1',
  mine: params.get('mine') === '1',
  origin: initialOrigin(),
  radius: params.get('radius') || (params.get('near') === 'me' ? '10' : ''),
  sort: params.get('sort') || (params.get('near') === 'me' ? 'distance' : 'newest'),
  page: Number(params.get('page') || 0),
};

render('#f-types', Object.entries(LISTING_TYPES).map(([key, t]) => html`<label class="checkbox">
  <input type="checkbox" name="type" value="${key}" ${state.type.includes(key) ? 'checked' : ''}>
  <span class="badge badge-${t.cls}">${icon(t.icon)}${t.label}</span></label>`));
render('#f-conditions', Object.entries(CONDITIONS).map(([key, label]) => html`<label class="checkbox">
  <input type="checkbox" name="condition" value="${key}" ${state.conditions.includes(key) ? 'checked' : ''}>${label}</label>`));

const categories = await http.get('/api/categories').catch(() => []);
$('#f-category').insertAdjacentHTML('beforeend', categories.map((c) => html`<option value="${c.id}">${c.name}</option>`.toString()).join(''));

$('#f-q').value = state.q;
$('#f-category').value = state.categoryId;
$('#f-location').value = state.location;
$('#f-posted').value = state.posted;
$('#f-photos').checked = state.photos;
$('#f-mine').checked = state.mine;
$('#f-radius').value = state.radius;
$('#f-sort').value = state.sort;

const picker = locationPicker($('#f-origin'), {
  value: state.origin,
  compact: true,
  emptyText: 'Anywhere',
  onChange: (origin) => {
    state.origin = origin;
    saveOrigin(origin);
    if (origin && !state.radius) { state.radius = '10'; $('#f-radius').value = '10'; }
    if (origin && state.sort === 'newest') { state.sort = 'distance'; $('#f-sort').value = 'distance'; }
    if (!origin) { state.radius = ''; $('#f-radius').value = ''; if (state.sort === 'distance') { state.sort = 'newest'; $('#f-sort').value = 'newest'; } }
    state.page = 0;
    load();
  },
});

function syncControls() {
  const hasOrigin = !!state.origin;
  $('#f-radius').disabled = !hasOrigin;
  $('#f-sort option[value=distance]').disabled = !hasOrigin;
}

let controller = null;
async function load() {
  syncControls();
  syncUrl();
  renderActiveFilters();
  if (controller) controller.abort();
  controller = new AbortController();
  results.className = 'item-grid';
  render(results, skeletonCards(8));
  render('#pager', '');
  try {
    const page = await http.get('/api/items', {
      q: state.q, categoryId: state.categoryId, condition: state.conditions, location: state.location,
      type: state.type, postedWithinDays: state.posted, withPhotos: state.photos || undefined,
      excludeMine: state.mine || undefined,
      lat: state.origin ? state.origin.lat : undefined, lng: state.origin ? state.origin.lng : undefined,
      radiusKm: state.origin && state.radius ? state.radius : undefined,
      sort: state.sort === 'distance' && !state.origin ? 'newest' : state.sort, page: state.page, size: 12,
    }, controller.signal);
    const n = page.totalElements;
    $('#result-count').textContent = `${n} ${n === 1 ? 'item' : 'items'} found`
      + (state.origin && state.radius ? ` within ${state.radius} km` : '');
    if (!page.content.length) {
      results.className = '';
      const nearby = state.origin && state.radius;
      render(results, emptyState({
        iconName: nearby ? 'pin' : 'search',
        title: nearby ? `Nothing within ${state.radius} km yet` : 'No items match your filters',
        text: nearby ? 'Try a larger distance, or be the first to list something in your area.' : 'Try a different search term or clear some filters.',
        action: html`${nearby ? html`<button class="btn btn-primary" id="empty-wider">Search within 50 km</button> ` : ''}<button class="btn btn-secondary" id="empty-reset">Clear filters</button>`,
      }));
      $('#empty-reset').addEventListener('click', reset);
      const wider = $('#empty-wider');
      if (wider) wider.addEventListener('click', () => { state.radius = '50'; $('#f-radius').value = '50'; state.page = 0; load(); });
      return;
    }
    render(results, page.content.map((i) => itemCard(i)));
    pagination($('#pager'), page, (p) => { state.page = p; load(); });
  } catch (err) {
    if (err.name === 'AbortError') return;
    results.className = '';
    render(results, errorState(err.message, load));
  }
}

function renderActiveFilters() {
  const chips = []; // [key, label, iconName?]
  if (state.origin) chips.push(['origin', `${state.origin.name}${state.radius ? ` · ${state.radius} km` : ''}`, 'pin']);
  if (state.q) chips.push(['q', `“${state.q}”`, 'search']);
  state.type.forEach((t) => chips.push([`type:${t}`, LISTING_TYPES[t].label, LISTING_TYPES[t].icon]));
  if (state.categoryId) {
    const c = categories.find((x) => String(x.id) === String(state.categoryId));
    if (c) chips.push(['categoryId', c.name, c.icon]);
  }
  state.conditions.forEach((c) => chips.push([`condition:${c}`, CONDITIONS[c], 'star']));
  if (state.posted) chips.push(['posted', { 1: 'Last 24 hours', 7: 'Last 7 days', 30: 'Last 30 days' }[state.posted] || `${state.posted} days`, 'clock']);
  if (state.photos) chips.push(['photos', 'With photos', 'image']);
  if (state.mine) chips.push(['mine', 'Hiding my listings', 'eyeOff']);
  if (state.location) chips.push(['location', `Area: ${state.location}`, 'pin']);
  const box = $('#active-filters');
  box.classList.toggle('hidden', chips.length === 0);
  render(box, chips.map(([key, label, ic]) => html`<button type="button" class="chip" data-remove="${key}" aria-label="Remove filter ${label}">
    ${ic ? icon(ic, 'icon-sm') : ''}${label}${icon('x', 'icon-sm')}</button>`));
  $$('[data-remove]', box).forEach((b) => b.addEventListener('click', () => removeFilter(b.dataset.remove)));
}

function removeFilter(key) {
  const [name, value] = key.split(':');
  if (name === 'origin') { picker.set(null); state.origin = null; state.radius = ''; $('#f-radius').value = ''; if (state.sort === 'distance') { state.sort = 'newest'; $('#f-sort').value = 'newest'; } }
  else if (name === 'type') { state.type = state.type.filter((t) => t !== value); $(`input[name=type][value=${value}]`, form).checked = false; }
  else if (name === 'condition') { state.conditions = state.conditions.filter((c) => c !== value); $(`input[name=condition][value=${value}]`, form).checked = false; }
  else if (name === 'q') { state.q = ''; $('#f-q').value = ''; }
  else if (name === 'categoryId') { state.categoryId = ''; $('#f-category').value = ''; }
  else if (name === 'posted') { state.posted = ''; $('#f-posted').value = ''; }
  else if (name === 'photos') { state.photos = false; $('#f-photos').checked = false; }
  else if (name === 'mine') { state.mine = false; $('#f-mine').checked = false; }
  else if (name === 'location') { state.location = ''; $('#f-location').value = ''; }
  state.page = 0;
  load();
}

function syncUrl() {
  const p = new URLSearchParams();
  ['q', 'categoryId', 'location', 'posted'].forEach((k) => { if (state[k]) p.set(k, state[k]); });
  state.type.forEach((t) => p.append('type', t));
  state.conditions.forEach((c) => p.append('condition', c));
  if (state.photos) p.set('photos', '1');
  if (state.mine) p.set('mine', '1');
  if (state.origin) { p.set('lat', state.origin.lat); p.set('lng', state.origin.lng); p.set('place', state.origin.name || ''); }
  if (state.origin && state.radius) p.set('radius', state.radius);
  if (state.sort !== 'newest') p.set('sort', state.sort);
  if (state.page) p.set('page', state.page);
  const qs = p.toString();
  history.replaceState(null, '', qs ? `?${qs}` : location.pathname);
}

function readForm() {
  state.q = $('#f-q').value.trim();
  state.categoryId = $('#f-category').value;
  state.location = $('#f-location').value.trim();
  state.type = $$('input[name=type]:checked', form).map((i) => i.value);
  state.conditions = $$('input[name=condition]:checked', form).map((i) => i.value);
  state.posted = $('#f-posted').value;
  state.photos = $('#f-photos').checked;
  state.mine = $('#f-mine').checked;
  state.radius = $('#f-radius').value;
  state.page = 0;
}

function reset() {
  form.reset();
  $$('input[type=checkbox]', form).forEach((i) => { i.checked = false; });
  picker.set(null);
  state.origin = null;
  state.sort = 'newest';
  $('#f-sort').value = 'newest';
  readForm();
  load();
}

let debounce;
form.addEventListener('input', (e) => {
  if (e.target.closest('#f-origin')) return; // the picker manages its own inputs
  clearTimeout(debounce);
  const delay = e.target.matches('input[type=search], #f-location') ? 400 : 0;
  debounce = setTimeout(() => { readForm(); load(); }, delay);
});
form.addEventListener('submit', (e) => { e.preventDefault(); readForm(); load(); });
$('#f-sort').addEventListener('change', (e) => { state.sort = e.target.value; state.page = 0; load(); });
$('#reset-filters').addEventListener('click', reset);
$('#toggle-filters').addEventListener('click', () => {
  const open = $('#filters').classList.toggle('open');
  $('#toggle-filters').setAttribute('aria-expanded', String(open));
});

load();
