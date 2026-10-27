import { defineConfig, devices } from "@playwright/test";
if (!process.env.LOGSCOPE_TEST_UI_URL || !process.env.LOGSCOPE_TEST_API_URL)
  throw new Error("Isolated integration environment is required");
export default defineConfig({
  testDir: "./tests",
  testMatch: "**/persisted.spec.ts",
  timeout: 30_000,
  globalTimeout: 120_000,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  reporter: "list",
  use: {
    ...devices["Desktop Chrome"],
    baseURL: process.env.LOGSCOPE_TEST_UI_URL,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
});
