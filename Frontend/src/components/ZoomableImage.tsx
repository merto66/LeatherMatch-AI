import { useEffect, useRef, useState } from 'react'
import AuthenticatedImage from './AuthenticatedImage'

function distance(t1: Touch, t2: Touch) {
  return Math.hypot(t2.clientX - t1.clientX, t2.clientY - t1.clientY)
}

interface ZoomableImageProps {
  src: string
  alt: string
  caption?: string
  thumbnailClassName?: string
  thumbnailStyle?: React.CSSProperties
  requiresAuth?: boolean
}

export default function ZoomableImage({
  src,
  alt,
  caption,
  thumbnailClassName,
  thumbnailStyle,
  requiresAuth = false,
}: ZoomableImageProps) {
  const [modalOpen, setModalOpen] = useState(false)
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const pinchRef = useRef<{ initialDistance: number; initialZoom: number } | null>(null)
  const dragRef = useRef<{ startX: number; startY: number; startPan: { x: number; y: number } } | null>(null)
  const imageWrapRef = useRef<HTMLDivElement>(null)
  const setZoomRef = useRef(setZoom)
  setZoomRef.current = setZoom
  // Track last tap time for double-tap detection on iOS (where dblclick is unreliable)
  const lastTapRef = useRef<number>(0)

  const toggleZoom = () => {
    setZoom((z) => {
      const next = z >= 1.5 ? 1 : 2
      if (next === 1) setPan({ x: 0, y: 0 })
      return next
    })
  }

  const handleDoubleClick = (e: React.MouseEvent) => {
    e.stopPropagation()
    toggleZoom()
  }

  useEffect(() => {
    if (!modalOpen) return
    setZoom(1)
    setPan({ x: 0, y: 0 })
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setModalOpen(false)
    }
    document.addEventListener('keydown', onEscape)

    // Prevent body scroll while modal is open without causing a layout shift.
    // On desktop, hiding overflow removes the scrollbar (≈15 px), which forces
    // a reflow of every element on the page – causing the visible "delay".
    // We compensate by reserving that space with padding-right before hiding.
    const scrollbarWidth = window.innerWidth - document.documentElement.clientWidth
    if (scrollbarWidth > 0) {
      document.body.style.paddingRight = `${scrollbarWidth}px`
    }
    document.body.style.overflow = 'hidden'

    return () => {
      document.removeEventListener('keydown', onEscape)
      document.body.style.overflow = ''
      document.body.style.paddingRight = ''
    }
  }, [modalOpen])

  const zoomRef = useRef(zoom)
  const panRef  = useRef(pan)
  useEffect(() => { zoomRef.current = zoom }, [zoom])
  useEffect(() => { panRef.current  = pan  }, [pan])

  useEffect(() => {
    const el = imageWrapRef.current
    if (!el || !modalOpen) return

    const onTouchStart = (e: TouchEvent) => {
      if (e.touches.length === 2) {
        pinchRef.current = {
          initialDistance: distance(e.touches[0], e.touches[1]),
          initialZoom: zoomRef.current,
        }
        dragRef.current = null
      } else if (e.touches.length === 1) {
        dragRef.current = {
          startX: e.touches[0].clientX,
          startY: e.touches[0].clientY,
          startPan: { ...panRef.current },
        }
      }
    }

    const onTouchMove = (e: TouchEvent) => {
      if (e.touches.length === 2 && pinchRef.current) {
        e.preventDefault()
        const d = distance(e.touches[0], e.touches[1])
        const ratio = d / pinchRef.current.initialDistance
        const newZoom = Math.min(3, Math.max(0.5, pinchRef.current.initialZoom * ratio))
        setZoomRef.current(newZoom)
      } else if (e.touches.length === 1 && dragRef.current && zoomRef.current > 1) {
        e.preventDefault()
        setPan({
          x: dragRef.current.startPan.x + (e.touches[0].clientX - dragRef.current.startX),
          y: dragRef.current.startPan.y + (e.touches[0].clientY - dragRef.current.startY),
        })
      }
    }

    const onTouchEnd = (e: TouchEvent) => {
      const wasDragging = dragRef.current
      pinchRef.current = null
      dragRef.current = null

      if (e.changedTouches.length === 1 && wasDragging) {
        const now = Date.now()
        const delta = now - lastTapRef.current
        if (delta < 300 && delta > 0) {
          e.preventDefault()
          toggleZoom()
          lastTapRef.current = 0
        } else {
          lastTapRef.current = now
        }
      }
    }

    el.addEventListener('touchstart', onTouchStart, { passive: true })
    el.addEventListener('touchmove', onTouchMove, { passive: false })
    el.addEventListener('touchend', onTouchEnd, { passive: false })
    el.addEventListener('touchcancel', onTouchEnd, { passive: false })
    return () => {
      el.removeEventListener('touchstart', onTouchStart)
      el.removeEventListener('touchmove', onTouchMove)
      el.removeEventListener('touchend', onTouchEnd)
      el.removeEventListener('touchcancel', onTouchEnd)
    }
  }, [modalOpen])

  const handleMouseDown = (e: React.MouseEvent) => {
    if (zoom > 1) {
      e.preventDefault()
      dragRef.current = {
        startX: e.clientX,
        startY: e.clientY,
        startPan: { ...pan },
      }
    }
  }

  useEffect(() => {
    if (!modalOpen) return
    const onMouseMove = (e: MouseEvent) => {
      if (dragRef.current) {
        setPan({
          x: dragRef.current.startPan.x + (e.clientX - dragRef.current.startX),
          y: dragRef.current.startPan.y + (e.clientY - dragRef.current.startY),
        })
      }
    }
    const onMouseUp = () => { dragRef.current = null }
    document.addEventListener('mousemove', onMouseMove)
    document.addEventListener('mouseup', onMouseUp)
    return () => {
      document.removeEventListener('mousemove', onMouseMove)
      document.removeEventListener('mouseup', onMouseUp)
    }
  }, [modalOpen])

  const thumbnailNode = requiresAuth ? (
    <div
      className={thumbnailClassName ?? 'pattern-thumbnail pattern-thumbnail-clickable'}
      style={{
        cursor: 'zoom-in',
        position: 'relative',
        touchAction: 'manipulation',
        WebkitTapHighlightColor: 'transparent',
        // thumbnailStyle overrides (e.g. explicit width/height from parent)
        ...thumbnailStyle,
      }}
      onClick={() => setModalOpen(true)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && setModalOpen(true)}
      aria-label={`View ${alt}`}
    >
      <AuthenticatedImage
        src={src}
        alt={alt}
        style={{
          width: '100%',
          height: '100%',
          objectFit: 'cover',
          display: 'block',
          borderRadius: 'inherit',
          pointerEvents: 'none',
        }}
        onError={() => {}}
      />
    </div>
  ) : (
    <img
      src={src}
      alt={alt}
      className={thumbnailClassName ?? 'pattern-thumbnail pattern-thumbnail-clickable'}
      style={{
        ...thumbnailStyle,
        touchAction: 'manipulation',
        WebkitTapHighlightColor: 'transparent',
      } as React.CSSProperties}
      onClick={() => setModalOpen(true)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && setModalOpen(true)}
      aria-label={`View ${alt}`}
    />
  )

  return (
    <>
      {thumbnailNode}
      <div
        className="pattern-modal-backdrop"
        onClick={() => setModalOpen(false)}
        role="button"
        tabIndex={-1}
        aria-modal="true"
        aria-label="Close"
        // Keep the modal in the DOM but invisible when closed so the image is
        // already decoded and cached when the user opens it – eliminating the
        // open/close delay on desktop caused by late mount + layout recalc.
        // Guard: only pre-render when src is non-empty to avoid spurious fetches.
        style={modalOpen ? undefined : (!src ? { display: 'none' } : { visibility: 'hidden', pointerEvents: 'none' })}
      >
        <div
          className="pattern-modal-content"
          onClick={(e) => e.stopPropagation()}
        >
          <button
            type="button"
            className="pattern-modal-close"
            onClick={() => setModalOpen(false)}
            aria-label="Close"
          >
            ×
          </button>
          <div
            ref={imageWrapRef}
            className={`pattern-modal-image-wrap${zoom > 1 ? ' pattern-modal-image-wrap--drag' : ''}`}
            onMouseDown={handleMouseDown}
            onDoubleClick={handleDoubleClick}
          >
            {requiresAuth ? (
              <AuthenticatedImage
                src={src}
                alt={alt}
                className="pattern-modal-image"
                style={{
                  transform: zoom !== 1
                    ? `scale(${zoom}) translate(${pan.x / zoom}px, ${pan.y / zoom}px)`
                    : undefined,
                }}
              />
            ) : (
              <img
                src={src}
                alt={alt}
                className="pattern-modal-image"
                style={{
                  transform: zoom !== 1
                    ? `scale(${zoom}) translate(${pan.x / zoom}px, ${pan.y / zoom}px)`
                    : undefined,
                }}
              />
            )}
          </div>
          {caption && <div className="pattern-modal-caption">{caption}</div>}
        </div>
      </div>
    </>
  )
}
