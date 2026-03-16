import { useEffect, useRef, useState } from 'react'

function distance(t1: Touch, t2: Touch) {
  return Math.hypot(t2.clientX - t1.clientX, t2.clientY - t1.clientY)
}

interface ZoomableImageProps {
  src: string
  alt: string
  caption?: string
  thumbnailClassName?: string
  thumbnailStyle?: React.CSSProperties
}

export default function ZoomableImage({
  src,
  alt,
  caption,
  thumbnailClassName,
  thumbnailStyle,
}: ZoomableImageProps) {
  const [modalOpen, setModalOpen] = useState(false)
  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 0, y: 0 })
  const pinchRef = useRef<{ initialDistance: number; initialZoom: number } | null>(null)
  const dragRef = useRef<{ startX: number; startY: number; startPan: { x: number; y: number } } | null>(null)
  const imageWrapRef = useRef<HTMLDivElement>(null)
  const setZoomRef = useRef(setZoom)
  setZoomRef.current = setZoom

  const handleDoubleClick = (e: React.MouseEvent) => {
    e.stopPropagation()
    setZoom((z) => {
      const next = z >= 1.5 ? 1 : 2
      if (next === 1) setPan({ x: 0, y: 0 })
      return next
    })
  }

  useEffect(() => {
    if (!modalOpen) return
    setZoom(1)
    setPan({ x: 0, y: 0 })
    const onEscape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setModalOpen(false)
    }
    document.addEventListener('keydown', onEscape)
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onEscape)
      document.body.style.overflow = ''
    }
  }, [modalOpen])

  useEffect(() => {
    const el = imageWrapRef.current
    if (!el || !modalOpen) return

    const onTouchStart = (e: TouchEvent) => {
      if (e.touches.length === 2) {
        pinchRef.current = {
          initialDistance: distance(e.touches[0], e.touches[1]),
          initialZoom: zoom,
        }
        dragRef.current = null
      } else if (e.touches.length === 1 && zoom > 1) {
        dragRef.current = {
          startX: e.touches[0].clientX,
          startY: e.touches[0].clientY,
          startPan: { ...pan },
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
      } else if (e.touches.length === 1 && dragRef.current) {
        e.preventDefault()
        setPan({
          x: dragRef.current.startPan.x + (e.touches[0].clientX - dragRef.current.startX),
          y: dragRef.current.startPan.y + (e.touches[0].clientY - dragRef.current.startY),
        })
      }
    }

    const onTouchEnd = () => {
      pinchRef.current = null
      dragRef.current = null
    }

    el.addEventListener('touchstart', onTouchStart, { passive: true })
    el.addEventListener('touchmove', onTouchMove, { passive: false })
    el.addEventListener('touchend', onTouchEnd, { passive: true })
    el.addEventListener('touchcancel', onTouchEnd, { passive: true })
    return () => {
      el.removeEventListener('touchstart', onTouchStart)
      el.removeEventListener('touchmove', onTouchMove)
      el.removeEventListener('touchend', onTouchEnd)
      el.removeEventListener('touchcancel', onTouchEnd)
    }
  }, [modalOpen, zoom, pan])

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

  return (
    <>
      <img
        src={src}
        alt={alt}
        className={thumbnailClassName ?? 'pattern-thumbnail pattern-thumbnail-clickable'}
        style={thumbnailStyle}
        onClick={() => setModalOpen(true)}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => e.key === 'Enter' && setModalOpen(true)}
        aria-label={`View ${alt}`}
      />
      {modalOpen && (
        <div
          className="pattern-modal-backdrop"
          onClick={() => setModalOpen(false)}
          role="button"
          tabIndex={-1}
          aria-modal="true"
          aria-label="Close"
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
            </div>
            {caption && <div className="pattern-modal-caption">{caption}</div>}
          </div>
        </div>
      )}
    </>
  )
}
