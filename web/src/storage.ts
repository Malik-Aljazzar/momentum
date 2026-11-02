// Device storage: small values in localStorage (token, settings, outbox, last state) and cached
// GET responses (chats, feed, profiles) in IndexedDB so they show without internet.

export const local = {
  get<T>(key: string, fallback: T): T {
    try {
      const v = localStorage.getItem(key)
      return v == null ? fallback : (JSON.parse(v) as T)
    } catch {
      return fallback
    }
  },
  set(key: string, value: unknown) {
    try {
      if (value === undefined || value === null) localStorage.removeItem(key)
      else localStorage.setItem(key, JSON.stringify(value))
    } catch { /* full or blocked: the app works without it */ }
  },
}

let dbp: Promise<IDBDatabase | null> | null = null

function db(): Promise<IDBDatabase | null> {
  if (!dbp) {
    dbp = new Promise((resolve) => {
      try {
        const req = indexedDB.open("momentum", 1)
        req.onupgradeneeded = () => req.result.createObjectStore("cache")
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => resolve(null)
      } catch {
        resolve(null)
      }
    })
  }
  return dbp
}

export const cache = {
  async get(key: string): Promise<string | null> {
    const d = await db()
    if (!d) return null
    return new Promise((resolve) => {
      try {
        const r = d.transaction("cache").objectStore("cache").get(key)
        r.onsuccess = () => resolve((r.result as string | undefined) ?? null)
        r.onerror = () => resolve(null)
      } catch {
        resolve(null)
      }
    })
  },
  async set(key: string, text: string) {
    const d = await db()
    if (!d) return
    try { d.transaction("cache", "readwrite").objectStore("cache").put(text, key) } catch { /* ignore */ }
  },
  async clear() {
    const d = await db()
    if (!d) return
    try { d.transaction("cache", "readwrite").objectStore("cache").clear() } catch { /* ignore */ }
  },
}
