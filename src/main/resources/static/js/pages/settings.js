import { initPage, setTheme } from '../app.js';
import { http, api } from '../api.js';
import { html, render, icon, $, $$, avatar, formatDate, toast, withLoading, showErrors, clearErrors, fieldError, confirmDialog, openDialog } from '../ui.js';
import { locationPicker } from '../location.js';
import { prepareImage } from '../images.js';

let { user } = await initPage({ auth: 'required' });

render('#settings-nav', [
  ['#profile', 'user', 'Profile'], ['#security', 'lock', 'Sign-in'], ['#sessions', 'shield', 'Sessions'],
  ['#blocked', 'users', 'Blocked members'],
  ['#appearance', 'sun', 'Appearance'], ['#account', 'info', 'Account'], ['#data', 'upload', 'Your data'],
  ['#delete', 'trash', 'Delete account'],
].map(([href, ic, label]) => html`<a href="${href}">${icon(ic, 'icon-sm')}${label}</a>`));
render('#avatar-upload-label', html`${icon('camera', 'icon-sm')}Upload photo`);

function renderUser() {
  render('#avatar-preview', avatar(user, 'xl'));
  $('#avatar-remove').classList.toggle('hidden', !user.avatarUrl);
  render('#account-info', html`<dt>${icon('mail', 'icon-sm')}Email</dt><dd>${user.email}</dd>
    <dt>${icon('shield', 'icon-sm')}Email status</dt><dd>${user.emailVerified
      ? html`<span class="badge badge-success">Confirmed</span>`
      : html`<span class="badge badge-warning">Not confirmed</span> <button type="button" class="btn btn-ghost btn-sm" id="settings-resend">Resend link</button>`}</dd>
    <dt>${icon('calendar', 'icon-sm')}Member since</dt><dd>${formatDate(user.createdAt)}</dd>
    <dt>${icon('user', 'icon-sm')}Public profile</dt><dd><a href="/profile.html?id=${user.id}">View your profile</a></dd>`);
  const resend = $('#settings-resend');
  if (resend) {
    resend.addEventListener('click', async () => {
      try {
        await withLoading(resend, () => http.post('/api/users/me/verification-email'));
        toast(`We sent a new link to ${user.email}.`);
      } catch (err) {
        toast(err.message, 'error');
      }
    });
  }
}

const profileForm = $('#profile-form');
const pf = profileForm.elements;
pf.displayName.value = user.displayName;
pf.location.value = user.location || '';
pf.bio.value = user.bio || '';
const homePicker = locationPicker($('#home-picker'), {
  value: user.latitude !== null && user.latitude !== undefined
    ? { lat: user.latitude, lng: user.longitude, name: user.location || 'Home location' } : null,
  emptyText: 'Not set',
  onChange: (v) => { if (v && v.name && !pf.location.value.trim()) pf.location.value = v.name; },
});
const bioCount = () => { $('#bio-count').textContent = `${pf.bio.value.length} / 500`; };
pf.bio.addEventListener('input', bioCount);
bioCount();
renderUser();

profileForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  clearErrors(profileForm);
  const home = homePicker.get();
  const data = {
    displayName: pf.displayName.value.trim(), location: pf.location.value.trim() || null, bio: pf.bio.value.trim() || null,
    latitude: home ? home.lat : null, longitude: home ? home.lng : null,
  };
  if (data.displayName.length < 2) { fieldError(profileForm, 'displayName', 'Name must be at least 2 characters'); return; }
  try {
    user = await withLoading(profileForm.querySelector('[type=submit]'), () => http.put('/api/users/me', data));
    renderUser();
    toast('Profile saved');
  } catch (err) {
    showErrors(profileForm, err);
  }
});

$('#avatar-input').addEventListener('change', async (e) => {
  const file = e.target.files[0];
  e.target.value = '';
  if (!file) return;
  let prepared;
  try {
    prepared = await prepareImage(file, 512); // avatars are small; resize in the browser
  } catch (err) {
    toast(err.message, 'error');
    return;
  }
  const fd = new FormData();
  fd.append('file', prepared);
  try {
    user = await http.post('/api/users/me/avatar', fd);
    renderUser();
    toast('Profile photo updated');
  } catch (err) {
    toast(err.message, 'error');
  }
});

