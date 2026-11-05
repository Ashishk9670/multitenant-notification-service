// @ts-check
const { defineConfig } = require('@playwright/test');

// Captures README screenshots of the admin UI into ../docs/img. Needs a running stack (see `make screenshots`).
module.exports = defineConfig({
  testDir: './screenshots',
  timeout: 90_000,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost:8080',
    viewport: { width: 1280, height: 900 },
    colorScheme: 'light',
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
});
