import { getPatternThumbnailCacheUrl } from '../api/client'
import ZoomableImage from './ZoomableImage'

interface PatternThumbnailProps {
  code: string
}

/**
 * Displays the thumbnail for a pattern.
 *
 * Passes the API URL directly to ZoomableImage with requiresAuth=true so that
 * the shared imageCache fetches the image once and reuses the blob URL for both
 * the grid thumbnail and the zoom modal – no intermediate blobUrl state means
 * no extra re-render cycle and no delay when opening the modal.
 */
export default function PatternThumbnail({ code }: PatternThumbnailProps) {
  const src = getPatternThumbnailCacheUrl(code)

  return (
    <ZoomableImage
      src={src}
      alt={code}
      caption={code}
      thumbnailClassName="pattern-thumbnail pattern-thumbnail-clickable"
      requiresAuth={true}
    />
  )
}
