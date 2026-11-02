// Sign in with Google (server/google.py): the login button goes to /api/auth/google/start, Google, and back to
// /app/#token=… (or #google=cancel / #google=error). A redirect, not a popup: popups break in the iOS home-screen app.

export type GoogleReturn = { token: string } | { failed: boolean } | null

/** What the URL fragment brings back from the Google sign-in (null: nothing). */
export function googleReturn(hash: string): GoogleReturn {
  const p = new URLSearchParams(hash.replace(/^#/, ""))
  const token = p.get("token")
  if (token) return { token }
  const g = p.get("google")
  return g ? { failed: g !== "cancel" } : null
}

export const googleStartUrl = (lang: string) => `/api/auth/google/start?lang=${lang === "de" ? "de" : "en"}`
