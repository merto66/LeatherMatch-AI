import { DragEvent, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import {
  deleteReference,
  getAdminPatterns,
  getReferences,
  importFromDisk,
  PatternDto,
  ReferenceImageDto,
  setThumbnailReference,
  uploadReferences,
} from '../../api/client'
import ZoomableImage from '../../components/ZoomableImage'

export default function PatternDetailPage() {
  const { id } = useParams<{ id: string }>()
  const patternId = Number(id)
  const { t } = useTranslation()

  const [pattern, setPattern]       = useState<PatternDto | null>(null)
  const [refs, setRefs]             = useState<ReferenceImageDto[]>([])
  const [loading, setLoading]       = useState(true)
  const [uploading, setUploading]   = useState(false)
  const [importing, setImporting]   = useState(false)
  const [dragOver, setDragOver]     = useState(false)
  const [error, setError]           = useState('')
  const [success, setSuccess]       = useState('')
  const [progress, setProgress]     = useState(0)
  const [thumbnailSelectOpen, setThumbnailSelectOpen] = useState(false)
  const [settingThumbnail, setSettingThumbnail] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const refThumbStyle = useMemo(() => ({
    width: '100%', height: '100%', objectFit: 'cover' as const, display: 'block', cursor: 'zoom-in',
  }), [])

  useEffect(() => { load() }, [patternId])

  async function load() {
    setLoading(true)
    try {
      const [all, references] = await Promise.all([
        getAdminPatterns(),
        getReferences(patternId),
      ])
      setPattern(all.find(p => p.id === patternId) ?? null)
      setRefs(references)
    } catch {
      setError(t('patternDetail.failedToLoad'))
    } finally {
      setLoading(false)
    }
  }

  async function handleUpload(files: FileList | null) {
    if (!files || files.length === 0) return
    const fileArr = Array.from(files)
    setUploading(true)
    setError('')
    setSuccess('')
    setProgress(0)

    try {
      const batchSize = 5
      const added: ReferenceImageDto[] = []
      for (let i = 0; i < fileArr.length; i += batchSize) {
        const batch = fileArr.slice(i, i + batchSize)
        const results = await uploadReferences(patternId, batch)
        added.push(...results)
        setProgress(Math.round(((i + batch.length) / fileArr.length) * 100))
      }
      setRefs(prev => [...prev, ...added])
      setSuccess(t('patternDetail.refsAdded', { count: added.length }))
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('patternDetail.uploadFailed')
      setError(msg)
    } finally {
      setUploading(false)
      setProgress(0)
      if (fileInput.current) fileInput.current.value = ''
    }
  }

  async function handleImportFromDisk() {
    if (!pattern) return
    if (!confirm(t('patternDetail.scanConfirm', { code: pattern.code }))) return

    setImporting(true)
    setError('')
    setSuccess('')
    try {
      const result = await importFromDisk(patternId)
      const fresh = await getReferences(patternId)
      setRefs(fresh)
      let msg = t('patternDetail.importComplete', { imported: result.imported, skipped: result.skipped })
      if (result.errors && result.errors.length > 0) {
        msg += t('patternDetail.importErrors', { count: result.errors.length }) + result.errors[0]
      }
      setSuccess(msg)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('patternDetail.importFailed')
      setError(msg)
    } finally {
      setImporting(false)
    }
  }

  async function handleDelete(ref: ReferenceImageDto) {
    if (!confirm(t('patternDetail.confirmDeleteRef', { id: ref.id }))) return
    try {
      await deleteReference(ref.id)
      setRefs(prev => prev.filter(r => r.id !== ref.id))
      setSuccess(t('patternDetail.refDeleted'))
      load()
    } catch {
      setError(t('patternDetail.failedToDelete'))
    }
  }

  async function handleSetThumbnail(ref: ReferenceImageDto) {
    setError('')
    setSuccess('')
    setSettingThumbnail(true)
    try {
      await setThumbnailReference(patternId, ref.id)
      setSuccess(t('patternDetail.thumbnailSet', { id: ref.id }))
      setThumbnailSelectOpen(false)
      setPattern((prev) => (prev ? { ...prev, thumbnailReferenceId: ref.id } : null))
    } catch (err: unknown) {
      const ax = err as { response?: { data?: { message?: string }; status?: number }; message?: string }
      const msg = ax?.response?.data?.message ?? (ax?.response?.status === 404 ? t('patternDetail.patternNotFound') : ax?.message ?? t('patternDetail.failedToSetThumbnail'))
      setError(msg)
    } finally {
      setSettingThumbnail(false)
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragOver(false)
    handleUpload(e.dataTransfer.files)
  }

  if (loading) return <div className="empty-state" style={{ padding: 60 }}><span className="spinner" /></div>

  if (!pattern) return (
    <div className="page-body">
      <div className="alert alert-error">{t('patternDetail.patternNotFound')}</div>
      <Link to="/admin/patterns" className="btn btn-outline">{t('patternDetail.backToPatterns')}</Link>
    </div>
  )

  const hasUnimportedRefs = pattern.referenceCount > refs.length
  const thumbnailRefId = pattern.thumbnailReferenceId ?? null

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">
          <Link to="/admin/patterns" style={{ color: 'var(--text-muted)', fontWeight: 400 }}>
            {t('patterns.title')}
          </Link>
          {' / '}{pattern.code}
        </h1>
        <div className="flex gap-2 items-center">
          <span className="badge badge-info">{refs.length} {t('patternDetail.inDatabase')}</span>
          {hasUnimportedRefs && (
            <span className="badge badge-warning">
              {pattern.referenceCount} {t('patternDetail.totalIncLegacy')}
            </span>
          )}
        </div>
      </div>

      <div className="page-body">
        {error   && <div className="alert alert-error">{error}</div>}
        {success && <div className="alert alert-success">{success}</div>}

        {/* Import from disk banner */}
        {hasUnimportedRefs && (
          <div className="alert alert-info" style={{ marginBottom: 16 }}>
            <strong>{pattern.referenceCount - refs.length} </strong>
            {t('patternDetail.legacyBanner', { count: pattern.referenceCount - refs.length })
              .replace(/<[^>]+>/g, '')}
            <div style={{ marginTop: 8 }}>
              <button
                className="btn btn-primary btn-sm"
                onClick={handleImportFromDisk}
                disabled={importing}
              >
                {importing
                  ? <><span className="spinner" /> {t('patternDetail.importing')}</>
                  : t('patternDetail.importFromDisk', { count: pattern.referenceCount - refs.length })}
              </button>
            </div>
          </div>
        )}

        {/* Upload area */}
        <div className="card mb-4" style={{ marginBottom: 20 }}>
          <div className="flex items-center" style={{ marginBottom: 12, gap: 12 }}>
            <p style={{ fontWeight: 600, margin: 0 }}>{t('patternDetail.addNewRefs')}</p>
            <button
              className="btn btn-outline btn-sm"
              style={{ marginLeft: 'auto' }}
              onClick={handleImportFromDisk}
              disabled={importing}
              title="Scan Leather_Images folder and import all unregistered image files"
            >
              {importing ? <span className="spinner" /> : t('patternDetail.importFromDiskShort')}
            </button>
          </div>

          <div
            className={`upload-area${dragOver ? ' drag-over' : ''}`}
            onClick={() => !uploading && fileInput.current?.click()}
            onDragOver={e => { e.preventDefault(); setDragOver(true) }}
            onDragLeave={() => setDragOver(false)}
            onDrop={onDrop}
            style={{ padding: 24 }}
          >
            <input
              ref={fileInput}
              type="file"
              accept=".jpg,.jpeg,.png"
              multiple
              onChange={e => handleUpload(e.target.files)}
            />
            {uploading ? (
              <div>
                <div className="flex items-center gap-2" style={{ justifyContent: 'center', marginBottom: 12 }}>
                  <span className="spinner" />
                  <span>{t('patternDetail.uploadingEmbeddings')}</span>
                </div>
                <div className="progress-bar" style={{ maxWidth: 300, margin: '0 auto' }}>
                  <div className="progress-fill" style={{ width: `${progress}%` }} />
                </div>
                <div className="text-muted" style={{ textAlign: 'center', marginTop: 6, fontSize: 12 }}>
                  {progress}%
                </div>
              </div>
            ) : (
              <>
                <div style={{ fontSize: 28, marginBottom: 8 }}>&#128443;</div>
                <div style={{ fontWeight: 600 }}>{t('patternDetail.clickOrDragImages')}</div>
                <div className="text-muted" style={{ fontSize: 12, marginTop: 4 }}>
                  {t('patternDetail.multipleHint')}
                </div>
              </>
            )}
          </div>
        </div>

        {/* References table */}
        <div className="card">
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12, flexWrap: 'wrap', gap: 8 }}>
            <p style={{ fontWeight: 600, margin: 0 }}>
              {t('patternDetail.registeredRefs', { count: refs.length })}
            </p>
            {refs.length > 0 && (
              <button
                type="button"
                className="btn btn-outline btn-sm"
                onClick={() => setThumbnailSelectOpen(true)}
              >
                {t('patternDetail.setThumbnail')}
              </button>
            )}
          </div>
          {refs.length === 0 ? (
            <div className="empty-state">
              {hasUnimportedRefs
                ? t('patternDetail.noRefsImport')
                : t('patternDetail.noRefsUpload')}
            </div>
          ) : (
            <>
              {/* Visual grid of thumbnails */}
              <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(120px, 1fr))',
                gap: 10,
                marginBottom: 20,
              }}>
                {refs.map(r => (
                  <div key={r.id} style={{ position: 'relative', textAlign: 'center' }}>
                    <div style={{
                      width: '100%',
                      aspectRatio: '1',
                      borderRadius: 6,
                      border: thumbnailRefId === r.id ? '2px solid var(--primary)' : '1px solid var(--border)',
                      overflow: 'hidden',
                      position: 'relative',
                    }}>
                      <ZoomableImage
                        src={`/api/admin/references/${r.id}/image`}
                        alt={r.imagePath.split(/[\\/]/).pop() ?? `#${r.id}`}
                        caption={`#${r.id}`}
                        thumbnailClassName="ref-photo-thumbnail"
                        thumbnailStyle={refThumbStyle}
                      />
                    </div>
                    <button
                      onClick={() => handleDelete(r)}
                      title="Delete"
                      style={{
                        position: 'absolute',
                        top: 4,
                        right: 4,
                        background: 'rgba(220,38,38,0.85)',
                        color: '#fff',
                        border: 'none',
                        borderRadius: 4,
                        width: 22,
                        height: 22,
                        cursor: 'pointer',
                        fontSize: 13,
                        lineHeight: '22px',
                        padding: 0,
                      }}
                    >
                      ×
                    </button>
                    <div style={{ fontSize: 10, color: 'var(--text-muted)', marginTop: 4, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                      #{r.id}
                    </div>
                  </div>
                ))}
              </div>

              {/* Thumbnail selection modal */}
              {thumbnailSelectOpen && (
                <div
                  className="pattern-modal-backdrop"
                  onClick={() => setThumbnailSelectOpen(false)}
                  style={{ zIndex: 2001 }}
                >
                  <div
                    className="pattern-modal-content"
                    onClick={(e) => e.stopPropagation()}
                    style={{ maxWidth: '90vw', width: 'auto' }}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
                      <p style={{ fontWeight: 600, margin: 0, color: '#fff' }}>
                        {t('patternDetail.selectThumbnail')}
                      </p>
                      <button
                        type="button"
                        className="pattern-modal-close"
                        onClick={() => setThumbnailSelectOpen(false)}
                        aria-label="Close"
                      >
                        ×
                      </button>
                    </div>
                    <div style={{
                      display: 'grid',
                      gridTemplateColumns: 'repeat(auto-fill, minmax(100px, 1fr))',
                      gap: 12,
                      maxHeight: '70vh',
                      overflowY: 'auto',
                    }}>
                      {refs.map(r => (
                        <button
                          key={r.id}
                          type="button"
                          onClick={() => handleSetThumbnail(r)}
                          disabled={settingThumbnail}
                          style={{
                            padding: 0,
                            border: thumbnailRefId === r.id ? '3px solid var(--primary)' : '2px solid var(--border)',
                            borderRadius: 8,
                            background: 'transparent',
                            cursor: 'pointer',
                            overflow: 'hidden',
                            aspectRatio: '1',
                          }}
                        >
                          <img
                            src={`/api/admin/references/${r.id}/image`}
                            alt={`#${r.id}`}
                            style={{
                              width: '100%',
                              height: '100%',
                              objectFit: 'cover',
                              display: 'block',
                            }}
                          />
                          <div style={{ fontSize: 10, color: '#fff', padding: 4, background: 'rgba(0,0,0,0.5)' }}>
                            #{r.id} {thumbnailRefId === r.id ? '✓' : ''}
                          </div>
                        </button>
                      ))}
                    </div>
                  </div>
                </div>
              )}

              {/* Detail table (collapsed by default) */}
              <details>
                <summary style={{ cursor: 'pointer', fontSize: 13, color: 'var(--text-muted)', marginBottom: 8 }}>
                  {t('patternDetail.showDetails', { count: refs.length })}
                </summary>
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>{t('patternDetail.id')}</th>
                        <th>{t('patternDetail.filename')}</th>
                        <th>{t('patternDetail.dim')}</th>
                        <th>{t('patterns.created')}</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      {refs.map(r => (
                        <tr key={r.id}>
                          <td className="text-muted">#{r.id}</td>
                          <td style={{ wordBreak: 'break-all', maxWidth: 260 }}>
                            {r.imagePath.split(/[\\/]/).pop()}
                          </td>
                          <td>{r.embeddingDim}</td>
                          <td className="text-muted">{r.createdAt.slice(0, 19).replace('T', ' ')}</td>
                          <td>
                            <button
                              className="btn btn-danger btn-sm"
                              onClick={() => handleDelete(r)}
                            >
                              {t('patterns.delete')}
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </details>
            </>
          )}
        </div>
      </div>
    </>
  )
}
