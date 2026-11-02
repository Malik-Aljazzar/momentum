/// <reference types="vitest" />
import { defineConfig } from "vite"
import preact from "@preact/preset-vite"
import { serviceWorker } from "./scripts/sw-plugin"

// The PWA lives under /app/ on the Momentum server (server/app.py serves web/dist there);
// in development the API, sprites and media come from the local server (python -m server).
const backend = process.env.MOMENTUM_BACKEND ?? "http://localhost:8000"
const build = new Date().toISOString().slice(2, 16).replace(/[-:]/g, "").replace("T", ".")

export default defineConfig({
  base: "/app/",
  plugins: [preact(), serviceWorker(build)],
  define: {
    // build version: shown in the settings, the service worker cache is named after it
    __BUILD__: JSON.stringify(build),
  },
  server: {
    port: 5173,
    fs: { allow: [".."] },      // src/changelog.ts imports the project's CHANGELOG.md
    proxy: Object.fromEntries(["/api", "/media", "/avatars"].map((p) => [p, { target: backend, changeOrigin: true }])),
  },
  build: { outDir: "dist", emptyOutDir: true, sourcemap: true },
  test: {
    environment: "jsdom",
    include: ["tests/unit/**/*.test.ts", "tests/unit/**/*.test.tsx"],
  },
})
