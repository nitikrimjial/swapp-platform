import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, itemCard, skeletonCards, emptyState, errorState, pagination, CONDITIONS, LISTING_TYPES, $, $$ } from '../ui.js';

await initPage({ auth: 'optional', active: 'browse' });

$$('[data-icon]').forEach((el) => { el.outerHTML = icon(el.dataset.icon).toString(); });
render('#toggle-filters', html`${icon('sliders')}Filters`);

const form = $('#filter-form');
const results = $('#results');
const params = new URLSearchParams(location.search);
const state = {
  q: params.get('q') || '',
  categoryId: params.get('categoryId') || '',
  condition: params.get('condition') || '',
  location: params.get('location') || '',
  type: params.getAll('type'),
  sort: params.get('sort') || 'newest',
  page: Number(params.get('page') || 0),
};

render('#f-types', Object.entries(LISTING_TYPES).map(([key, t]) => html`<label class="checkbox">
  <input type="checkbox" name="type" value="${key}" ${state.type.includes(key) ? 'checked' : ''}>
  <span class="badge badge-${t.cls}">${icon(t.icon)}${t.label}</span></label>`));
$('#f-condition').insertAdjacentHTML('beforeend', Object.entries(CONDITIONS)
  .map(([k, v]) => `<option value="${k}">${v}</option>`).join(''));

const categories = await http.get('/api/categories').catch(() => []);
$('#f-category').insertAdjacentHTML('beforeend', categories.map((c) => html`<option value="${c.id}">${c.name}</option>`.toString()).join(''));

$('#f-q').value = state.q;
$('#f-category').value = state.categoryId;
$('#f-condition').value = state.condition;
$('#f-location').value = state.location;
$('#f-sort').value = state.sort;

let controller = null;
async function load() {
  syncUrl();
  if (controller) controller.abort();
  controller = new AbortController();
  render(results, skeletonCards(8));
  results.classList.add('item-grid');
  render('#pager', '');
  try {
    const page = await http.get('/api/items', {
      q: state.q, categoryId: state.categoryId, condition: state.condition, location: state.location,
      type: state.type, sort: state.sort, page: state.page, size: 12,
    }, controller.signal);
    const n = page.totalElements;
    $('#result-count').textContent = `${n} ${n === 1 ? 'item' : 'items'} found`;
    if (!page.content.length) {
      results.classList.remove('item-grid');
      render(results, emptyState({
        iconName: 'search', title: 'No items match your filters',
        text: 'Try a different search term, widen the location, or clear some filters.',
        action: html`<button class="btn btn-secondary" id="empty-reset">Clear filters</button>`,
      }));
      $('#empty-reset').addEventListener('click', reset);
      return;
    }
    render(results, page.content.map((i) => itemCard(i)));
    pagination($('#pager'), page, (p) => { state.page = p; load(); });
  } catch (err) {
    if (err.name === 'AbortError') return;
    results.classList.remove('item-grid');
    render(results, errorState(err.message, load));
  }
}

function syncUrl() {
  const p = new URLSearchParams();
  ['q', 'categoryId', 'condition', 'location'].forEach((k) => { if (state[k]) p.set(k, state[k]); });
  state.type.forEach((t) => p.append('type', t));
  if (state.sort !== 'newest') p.set('sort', state.sort);
  if (state.page) p.set('page', state.page);
  const qs = p.toString();
  history.replaceState(null, '', qs ? `?${qs}` : location.pathname);
}

function readForm() {
  state.q = $('#f-q').value.trim();
  state.categoryId = $('#f-category').value;
  state.condition = $('#f-condition').value;
  state.location = $('#f-location').value.trim();
  state.type = $$('input[name=type]:checked', form).map((i) => i.value);
  state.page = 0;
}

function reset() {
  form.reset();
  $$('input[name=type]', form).forEach((i) => { i.checked = false; });
  readForm();
  load();
}

let debounce;
form.addEventListener('input', (e) => {
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
