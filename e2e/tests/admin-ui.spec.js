const { test, expect } = require('@playwright/test');

const uid = () => Math.random().toString(36).slice(2, 8);

const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'e2e-admin-token';

/** Issues a real API key for the tenant through the admin API (the UI itself never sends a tenant id). */
async function issueKey(request, tenant) {
  const res = await request.post('/api/v1/admin/api-keys', {
    headers: { 'X-Admin-Token': ADMIN_TOKEN },
    data: { tenantId: tenant, name: 'e2e' },
  });
  expect(res.status()).toBe(201);
  return (await res.json()).apiKey;
}

async function useKey(page, key) {
  await page.goto('/');
  await page.getByTestId('api-key').fill(key);
  await page.getByTestId('api-key').dispatchEvent('change');
}

async function useTenant(page, tenant) {
  await useKey(page, await issueKey(page.request, tenant));
  await expect(page.getByTestId('auth-state')).toHaveText('authenticated');
}

test('rejects an invalid API key', async ({ page }) => {
  await useKey(page, 'ntf_definitely_not_valid_key');
  await expect(page.getByTestId('auth-state')).toContainText('valid API key');
  await page.getByTestId('s-recipient').fill('user@example.com');
  await page.getByTestId('s-body').fill('x');
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('401');
});

test('tenants cannot see each other\'s templates in the UI', async ({ page }) => {
  const a = 'e2e-' + uid(), b = 'e2e-' + uid();
  await useTenant(page, a);
  await page.getByTestId('t-name').fill('private-tpl');
  await page.getByTestId('t-body').fill('secret');
  await page.getByTestId('t-save').click();
  await expect(page.locator('#t-rows tr[data-name="private-tpl"]')).toBeVisible();
  await useTenant(page, b);
  await expect(page.locator('#t-rows tr')).toHaveCount(0);
});

test('creates and deletes a template', async ({ page }) => {
  const tenant = 'e2e-' + uid();
  await useTenant(page, tenant);
  await page.getByTestId('t-name').fill('welcome');
  await page.getByTestId('t-subject').fill('Hi {{name}}');
  await page.getByTestId('t-body').fill('Hello {{name}}');
  await page.getByTestId('t-save').click();
  await expect(page.getByTestId('t-msg')).toHaveText('Template created');
  const row = page.locator('#t-rows tr[data-name="welcome"]');
  await expect(row).toBeVisible();

  // duplicate is rejected
  await page.getByTestId('t-save').click();
  await expect(page.getByTestId('t-msg')).toContainText('already exists');

  await row.getByRole('button', { name: 'Delete' }).click();
  await expect(row).toHaveCount(0);
});

test('sends a notification and sees it delivered', async ({ page }) => {
  const tenant = 'e2e-' + uid();
  await useTenant(page, tenant);
  await page.getByTestId('s-recipient').fill('user@example.com');
  await page.getByTestId('s-body').fill('Hello there');
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('Accepted');
  await expect(page.locator('#d-rows [data-testid=status]').first()).toHaveText('SENT');
});

test('sends using a stored template with variables', async ({ page }) => {
  const tenant = 'e2e-' + uid();
  await useTenant(page, tenant);
  await page.getByTestId('t-name').fill('greet');
  await page.getByTestId('t-body').fill('Hello {{name}}');
  await page.getByTestId('t-save').click();
  await expect(page.getByTestId('t-msg')).toHaveText('Template created');

  await page.getByTestId('s-recipient').fill('user@example.com');
  await page.getByTestId('s-template').fill('greet');
  await page.getByTestId('s-vars').fill('{}');
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('Missing template variables');

  await page.getByTestId('s-vars').fill('{"name":"Ann"}');
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('Accepted');
});

test('idempotency key produces a duplicate on replay', async ({ page }) => {
  const tenant = 'e2e-' + uid();
  await useTenant(page, tenant);
  await page.getByTestId('s-recipient').fill('user@example.com');
  await page.getByTestId('s-body').fill('x');
  await page.getByTestId('s-idem').fill('key-' + uid());
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('Accepted');
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('Duplicate of');
});

test('failing recipient ends up DEAD after retries', async ({ page }) => {
  const tenant = 'e2e-' + uid();
  await useTenant(page, tenant);
  await page.getByTestId('s-recipient').fill('fail@example.com');
  await page.getByTestId('s-body').fill('x');
  await page.getByTestId('s-send').click();
  await expect(page.getByTestId('s-msg')).toContainText('Accepted');
  await expect(page.locator('#d-rows [data-testid=status]').first()).toHaveText('DEAD', { timeout: 45_000 });
});

test('rate limit surfaces as 429 in the UI', async ({ page }) => {
  // tenant "e2e-ratelimited" is configured with capacity 3 and a near-zero refill (see scripts/e2e.sh)
  await useTenant(page, 'e2e-ratelimited');
  await page.getByTestId('s-recipient').fill('user@example.com');
  await page.getByTestId('s-body').fill('x');
  // Send until the limiter kicks in; tolerant of a partially consumed bucket (e.g. a retried run).
  let msg = '';
  for (let i = 0; i < 6 && !msg.includes('429'); i++) {
    await page.getByTestId('s-send').click();
    await expect(page.getByTestId('s-msg')).not.toHaveText('');
    await expect(page.getByTestId('s-msg')).toContainText(/Accepted|429/);
    msg = await page.getByTestId('s-msg').textContent();
  }
  await expect(page.getByTestId('s-msg')).toContainText('429');
});
