// Reporting and blocking, shared by the item, profile and messages pages.
import { http } from './api.js';
import { html, openDialog, confirmDialog, toast, fieldError } from './ui.js';

const REASONS = {
  ITEM: [['SCAM', 'Scam or fraud'], ['PROHIBITED_ITEM', 'Prohibited or illegal item'], ['FAKE_LISTING', 'Fake or misleading listing'],
    ['INAPPROPRIATE', 'Inappropriate content'], ['SPAM', 'Spam'], ['OTHER', 'Something else']],
  USER: [['SCAM', 'Scam or fraud'], ['HARASSMENT', 'Harassment or abuse'], ['FAKE_LISTING', 'Fake profile or impersonation'],
    ['INAPPROPRIATE', 'Inappropriate content'], ['SPAM', 'Spam'], ['OTHER', 'Something else']],
  MESSAGE: [['SCAM', 'Scam, or asking for money'], ['HARASSMENT', 'Harassment or abuse'], ['INAPPROPRIATE', 'Inappropriate content'],
    ['SPAM', 'Spam'], ['OTHER', 'Something else']],
};
const TITLES = { ITEM: 'Report this listing', USER: 'Report this member', MESSAGE: 'Report this message' };

/** Opens the report dialog. Resolves true when a report was filed. */
export async function reportDialog(targetType, targetId) {
  const result = await openDialog({
    title: TITLES[targetType],
    submitText: 'Send report',
    body: html`<p class="muted">Reports are confidential: the member is not told who reported them. Our moderators review every report.</p>
      <div class="field"><label for="report-reason">What's wrong?</label>
        <select class="select" id="report-reason" name="reason" required>
          <option value="">Choose a reason</option>
          ${REASONS[targetType].map(([value, label]) => html`<option value="${value}">${label}</option>`)}
        </select></div>
      <div class="field"><label for="report-details">Details <span class="muted">(optional)</span></label>
        <textarea class="textarea" id="report-details" name="details" maxlength="1000" rows="3" placeholder="Anything that helps us understand what happened"></textarea></div>
      <p class="small muted">In danger? Contact local emergency services first. Never send money or share bank details to secure an exchange.</p>`,
    onSubmit: async (dialog) => {
      const form = dialog.querySelector('form');
      const reason = form.elements.reason.value;
      if (!reason) { fieldError(form, 'reason', 'Choose a reason'); return false; }
      const details = form.elements.details.value.trim();
      await http.post('/api/reports', { targetType, targetId, reason, details: details || null });
      return true;
    },
  });
  if (result) toast('Thanks for your report. Our moderators will review it.', 'success', { title: 'Report sent' });
  return Boolean(result);
}

/** Blocks or unblocks a member after confirming. Resolves with the new blocked state, or null if cancelled. */
export async function toggleBlock(member, currentlyBlocked) {
  if (currentlyBlocked) {
    await http.del(`/api/users/${member.id}/block`);
    toast(`${member.displayName} is unblocked.`);
    return false;
  }
  const ok = await confirmDialog({
    title: `Block ${member.displayName}?`,
    message: 'You won\'t be able to message each other, and neither of you can make offers on or request the other\'s listings. They won\'t be notified. You can unblock them at any time.',
    confirmText: 'Block',
    danger: true,
    iconName: 'shield',
  });
  if (!ok) return null;
  await http.post(`/api/users/${member.id}/block`);
  toast(`${member.displayName} is blocked.`);
  return true;
}
