import { defineConfig } from "vite";

export default defineConfig({
  base: "/",
  build: { assetsDir: "player-assets", target: "es2022", sourcemap: false },
  server: {
    host: "127.0.0.1",
    proxy: {
      "^/s/[^/]+/(manifest|assets/)": "http://127.0.0.1:4173",
    },
  },
});
