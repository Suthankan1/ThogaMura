import type { NextConfig } from "next";
import fs from "node:fs";
import path from "node:path";

function getTurbopackRoot(): string {
  try {
    const cwd = process.cwd();
    const nm = path.resolve(cwd, "node_modules");
    if (fs.existsSync(nm)) {
      const realNm = fs.realpathSync(nm);
      const parts1 = path.resolve(cwd).split(path.sep);
      const parts2 = path.resolve(realNm).split(path.sep);
      const common: string[] = [];
      for (let i = 0; i < Math.min(parts1.length, parts2.length); i++) {
        if (parts1[i] === parts2[i]) common.push(parts1[i]);
        else break;
      }
      return common.join(path.sep) || path.sep;
    }
  } catch {
    // Fallback to process.cwd() if resolution fails
  }
  return process.cwd();
}

const nextConfig: NextConfig = {
  reactStrictMode: true,
  turbopack: {
    root: getTurbopackRoot(),
  },
  async rewrites() {
    return [
      {
        source: "/api/v1/:path*",
        destination: `${process.env.GATEWAY_URL || "http://localhost:8080"}/api/v1/:path*`,
      },
    ];
  },
};

export default nextConfig;
