import { initPage, setTheme } from '../app.js';
import { http, api } from '../api.js';
import { html, render, icon, $, $$, avatar, formatDate, toast, withLoading, showErrors, clearErrors, fieldError, confirmDialog, openDialog } from '../ui.js';
import { locationPicker } from '../location.js';
import { prepareImage } from '../images.js';

let { user } = await initPage({ auth: 'required' });

render('#settings-nav', [
  ['#profile', 'user', 'Profile'], ['#security', 'lock', 'Password'], ['#sessions', 'shield', 'Sessions'],
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

// Account deletion: re-enter the password and type DELETE, so it can't happen by accident.
$('#delete-account').addEventListener('click', async () => {
  await openDialog({
    title: 'Delete your account?',
    submitText: 'Delete my account',
    danger: true,
    body: html`<div class="modal-icon danger">${icon('alert')}</div>
      <p>Your profile, listings, photos and notifications will be erased and you'll be signed out everywhere. This can't be undone.</p>
      <p class="small muted">Want a copy first? <a href="/api/users/me/export" download>Download your data</a>.</p>
      <div class="field"><label for="delete-password">Your password</label>
        <input class="input" type="password" id="delete-password" name="password" autocomplete="current-password" required maxlength="72"></div>
      <div class="field"><label for="delete-confirm">Type DELETE to confirm</label>
        <input class="input" id="delete-confirm" name="confirm" autocomplete="off" required maxlength="10"></div>`,
    onSubmit: async (dialog) => {
      const form = dialog.querySelector('form');
      const password = form.elements.password.value;
      let ok = true;
      if (!password) { fieldError(form, 'password', 'Enter your password'); ok = false; }
      if (form.elements.confirm.value.trim() !== 'DELETE') { fieldError(form, 'confirm', 'Type DELETE in capital letters'); ok = false; }
      if (!ok) return false;
      await api('/api/users/me', { method: 'DELETE', body: { password } });
      location.replace('/?deleted=1');
      return true;
    },
  });
});
