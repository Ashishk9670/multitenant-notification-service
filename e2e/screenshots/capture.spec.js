const { test, expect } = require('@playwright/test');
const path = require('path');

const ADMIN_TOKEN = process.env.ADMIN_TOKEN || 'dev-admin-token';
const OUT = path.join(__dirname, '..', '..', 'docs', 'img');

test('capture admin UI screenshots', async ({ page, request }) => {
  const tenant = 'demo-' + Math.random().toString(36).slice(2, 6);
  const res = await request.post('/api/v1/admin/api-keys', {
    headers: { 'X-Admin-Token': ADMIN_TOKEN },
    data: { tenantId: tenant, name: 'screenshots' },
  });
  expect(res.status()).toBe(201);
  const key = (await res.json()).apiKey;
  const h = { 'X-API-Key': key };

  await request.post('/api/v1/templates', { headers: h, data: { name: 'welcome', channel: 'SMS', body: 'Hi {{name}}, thanks for joining {{product|us}}!' } });
  await request.post('/api/v1/templates', { headers: h, data: { name: 'otp', channel: 'SMS', body: 'Your code is {{code}}. It expires in 5 minutes.' } });
  const sends = [
    { channels: ['SMS'], priority: 'HIGH', recipients: { phone: '+15557654321' }, templateName: 'welcome', variables: { name: 'Ann' } },
    { channels: ['SMS'], recipients: { phone: '+15551234567' }, templateName: 'otp', variables: { code: '482913' } },
    { channels: ['PUSH'], priority: 'LOW', recipients: { deviceToken: 'device-token-123' }, body: 'Your order has shipped' },
  ];
  for (const s of sends) {
    const r = await request.post('/api/v1/notifications', { headers: h, data: s });
    expect(r.status()).toBe(202);
  }

  await page.goto('/');
  await page.getByTestId('api-key').fill(key);
  await page.getByTestId('api-key').dispatchEvent('change');
  await page.reload(); // key is kept in sessionStorage; reload clears the transient 401 from the first, key-less load
  await expect(page.getByTestId('auth-state')).toHaveText('authenticated');
  await expect(page.locator('#t-rows tr[data-name="welcome"]')).toBeVisible();
  await page.screenshot({ path: path.join(OUT, 'admin-ui-templates.png') });

  // wait until at least the first two deliveries are SENT
  await page.getByTestId('d-refresh').click();
  await expect(async () => {
    await page.getByTestId('d-refresh').click();
    await expect(page.locator('#d-rows tr')).not.toHaveCount(0);
    expect(await page.locator('#d-rows').innerText()).toContain('SENT');
  }).toPass({ timeout: 40_000 });
  await page.locator('#deliveries-section').scrollIntoViewIfNeeded();
  await page.locator('#deliveries-section').screenshot({ path: path.join(OUT, 'admin-ui-deliveries.png') });

  // a send in progress (shows the form filled in)
  await page.getByTestId('s-channel').selectOption('SMS');
  await page.getByTestId('s-recipient').fill('+15550001111');
  await page.getByTestId('s-template').fill('welcome');
  await page.getByTestId('s-vars').fill('{"name":"Sam"}');
  await page.locator('#send-section').scrollIntoViewIfNeeded();
  await page.locator('#send-section').screenshot({ path: path.join(OUT, 'admin-ui-send.png') });

  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: path.join(OUT, 'admin-ui-overview.png'), fullPage: true });
});
