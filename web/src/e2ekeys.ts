// The chat keys on this device (IndexedDB "momentum-e2e", kept on logout - the backup on the server
// is only for a new device). The service worker reads it too, to open the text of a notification.

export interface StoredKey {
  /** the private key: usable, never readable again */
  priv: CryptoKey
  pub: string
  /** the private key encrypted with the recovery key, as on the server */
  backup: string
  /** the recovery key, to show it again in the settings */
  recovery: string
  /** the player said they wrote it down */
  noted: boolean
}

let dbp: Promise<IDBDatabase | null> | null = null

function db(): Promise<IDBDatabase | null> {
  if (!dbp) {
    dbp = new Promise((resolve) => {
      try {
        const req = indexedDB.open("momentum-e2e", 1)
        req.onupgradeneeded = () => req.result.createObjectStore("keys")
        req.onsuccess = () => resolve(req.result)
        req.onerror = () => resolve(null)
      } catch {
        resolve(null)
      }
    })
  }
  return dbp
}

async function get<T>(key: string): Promise<T | null> {
  const d = await db()
  if (!d) return null
  return new Promise((resolve) => {
    try {
      const r = d.transaction("keys").objectStore("keys").get(key)
      r.onsuccess = () => resolve((r.result as T | undefined) ?? null)
      r.onerror = () => resolve(null)
    } catch {
      resolve(null)
    }
  })
}

async function put(key: string, value: unknown): Promise<boolean> {
  const d = await db()
  if (!d) return false
  return new Promise((resolve) => {
    try {
      const tx = d.transaction("keys", "readwrite")
      if (value === null) tx.objectStore("keys").delete(key)
      else tx.objectStore("keys").put(value, key)
      tx.oncomplete = () => resolve(true)
      tx.onerror = () => resolve(false)
    } catch {
      resolve(false)
    }
  })
}

export const loadKey = (userId: number) => get<StoredKey>(`key:${userId}`)
export const saveKey = (userId: number, k: StoredKey | null) => put(`key:${userId}`, k)

/** The other players' keys as first seen here: a different one later is shown as a warning. */
export const loadKnown = async (userId: number) => (await get<Record<string, string>>(`known:${userId}`)) ?? {}
export const saveKnown = (userId: number, known: Record<string, string>) => put(`known:${userId}`, known)
