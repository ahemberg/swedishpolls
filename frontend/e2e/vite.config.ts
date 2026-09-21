import { defineConfig } from "vite";

export default defineConfig({
  root: "e2e/site",
  resolve: {
    alias: {
      "/@app": new URL("../src/main.tsx", import.meta.url).pathname,
      "/@fixtures": new URL("../src/test-fixtures.ts", import.meta.url).pathname,
    },
  },
  server: {
    fs: {
      allow: ["../.."],
    },
  },
});
