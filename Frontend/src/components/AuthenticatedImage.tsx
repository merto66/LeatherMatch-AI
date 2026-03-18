import { useEffect, useRef, useState } from 'react'
import { acquire } from '../utils/imageCache'

interface AuthenticatedImageProps {
  src: string
  alt?: string
  className?: string
  style?: React.CSSProperties
  onError?: () => void
}

/**
 * Renders an image that lives behind an HTTP Basic Auth endpoint.
 *
 * Key behaviours:
 * - Uses the global imageCache so each URL is fetched at most once across all
 *   mounted instances; subsequent mounts reuse the cached blob URL instantly.
 * - The useEffect dependency list is intentionally [src] only.  The onError
 *   callback is read via a ref so it never triggers a re-fetch.
 * - When the component unmounts (or src changes) it releases its cache slot.
 *   The blob URL is only revoked when the last consumer releases it.
 * - No retry loop; a single failed fetch sets permanent error state.
 */
export default function AuthenticatedImage({
  src,
  alt = '',
  className,
  style,
  onError,
}: AuthenticatedImageProps) {
  const [blobUrl, setBlobUrl] = useState<string>('')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(false)

  // Keep a stable ref to onError so it never appears in the dependency list.
  const onErrorRef = useRef(onError)
  useEffect(() => { onErrorRef.current = onError })

  useEffect(() => {
    if (!src) return

    let cancelled = false
    setLoading(true)
    setError(false)
    setBlobUrl('')

    const cleanup = acquire(src, (url) => {
      if (cancelled) return
      if (url) {
        setBlobUrl(url)
        setLoading(false)
      } else {
        setError(true)
        setLoading(false)
        onErrorRef.current?.()
      }
    })

    return () => {
      cancelled = true
      cleanup()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [src])

  if (loading) {
    return (
      <div
        className={className}
        style={{
          ...style,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--bg-secondary, #f5f5f5)',
          // When used inside a position:relative container the parent passes
          // position:'absolute' + inset:0 via style; we must not override that.
        }}
      >
        <span className="spinner" style={{ width: 20, height: 20, flexShrink: 0 }} />
      </div>
    )
  }

  if (error) {
    return (
      <div
        className={className}
        style={{
          ...style,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--bg-secondary, #f5f5f5)',
          color: 'var(--text-muted, #999)',
          fontSize: 12,
        }}
      >
        ✕
      </div>
    )
  }

  return (
    <img
      src={blobUrl}
      alt={alt}
      className={className}
      style={style}
      decoding="async"
      onError={() => {
        setError(true)
        onErrorRef.current?.()
      }}
    />
  )
}
