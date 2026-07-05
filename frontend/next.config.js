/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  // Reverse-proxy every /api/* call (REST + the /api/stream SSE feed) to the Spring
  // backend on the same machine. This lets the whole app run behind ONE origin, so a
  // single tunnel (Cloudflare/ngrok) or LAN URL serves everything — the browser never
  // has to reach port 8090 directly. Backend runs on the same host as `next start`.
  async rewrites() {
    const backend = process.env.OMS_BACKEND_ORIGIN || "http://localhost:8090";
    return [
      { source: "/api/:path*", destination: `${backend}/api/:path*` },
    ];
  },
};
module.exports = nextConfig;
