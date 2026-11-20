// Location helpers: browser geolocation, OpenStreetMap Nominatim place search, and a reusable picker widget.
// Nominatim usage policy: explicit user-triggered searches only (no search-as-you-type), with attribution.
import { html, render, icon, $, $$, toast } from './ui.js';

const NOMINATIM = 'https://nominatim.openstreetmap.org';
const ORIGIN_KEY = 'swapp.origin';

export const round3 = (n) => Math.round(n * 1000) / 1000;

/** Resolves {lat, lng} from the device, with friendly error messages. */
export function currentPosition() {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) {
      reject(new Error('Your browser does not support location. Search for a place instead.'));
      return;
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve({ lat: round3(pos.coords.latitude), lng: round3(pos.coords.longitude) }),
      (err) => reject(new Error(err.code === err.PERMISSION_DENIED
        ? 'Location permission was denied. Allow it in your browser, or search for a place instead.'
        : 'Could not determine your location. Try searching for a place instead.')),
      { enableHighAccuracy: false, timeout: 10000, maximumAge: 300000 },
    );
  });
}

function shortName(address = {}, fallback = '') {
  const area = address.neighbourhood || address.suburb || address.quarter || address.city_district || address.village || address.town;
  const city = address.city || address.town || address.county || address.state_district || address.state;
  const parts = [area, city].filter(Boolean);
  const unique = parts.filter((p, i) => parts.indexOf(p) === i);
  return unique.length ? unique.join(', ') : fallback.split(',').slice(0, 2).join(',').trim();
}

export async function searchPlaces(query) {
  const url = `${NOMINATIM}/search?format=jsonv2&addressdetails=1&limit=6&accept-language=en&q=${encodeURIComponent(query)}`;
  const res = await fetch(url, { headers: { Accept: 'application/json' } });
  if (!res.ok) throw new Error('Place search is unavailable right now. Please try again shortly.');
  const data = await res.json();
  return data.map((p) => ({
    lat: round3(Number(p.lat)), lng: round3(Number(p.lon)),
    label: p.display_name, short: shortName(p.address, p.display_name),
  }));
}

export async function reverseGeocode(lat, lng) {
  try {
    const res = await fetch(`${NOMINATIM}/reverse?format=jsonv2&zoom=14&accept-language=en&lat=${lat}&lon=${lng}`,
      { headers: { Accept: 'application/json' } });
    if (!res.ok) return null;
    const data = await res.json();
    return shortName(data.address, data.display_name || '');
  } catch {
    return null;
  }
}

/** Last origin used for browsing (a per-device convenience only). */
export function savedOrigin() {
  try {
    const v = JSON.parse(localStorage.getItem(ORIGIN_KEY) || 'null');
    return v && typeof v.lat === 'number' && typeof v.lng === 'number' ? v : null;
  } catch { return null; }
}
export function saveOrigin(origin) {
  try {
    if (origin) localStorage.setItem(ORIGIN_KEY, JSON.stringify(origin));
    else localStorage.removeItem(ORIGIN_KEY);
  } catch { /* storage unavailable */ }
}

/**
 * Renders a location picker into `container`.
 * value: {lat, lng, name} | null. onChange(value) is called after every change.
 * Returns { get(), set(value) }.
 */
export function locationPicker(container, { value = null, onChange = () => {}, compact = false, emptyText = 'No location set' } = {}) {
  let current = value;
  const id = 'lp' + Math.random().toString(36).slice(2, 8);

  function draw() {
    render(container, html`<div class="loc-picker ${compact ? 'compact' : ''}">
      <div class="loc-status ${current ? 'set' : ''}">
        ${icon(current ? 'pin' : 'pin')}
        <span class="ellipsis">${current ? (current.name || `${current.lat}, ${current.lng}`) : emptyText}</span>
        ${current ? html`<button type="button" class="btn btn-ghost btn-icon btn-sm" data-clear aria-label="Clear location">${icon('x', 'icon-sm')}</button>` : ''}
      </div>
      <div class="loc-actions">
        <button type="button" class="btn btn-secondary btn-sm" data-gps>${icon('pin', 'icon-sm')}Use my location</button>
        <button type="button" class="btn btn-ghost btn-sm" data-toggle-search>${icon('search', 'icon-sm')}Search a place</button>
      </div>
      <div class="loc-search hidden" data-search>
        <div class="row"><input class="input" id="${id}-q" type="search" placeholder="e.g. Koramangala, Bengaluru" aria-label="Search a place" maxlength="120">
          <button type="button" class="btn btn-primary btn-sm" data-search-go>Search</button></div>
        <div class="loc-results" data-results></div>
        <p class="small muted" style="margin:6px 0 0">Search by <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a></p>
      </div>
    </div>`);

    const set = (v) => { current = v; draw(); onChange(current); };
    const clear = $('[data-clear]', container);
    if (clear) clear.addEventListener('click', () => set(null));

    $('[data-gps]', container).addEventListener('click', async (e) => {
      const btn = e.currentTarget;
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner"></span>Locating…';
      try {
        const pos = await currentPosition();
        const name = (await reverseGeocode(pos.lat, pos.lng)) || 'My location';
        set({ ...pos, name });
      } catch (err) {
        toast(err.message, 'error');
        draw();
      }
    });

    const searchBox = $('[data-search]', container);
    $('[data-toggle-search]', container).addEventListener('click', () => {
      searchBox.classList.toggle('hidden');
      if (!searchBox.classList.contains('hidden')) $(`#${id}-q`, container).focus();
    });
    const go = async () => {
      const q = $(`#${id}-q`, container).value.trim();
      const results = $('[data-results]', container);
      if (q.length < 2) return;
      render(results, html`<div class="loading-block" style="padding:12px 0"><span class="spinner"></span></div>`);
      try {
        const places = await searchPlaces(q);
        if (!places.length) { render(results, html`<p class="small muted">No places found. Try adding the city name.</p>`); return; }
        render(results, places.map((p, i) => html`<button type="button" class="loc-result" data-i="${i}">${icon('pin', 'icon-sm')}<span><strong>${p.short}</strong><span class="small muted ellipsis">${p.label}</span></span></button>`));
        $$('[data-i]', results).forEach((b) => b.addEventListener('click', () => {
          const p = places[Number(b.dataset.i)];
          set({ lat: p.lat, lng: p.lng, name: p.short });
        }));
      } catch (err) {
        render(results, html`<p class="small" style="color:var(--danger)">${err.message}</p>`);
      }
    };
    $('[data-search-go]', container).addEventListener('click', go);
    $(`#${id}-q`, container).addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); go(); } });
  }

  draw();
  return { get: () => current, set: (v) => { current = v; draw(); } };
}
