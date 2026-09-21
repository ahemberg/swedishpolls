import { defineConfig } from "vitest/config";

/**
 * Spring serves the compiled assets and writes the script tag itself, so the build emits a
 * manifest instead of an HTML entry: the hashed file names reach the server through
 * `.vite/manifest.json`, and a page without JavaScript stays a complete page.
 */
export default defineConfig({
  base: "/",
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.tsx"],
    setupFiles: ["./src/test-setup.ts"],
  },
  build: {
    outDir: "../target/frontend",
    emptyOutDir: true,
    manifest: true,
    rollupOptions: {
      input: "src/main.tsx",
    },
  },
});
