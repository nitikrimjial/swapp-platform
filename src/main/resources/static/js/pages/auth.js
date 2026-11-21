import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, withLoading, showErrors, clearErrors, fieldError, param, safeNext, toast } from '../ui.js';

const mode = document.body.dataset.mode; // "login" | "register"
await initPage({ auth: 'guest' });

if (mode === 'login' && param('reset')) {
  toast('Password changed. Sign in with your new password.', 'success', { duration: 8000 });
  history.replaceState(null, '', location.pathname);
}

const next = safeNext(param('next'), mode === 'register' ? '/home.html' : '/home.html');
if (param('next')) {
  const other = mode === 'login' ? '/register.html' : '/login.html';
  $('#switch-link').href = `${other}?next=${encodeURIComponent(next)}`;
}

render('#aside-points', [
  ['swap', 'Exchange items without money'],
  ['heart', 'Donate and give away in a few taps'],
  ['message', 'Chat privately to arrange hand-overs'],
  ['shield', 'Both sides confirm every exchange'],
].map(([ic, text]) => html`<li>${icon(ic)}${text}</li>`));

const pw = $('#password');
const toggle = $('#toggle-password');
render(toggle, icon('eye'));
toggle.addEventListener('click', () => {
  const show = pw.type === 'password';
  pw.type = show ? 'text' : 'password';
  toggle.setAttribute('aria-label', show ? 'Hide password' : 'Show password');
  render(toggle, icon(show ? 'eyeOff' : 'eye'));
});

const form = $('#auth-form');
form.addEventListener('submit', async (e) => {
  e.preventDefault();
  clearErrors(form);
  const data = Object.fromEntries(new FormData(form).entries());
  Object.keys(data).forEach((k) => { if (k !== 'password') data[k] = data[k].trim(); });

  let valid = true;
  if (!/^\S+@\S+\.\S+$/.test(data.email)) { fieldError(form, 'email', 'Enter a valid email address'); valid = false; }
  if (mode === 'register') {
    if (data.displayName.length < 2) { fieldError(form, 'displayName', 'Name must be at least 2 characters'); valid = false; }
    if (data.password.length < 8 || !/[A-Za-z]/.test(data.password) || !/\d/.test(data.password)) {
      fieldError(form, 'password', 'Use at least 8 characters with a letter and a number'); valid = false;
    }
    if (!data.location) delete data.location;
  } else if (!data.password) {
    fieldError(form, 'password', 'Password is required'); valid = false;
  }
  if (!valid) return;

  const button = form.querySelector('button[type=submit]');
  try {
    await withLoading(button, () => http.post(mode === 'register' ? '/api/auth/register' : '/api/auth/login', data));
    location.href = mode === 'register' && !param('next') ? '/home.html?welcome=1' : next;
  } catch (err) {
    showErrors(form, err);
  }
});
