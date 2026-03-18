/**
 * Global authenticated image cache.
 *
 * Goals:
 * - Fetch each unique URL exactly once per session; subsequent requests get the
 *   cached blob URL immediately.
 * - Track reference counts so blob URLs are only revoked when no component is
 *   still using them.
 * - Cap concurrent in-flight fetches so we never flood the backend.
 * - Support manual invalidation (e.g. after an image is re-uploaded).
 */

const STORAGE_KEY = 'leathermatch_auth'

/**
 * Maximum simultaneous fetch requests to the backend.
 * Higher on desktop (fast LAN), lower on mobile to avoid saturating the
 * connection when a page with many images is first opened.
 */
const MAX_CONCURRENT = /Mobi|Android|iPhone|iPad/i.test(navigator.userAgent) ? 4 : 8

interface CacheEntry {
  /** Resolved blob URL, or null while still loading. */
  blobUrl: string | null
  /** Whether the fetch has completed (success or error). */
  settled: boolean
  /** True when the fetch ended in a permanent error. */
  error: boolean
  /** Callbacks waiting for this entry to settle. */
  listeners: Array<(url: string | null) => void>
  /** Number of mounted components currently using this entry. */
  refCount: number
}

const cache = new Map<string, CacheEntry>()

/** Tracks how many fetches are currently in flight. */
let inFlight = 0

/** Queue of [src, resolve] pairs waiting for a free slot. */
type QueueItem = { src: string; resolve: () => void }
const queue: QueueItem[] = []

function processQueue() {
  while (inFlight < MAX_CONCURRENT && queue.length > 0) {
    const item = queue.shift()!
    item.resolve()
  }
}

async function doFetch(src: string): Promise<void> {
  const entry = cache.get(src)
  if (!entry) return

  // Wait for a free concurrency slot.
  await new Promise<void>((resolve) => {
    if (inFlight < MAX_CONCURRENT) {
      inFlight++
      resolve()
    } else {
      queue.push({ src, resolve: () => { inFlight++; resolve() } })
    }
  })

  try {
    const encoded = sessionStorage.getItem(STORAGE_KEY)
    if (!encoded) throw new Error('No auth credentials')

    const response = await fetch(src, {
      headers: { Authorization: `Basic ${encoded}` },
    })

    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const blob = await response.blob()
    const blobUrl = URL.createObjectURL(blob)

    entry.blobUrl = blobUrl
    entry.settled = true
    entry.error = false
    entry.listeners.forEach((cb) => cb(blobUrl))
  } catch {
    entry.settled = true
    entry.error = true
    entry.blobUrl = null
    entry.listeners.forEach((cb) => cb(null))
  } finally {
    inFlight--
    entry.listeners = []
    processQueue()
  }
}

/**
 * Acquire the blob URL for `src`.
 * Returns the URL immediately if already cached, otherwise fetches it.
 * The caller must call `release(src)` when it no longer needs the URL.
 */
export function acquire(
  src: string,
  onSettled: (url: string | null) => void,
): (() => void) {
  let entry = cache.get(src)

  if (!entry) {
    entry = {
      blobUrl: null,
      settled: false,
      error: false,
      listeners: [],
      refCount: 0,
    }
    cache.set(src, entry)
    // Fire-and-forget; listeners will be notified when done.
    doFetch(src)
  }

  entry.refCount++

  if (entry.settled) {
    // Already done – call back synchronously on next microtask to keep
    // component lifecycle predictable.
    const url = entry.error ? null : entry.blobUrl
    Promise.resolve().then(() => onSettled(url))
  } else {
    entry.listeners.push(onSettled)
  }

  // Return a cleanup function.
  return () => release(src)
}

/**
 * Decrement the ref count for `src`.
 * When it drops to zero the blob URL is revoked and the entry removed.
 */
export function release(src: string) {
  const entry = cache.get(src)
  if (!entry) return

  entry.refCount = Math.max(0, entry.refCount - 1)

  if (entry.refCount === 0) {
    if (entry.blobUrl) {
      URL.revokeObjectURL(entry.blobUrl)
    }
    cache.delete(src)
  }
}

/**
 * Force-remove a specific URL from the cache (e.g. after re-upload).
 * All future `acquire` calls will fetch fresh.
 */
export function invalidate(src: string) {
  const entry = cache.get(src)
  if (!entry) return
  if (entry.blobUrl) URL.revokeObjectURL(entry.blobUrl)
  cache.delete(src)
}

/** Clear the entire cache (e.g. on logout). */
export function clearAll() {
  cache.forEach((entry) => {
    if (entry.blobUrl) URL.revokeObjectURL(entry.blobUrl)
  })
  cache.clear()
}
