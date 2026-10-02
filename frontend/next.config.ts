import type { NextConfig } from "next";
const config: NextConfig = {
  ...(process.env.TV_DESKTOP_BUILD === "true" ? { output: "export" as const } : {
    async rewrites() {
      return [{ source: "/api/:path*", destination: "http://127.0.0.1:8000/api/:path*" }];
    },
  }),
  webpack(config, { dev }) {
    // Production cache restoration crashes in Webpack's WasmHash on this
    // Windows/Node runtime. A clean compilation keeps local rebuilds reliable.
    if (!dev) config.cache = false;
    return config;
  },
};
export default config;
