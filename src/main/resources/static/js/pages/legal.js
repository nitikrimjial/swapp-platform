// Privacy Policy, Terms and Safety pages: fills in the deployment's contact address and terms version.
import { initPage } from '../app.js';
import { http } from '../api.js';
import { html, render, $$ } from '../ui.js';

await initPage({ auth: 'optional' });

try {
  const site = await http.get('/api/site');
  $$('[data-contact-email]').forEach((el) => render(el, html`<a href="mailto:${site.contactEmail}">${site.contactEmail}</a>`));
  $$('[data-terms-version]').forEach((el) => { el.textContent = site.termsVersion; });
} catch {
  $$('[data-contact-email]').forEach((el) => { el.textContent = 'our support email address'; });
}
