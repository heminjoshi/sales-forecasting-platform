import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Vite config for the production SPA. `dist/` is the build output Vercel publishes.
// The API base is injected at build time via VITE_API_BASE (see .env.example / vercel.json),
// so the bundle is environment-agnostic — no API host is hardcoded.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "dist",
    sourcemap: true,
  },
  server: {
    port: 5173,
  },
});