$('#avatar-remove').addEventListener('click', async () => {
  if (!(await confirmDialog({ title: 'Remove profile photo?', message: 'Your initials will be shown instead.', confirmText: 'Remove', danger: true }))) return;
  try {
    user = await http.del('/api/users/me/avatar');
    renderUser();
    toast('Profile photo removed');
  } catch (err) {
    toast(err.message, 'error');
  }
});

// ---------------------------------------------------------------- sign-in methods (Google)

const GOOGLE_G = html`<svg class="google-g" viewBox="0 0 48 48" aria-hidden="true" focusable="false"><path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"/><path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"/><path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"/><path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"/></svg>`;

const CONNECT_RESULTS = {
  connected: ['success', 'Google connected. You can now sign in with it.'],
  in_use: ['error', 'That Google account is already connected to a different Swapp account.'],
  already_connected: ['error', 'Your account is already connected to a different Google account. Disconnect it first.'],
  unverified: ['error', 'That Google account’s email isn’t verified, so it can’t be connected.'],
};
const connectResult = CONNECT_RESULTS[new URLSearchParams(location.search).get('google')];
if (connectResult) {
  toast(connectResult[1], connectResult[0], { duration: 7000 });
  history.replaceState(null, '', location.pathname + location.hash);
}

function renderPasswordArea() {
  $('#password-form').classList.toggle('hidden', !user.hasPassword);
  $('#set-password').classList.toggle('hidden', user.hasPassword);
}
renderPasswordArea();

$('#send-set-password').addEventListener('click', async (e) => {
  try {
    await withLoading(e.target.closest('button'), () => http.post('/api/auth/forgot-password', { email: user.email }));
    toast(`We sent a link to ${user.email}. Open it to choose your password.`, 'success', { duration: 7000 });
  } catch (err) {
    toast(err.message, 'error');
  }
});

async function loadSignInMethods() {
  const block = $('#sign-in-methods-block');
  let site = { googleSignIn: false };
  let identities = [];
  try {
    [site, identities] = await Promise.all([http.get('/api/site'), http.get('/api/users/me/identities')]);
  } catch {
    return; // the rest of Settings still works
  }
  const google = identities.find((i) => i.provider === 'GOOGLE');
  if (!google && !site.googleSignIn) { block.classList.add('hidden'); return; }
  block.classList.remove('hidden');
  const box = $('#sign-in-methods');
  if (google) {
    render(box, html`<div class="identity-row">
      <span class="who">${GOOGLE_G}<span><strong>Google</strong><br><span class="small muted">${google.email}</span></span></span>
      ${user.hasPassword
        ? html`<button type="button" class="btn btn-secondary btn-sm" id="disconnect-google">Disconnect</button>`
        : html`<span class="small muted">Set a password before disconnecting</span>`}
    </div>`);
    const disconnect = $('#disconnect-google');
    if (disconnect) {
      disconnect.addEventListener('click', async () => {
        if (!(await confirmDialog({ title: 'Disconnect Google?', message: 'You will sign in with your email and password instead.', confirmText: 'Disconnect', danger: true }))) return;
        try {
          await withLoading(disconnect, () => http.del('/api/users/me/identities/google'));
          toast('Google disconnected.');
          loadSignInMethods();
        } catch (err) {
          toast(err.message, 'error');
        }
      });
    }
  } else {
    render(box, html`<div class="identity-row">
      <span class="who">${GOOGLE_G}<span><strong>Google</strong><br><span class="small muted">Not connected</span></span></span>
      <a class="btn btn-google btn-sm" href="/oauth2/authorization/google?intent=connect">Connect Google</a>
    </div>`);
  }
}
loadSignInMethods();

const pwForm = $('#password-form');
pwForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  clearErrors(pwForm);
  const f = pwForm.elements;
  const current = f.currentPassword.value;
  const next = f.newPassword.value;
  let ok = true;
  if (!current) { fieldError(pwForm, 'currentPassword', 'Enter your current password'); ok = false; }
  if (next.length < 8 || !/[A-Za-z]/.test(next) || !/\d/.test(next)) { fieldError(pwForm, 'newPassword', 'Use at least 8 characters with a letter and a number'); ok = false; }
  if (next !== f.confirmPassword.value) { fieldError(pwForm, 'confirmPassword', 'Passwords do not match'); ok = false; }
  if (!ok) return;
  try {
    await withLoading(pwForm.querySelector('[type=submit]'), () => http.post('/api/users/me/password', { currentPassword: current, newPassword: next }));
    pwForm.reset();
    toast('Password updated. Other devices have been signed out.');
  } catch (err) {
    showErrors(pwForm, err);
  }
});

