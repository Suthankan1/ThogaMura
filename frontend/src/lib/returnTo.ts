const localOrigin = "https://thogamura.invalid";
const routes = new Set(["/", "/products", "/requests", "/requests/new", "/suppliers", "/warehouse"]);

export function safeReturnTo(value: string | null) {
  if (!value?.startsWith("/")) return "/products";
  try {
    const target = new URL(value, localOrigin);
    if (target.origin === localOrigin && routes.has(target.pathname)) {
      return target.pathname + target.search;
    }
  } catch {
    return "/products";
  }
  return "/products";
}
