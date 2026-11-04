import { defineConfig, devices } from "@playwright/test"

// End-to-end tests of the built PWA (npm run build first) against a throwaway server
// (python -m server.testserver: fresh database, blobs in memory). WebKit = Safari's engine,
// with an iPhone viewport, touch and user agent - the PWA is the iOS client.
const PORT = Number(process.env.E2E_PORT ?? 8765)   // another port for a second run at the same time
// run from the project root
const python = process.platform === "win32" ? [".venv", "Scripts", "python"].join("\\") : ".venv/bin/python"

export default defineConfig({
  testDir: "tests/e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [["list"]],
  outputDir: "test-results",
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "iphone-webkit", use: { ...devices["iPhone 13"], browserName: "webkit" }, testIgnore: /calls\.spec\.ts/ },
    // calls (tests/e2e/calls.spec.ts): two pages really talk - Chromium has a fake camera and microphone
    {
      name: "calls-chromium", testMatch: /calls\.spec\.ts/,
      use: { ...devices["Pixel 7"], browserName: "chromium",
        launchOptions: { args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] } },
    },
  ],
  webServer: {
    command: `${python} -m server.testserver ${PORT}`,
    cwd: "..",
    url: `http://127.0.0.1:${PORT}/api/meta`,
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: "ignore",
  },
})