$('#signout-all').addEventListener('click', async (e) => {
  if (!(await confirmDialog({ title: 'Sign out everywhere?', message: 'All sessions, including this one, will end. You will need to sign in again.', confirmText: 'Sign out everywhere', danger: true }))) return;
  try {
    await withLoading(e.target.closest('button'), () => http.post('/api/users/me/sign-out-everywhere'));
    location.href = '/login.html';
  } catch (err) {
    toast(err.message, 'error');
  }
});

function currentTheme() {
  try { return localStorage.getItem('swapp.theme') || 'system'; } catch { return 'system'; }
}
function renderTheme() {
  const t = currentTheme();
  $$('#theme-picker button').forEach((b) => {
    b.classList.toggle('active', b.dataset.theme === t);
    b.setAttribute('aria-checked', String(b.dataset.theme === t));
  });
}
$$('#theme-picker button').forEach((b) => b.addEventListener('click', () => { setTheme(b.dataset.theme); renderTheme(); }));
renderTheme();

async function loadBlocked() {
  const box = $('#blocked-list');
  try {
    const blocked = await http.get('/api/users/me/blocks');
    if (!blocked.length) { render(box, html`<p class="small muted">You haven't blocked anyone.</p>`); return; }
    render(box, html`<ul class="stack" style="list-style:none;padding:0;margin:0">${blocked.map((m) => html`<li class="row" style="justify-content:space-between">
      <span class="row" style="gap:10px">${avatar(m)}<strong>${m.displayName}</strong></span>
      <button type="button" class="btn btn-secondary btn-sm" data-unblock="${m.id}">Unblock</button></li>`)}</ul>`);
    $$('[data-unblock]', box).forEach((b) => b.addEventListener('click', async () => {
      try {
        await withLoading(b, () => http.del(`/api/users/${b.dataset.unblock}/block`));
        toast('Member unblocked.');
        loadBlocked();
      } catch (err) {
        toast(err.message, 'error');
      }
    }));
  } catch (err) {
    render(box, html`<p class="small muted">${err.message}</p>`);
  }
}
loadBlocked();

// Account deletion: re-authenticate and type DELETE, so it can't happen by accident. Members with a password re-enter
// it; Google-only members must have signed in within the last 10 minutes (checked by the server).
$('#delete-account').addEventListener('click', async () => {
  const withPassword = user.hasPassword;
  await openDialog({
    title: 'Delete your account?',
    submitText: 'Delete my account',
    danger: true,
    body: html`<div class="modal-icon danger">${icon('alert')}</div>
      <p>Your profile, listings, photos and notifications will be erased and you'll be signed out everywhere. This can't be undone.</p>
      <p class="small muted">Want a copy first? <a href="/api/users/me/export" download>Download your data</a>.</p>
      ${withPassword
        ? html`<div class="field"><label for="delete-password">Your password</label>
            <input class="input" type="password" id="delete-password" name="password" autocomplete="current-password" required maxlength="72"></div>`
        : html`<p class="small">For your security, you need to have signed in with Google in the last 10 minutes. If it's been longer, sign out, sign in with Google again, then come back here.</p>`}
      <div class="field"><label for="delete-confirm">Type DELETE to confirm</label>
        <input class="input" id="delete-confirm" name="confirm" autocomplete="off" required maxlength="10"></div>`,
    onSubmit: async (dialog) => {
      const form = dialog.querySelector('form');
      const password = withPassword ? form.elements.password.value : null;
      let ok = true;
      if (withPassword && !password) { fieldError(form, 'password', 'Enter your password'); ok = false; }
      if (form.elements.confirm.value.trim() !== 'DELETE') { fieldError(form, 'confirm', 'Type DELETE in capital letters'); ok = false; }
      if (!ok) return false;
      await api('/api/users/me', { method: 'DELETE', body: withPassword ? { password } : {} });
      location.replace('/?deleted=1');
      return true;
    },
  });
});
