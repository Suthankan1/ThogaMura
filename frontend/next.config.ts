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

const workspaceEnabled = process.env.THOGAMURA_ACCESS_ENABLED === "true";

const nextConfig: NextConfig = {
  env: { NEXT_PUBLIC_WORKSPACE_ENABLED: String(workspaceEnabled) },
  async redirects() {
    if (!workspaceEnabled) return [];
    return ["/", "/products", "/requests/:path*", "/warehouse", "/suppliers"].map((source) => ({
      source,
      destination: "/workspace",
      permanent: false,
    }));
  },
  reactStrictMode: true,
  turbopack: {
    root: getTurbopackRoot(),
  },
  async rewrites() {
    const gateway = process.env.GATEWAY_URL || "http://localhost:8080";
    return ["v1", "v2"].map((version) => ({
      source: `/api/${version}/:path*`,
      destination: `${gateway}/api/${version}/:path*`,
    }));
  },
};

export default nextConfig;
