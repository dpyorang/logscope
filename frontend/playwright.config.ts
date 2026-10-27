import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./tests",
  testIgnore: "**/persisted.spec.ts",
  timeout: 30_000,
  globalTimeout: 300_000,
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:3100",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "node tests/upstream.mjs",
      url: "http://127.0.0.1:3102/control",
      reuseExistingServer: false,
    },
    {
      command: "npm run start",
      url: "http://127.0.0.1:3100/health/live",
      env: {
        HOSTNAME: "127.0.0.1",
        PORT: "3100",
        LOGSCOPE_API_BASE_URL: "http://127.0.0.1:3102",
      },
      reuseExistingServer: false,
      timeout: 60_000,
    },
  ],
});
