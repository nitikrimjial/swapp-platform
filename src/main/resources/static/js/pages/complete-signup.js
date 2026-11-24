// Last step of signing up with Google: the person chose their Google account; now they accept the Terms (18+).
// The pending sign-up lives in an HttpOnly cookie for 15 minutes; no account exists until this form is sent.
import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, icon, $, withLoading, showErrors, clearErrors, fieldError, param, safeNext } from '../ui.js';

await initPage({ auth: 'guest' });

const form = $('#signup-form');

function expired() {
  $('#signup-lead').textContent = 'Your Google sign-up has expired.';
  render('#result', html`<div class="banner warning" role="alert">${icon('alert')}<div><strong>Please start again</strong><br>
    For your security, sign-up must be finished within 15 minutes of choosing your Google account.
    <a href="/register.html">Back to sign up</a></div></div>`);
}

let pending = null;
try {
  pending = await http.get('/api/auth/google/pending'); // null (204) when there is no pending sign-up
} catch {
  pending = null;
}
if (!pending) expired();

if (pending) {
  $('#google-email').textContent = pending.email;
  form.elements.displayName.value = (pending.suggestedName || '').trim().slice(0, 80);
  form.classList.remove('hidden');

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    clearErrors(form);
    const data = {
      displayName: form.elements.displayName.value.trim(),
      location: form.elements.location.value.trim() || null,
      acceptTerms: form.elements.acceptTerms.checked,
    };
    let valid = true;
    if (data.displayName.length < 2) { fieldError(form, 'displayName', 'Name must be at least 2 characters'); valid = false; }
    if (!data.acceptTerms) {
      fieldError(form, 'acceptTerms', 'Please confirm you are 18 or older and accept the Terms and Privacy Policy'); valid = false;
    }
    if (!valid) return;
    try {
      await withLoading(form.querySelector('button[type=submit]'), () => http.post('/api/auth/google/complete', data));
      location.href = param('next') ? safeNext(param('next')) : '/home.html?welcome=1';
    } catch (err) {
      if (err.status === 404) { form.classList.add('hidden'); expired(); return; }
      showErrors(form, err);
    }
  });
}
