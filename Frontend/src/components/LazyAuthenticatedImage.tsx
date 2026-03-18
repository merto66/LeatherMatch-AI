import { useEffect, useRef, useState } from 'react'
import AuthenticatedImage from './AuthenticatedImage'

interface LazyAuthenticatedImageProps {
  src: string
  alt?: string
  className?: string
  style?: React.CSSProperties
  onError?: () => void
  /**
   * Root margin passed to IntersectionObserver.
   * A positive value starts loading before the element enters the viewport.
   * Default: "200px" so images pre-load slightly before they become visible.
   */
  rootMargin?: string
}

/**
 * A lazy-loading wrapper around AuthenticatedImage.
 *
 * The image is not fetched until the placeholder element enters (or is close
 * to entering) the viewport.  This prevents dozens of simultaneous requests
 * when a long grid of images is first rendered.
 *
 * Once visible the component switches to AuthenticatedImage which handles
 * caching, auth, and blob URL lifecycle.
 */
export default function LazyAuthenticatedImage({
  src,
  alt = '',
  className,
  style,
  onError,
  rootMargin = '200px',
}: LazyAuthenticatedImageProps) {
  const [visible, setVisible] = useState(false)
  const placeholderRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const el = placeholderRef.current
    if (!el) return

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { rootMargin },
    )

    observer.observe(el)
    return () => observer.disconnect()
  }, [rootMargin])

  if (!visible) {
    return (
      <div
        ref={placeholderRef}
        className={className}
        style={{
          ...style,
          background: 'var(--bg-secondary, #f5f5f5)',
        }}
      />
    )
  }

  return (
    <AuthenticatedImage
      src={src}
      alt={alt}
      className={className}
      style={style}
      onError={onError}
    />
  )
}
