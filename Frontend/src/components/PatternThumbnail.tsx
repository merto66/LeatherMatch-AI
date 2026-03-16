import { useEffect, useState } from 'react'
import { fetchPatternThumbnail } from '../api/client'
import ZoomableImage from './ZoomableImage'

interface PatternThumbnailProps {
  code: string
}

export default function PatternThumbnail({ code }: PatternThumbnailProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    setObjectUrl(null)
    setFailed(false)
    let cancelled = false
    let currentUrl: string | null = null

    fetchPatternThumbnail(code)
      .then((url) => {
        if (cancelled) {
          if (url) URL.revokeObjectURL(url)
          return
        }
        if (url) {
          currentUrl = url
          setObjectUrl(url)
        } else {
          setFailed(true)
        }
      })
      .catch(() => {
        if (!cancelled) setFailed(true)
      })

    return () => {
      cancelled = true
      if (currentUrl) {
        URL.revokeObjectURL(currentUrl)
      }
    }
  }, [code])

  if (objectUrl) {
    return (
      <ZoomableImage
        src={objectUrl}
        alt={code}
        caption={code}
        thumbnailClassName="pattern-thumbnail pattern-thumbnail-clickable"
      />
    )
  }

  if (failed) {
    return (
      <div className="pattern-thumbnail-placeholder" title={code}>
        {code}
      </div>
    )
  }

  return (
    <div className="pattern-thumbnail-placeholder" title={code} aria-hidden>
      —
    </div>
  )
}
