// Forgot password, reset password and email verification pages.
// Tokens arrive in the URL fragment (#token=...), which browsers never send to servers or in Referer headers.
// The fragment is removed from the address bar as soon as it has been read.
import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, withLoading, showErrors, clearErrors, fieldError } from '../ui.js';

const mode = document.body.dataset.mode; // "forgot" | "reset" | "verify"

function takeToken() {
  const token = new URLSearchParams(location.hash.slice(1)).get('token') || '';
  if (location.hash) history.replaceState(null, '', location.pathname + location.search);
  return token;
}

function showResult(kind, title, text, action) {
  const iconName = kind === 'success' ? 'checkCircle' : kind === 'danger' ? 'alert' : 'mail';
  render('#result', html`<div class="banner ${kind}">${icon(iconName)}<div><strong>${title}</strong><br>${text}</div></div>
    ${action ? html`<a class="btn btn-primary btn-block" href="${action.href}">${action.label}</a>` : ''}`);
}

if (mode === 'forgot') {
  await initPage({ auth: 'optional' });
  const form = $('#recovery-form');
  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearErrors(form);
    const email = form.elements.email.value.trim();
    if (!/^\S+@\S+\.\S+$/.test(email)) { fieldError(form, 'email', 'Enter a valid email address'); return; }
    try {
      await withLoading(form.querySelector('[type=submit]'), () => http.post('/api/auth/forgot-password', { email }));
      form.remove();
      // Deliberately identical whether or not the address has an account.
      showResult('info', 'Check your inbox',
        html`If an account exists for <strong>${email}</strong>, we've sent a link to reset your password. It expires in 1 hour. Don't forget to check your spam folder.`);
    } catch (err) {
      showErrors(form, err);
    }
  });
}

if (mode === 'reset') {
  const token = takeToken();
  await initPage({ auth: 'optional' });
  const form = $('#recovery-form');
  if (!token) {
    form.remove();
    showResult('danger', 'This link is incomplete', 'Open the link from your email again, or request a new one.',
      { href: '/forgot-password.html', label: 'Request a new link' });
  } else {
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      clearErrors(form);
      const f = form.elements;
      const newPassword = f.newPassword.value;
      let ok = true;
      if (newPassword.length < 8 || !/[A-Za-z]/.test(newPassword) || !/\d/.test(newPassword)) {
        fieldError(form, 'newPassword', 'Use at least 8 characters with a letter and a number'); ok = false;
      }
      if (newPassword !== f.confirmPassword.value) { fieldError(form, 'confirmPassword', 'Passwords do not match'); ok = false; }
      if (!ok) return;
      try {
        await withLoading(form.querySelector('[type=submit]'), () => http.post('/api/auth/reset-password', { token, newPassword }));
        location.replace('/login.html?reset=1');
      } catch (err) {
        if (err.status === 400 && !Object.keys(err.fieldErrors).length) {
          form.remove();
          showResult('danger', 'This link has expired', err.message, { href: '/forgot-password.html', label: 'Request a new link' });
        } else {
          showErrors(form, err);
        }
      }
    });
  }
}

if (mode === 'verify') {
  const token = takeToken();
  const { user } = await initPage({ auth: 'optional' });
  const next = user ? { href: '/home.html', label: 'Continue to Swapp' } : { href: '/login.html', label: 'Sign in' };
  if (!token) {
    showResult('danger', 'This link is incomplete', 'Open the link from your email again. You can send a new one from Settings.', next);
  } else {
    try {
      await http.post('/api/auth/verify-email', { token });
      showResult('success', 'Email confirmed', 'Thanks! Your account is fully set up.', next);
    } catch (err) {
      showResult('danger', 'We couldn\'t confirm your email', err.message,
        user ? { href: '/settings.html#account', label: 'Send a new link' } : next);
    }
  }
}
