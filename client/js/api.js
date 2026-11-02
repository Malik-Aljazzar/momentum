// Thin wrapper around the Quart JSON API. The auth token lives in localStorage.

const TOKEN_KEY = "momentum.token";

export function getToken() {
  try { return localStorage.getItem(TOKEN_KEY); } catch { return null; }
}

export function setToken(token) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    else localStorage.removeItem(TOKEN_KEY);
  } catch { /* private mode: token only lives for this page load */ }
}

export class ApiError extends Error {
  constructor(message, status) { super(message); this.status = status; }
}

export async function api(method, path, body) {
  const headers = { "Content-Type": "application/json" };
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;
  let res;
  try {
    res = await fetch(`/api${path}`, {
      method, headers, body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("Server nicht erreichbar", 0);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error || `Fehler ${res.status}`, res.status);
  return data;
}
