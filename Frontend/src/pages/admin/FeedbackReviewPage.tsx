import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  approveAndAddReference,
  approveFeedback,
  FeedbackEntry,
  getFeedbackList,
  rejectFeedback,
} from '../../api/client'
import LazyAuthenticatedImage from '../../components/LazyAuthenticatedImage'

export default function FeedbackReviewPage() {
  const [items, setItems] = useState<FeedbackEntry[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [status, setStatus] = useState('PENDING')
  const [limit] = useState(20)
  const [offset, setOffset] = useState(0)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [actingId, setActingId] = useState<number | null>(null)
  const { t } = useTranslation()

  useEffect(() => {
    load()
  }, [status, offset])

  useEffect(() => {
    setOffset(0)
  }, [status])

  async function load() {
    setLoading(true)
    setError('')
    try {
      const resp = await getFeedbackList({ status, limit, offset })
      setItems(resp.data)
      setTotal(resp.total)
    } catch {
      setError(t('feedback.failedToLoad'))
    } finally {
      setLoading(false)
    }
  }

  async function handleApprove(id: number) {
    setActingId(id)
    setError('')
    setSuccess('')
    try {
      await approveFeedback(id)
      setSuccess(t('feedback.approved'))
      load()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('feedback.failedToApprove')
      setError(msg)
    } finally {
      setActingId(null)
    }
  }

  async function handleReject(id: number) {
    setActingId(id)
    setError('')
    setSuccess('')
    try {
      await rejectFeedback(id)
      setSuccess(t('feedback.rejected'))
      load()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('feedback.failedToReject')
      setError(msg)
    } finally {
      setActingId(null)
    }
  }

  async function handleApproveAndAdd(id: number) {
    setActingId(id)
    setError('')
    setSuccess('')
    try {
      const r = await approveAndAddReference(id)
      setSuccess(t('feedback.approvedAndAdded', { pattern: r.patternCode }))
      load()
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('feedback.failedToApproveAndAdd')
      setError(msg)
    } finally {
      setActingId(null)
    }
  }

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">{t('feedback.title')}</h1>
        <p className="text-muted" style={{ margin: 0, fontSize: 14 }}>
          {t('feedback.subtitle')}
        </p>
      </div>

      <div className="page-body">
        {error && <div className="alert alert-error">{error}</div>}
        {success && <div className="alert alert-success">{success}</div>}

        <div className="card" style={{ marginBottom: 20 }}>
          <div className="flex gap-2" style={{ alignItems: 'center', marginBottom: 16 }}>
            <label style={{ fontSize: 14 }}>{t('feedback.status')}</label>
            <select
              className="form-input"
              value={status}
              onChange={e => setStatus(e.target.value)}
              style={{ width: 140 }}
            >
              <option value="PENDING">{t('feedbackStatus.PENDING')}</option>
              <option value="APPROVED">{t('feedbackStatus.APPROVED')}</option>
              <option value="REJECTED">{t('feedbackStatus.REJECTED')}</option>
            </select>
            <span className="text-muted" style={{ fontSize: 13 }}>
              {t('feedback.total', { count: total })}
            </span>
          </div>
        </div>

        {loading ? (
          <div className="empty-state"><span className="spinner" /></div>
        ) : items.length === 0 ? (
          <div className="empty-state">
            {t('feedback.noItems', { status: t(`feedbackStatus.${status}`, status) })}
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {items.map(fb => (
              <div key={fb.id} className="card feedback-card">
                <div>
                  <LazyAuthenticatedImage
                    src={`/api/admin/feedback/${fb.id}/image`}
                    alt="feedback"
                    style={{
                      width: '100%',
                      aspectRatio: '1',
                      objectFit: 'cover',
                      borderRadius: 6,
                      border: '1px solid var(--border)',
                    }}
                  />
                </div>
                <div>
                  <div style={{ marginBottom: 8 }}>
                    <span className="badge badge-info" style={{ marginRight: 8 }}>
                      {t('feedback.predicted', { pattern: fb.predictedPattern })}
                    </span>
                    <span style={{ fontWeight: 600 }}>
                      {(fb.predictedScore * 100).toFixed(1)}%
                    </span>
                    {fb.secondBestScore != null && (
                      <span className="text-muted" style={{ marginLeft: 8, fontSize: 12 }}>
                        (2nd: {(fb.secondBestScore * 100).toFixed(1)}%)
                      </span>
                    )}
                  </div>
                  <div style={{ marginBottom: 4 }}>
                    <span className="text-muted">{t('feedback.operatorSelected')}</span>
                    <strong>{fb.operatorSelectedPattern}</strong>
                  </div>
                  {fb.note && (
                    <div style={{ fontSize: 13, color: 'var(--text-muted)', marginTop: 4 }}>
                      {t('feedback.note')}{fb.note}
                    </div>
                  )}
                  <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 6 }}>
                    {fb.createdAt.slice(0, 19).replace('T', ' ')}
                  </div>
                </div>
                <div className="feedback-actions">
                  {fb.status === 'PENDING' && (
                    <>
                      <button
                        className="btn btn-success btn-sm"
                        onClick={() => handleApprove(fb.id)}
                        disabled={actingId !== null}
                      >
                        {actingId === fb.id ? <span className="spinner" /> : t('feedback.approve')}
                      </button>
                      <button
                        className="btn btn-danger btn-sm"
                        onClick={() => handleReject(fb.id)}
                        disabled={actingId !== null}
                      >
                        {actingId === fb.id ? <span className="spinner" /> : t('feedback.reject')}
                      </button>
                      <button
                        className="btn btn-primary btn-sm"
                        onClick={() => handleApproveAndAdd(fb.id)}
                        disabled={actingId !== null}
                        title="Approve and add image as reference for the selected pattern"
                      >
                        {actingId === fb.id ? <span className="spinner" /> : t('feedback.approveAndAdd')}
                      </button>
                    </>
                  )}
                  {fb.status !== 'PENDING' && (
                    <div>
                      <span className="badge badge-info">{t(`feedbackStatus.${fb.status}`, fb.status)}</span>
                      {fb.reviewedAt && (
                        <div className="text-muted" style={{ fontSize: 11, marginTop: 4 }}>
                          {fb.reviewedAt.slice(0, 19).replace('T', ' ')}
                          {fb.reviewedBy && ` by ${fb.reviewedBy}`}
                        </div>
                      )}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}

        {total > limit && (
          <div className="flex gap-2" style={{ marginTop: 20, justifyContent: 'center' }}>
            <button
              className="btn btn-outline btn-sm"
              disabled={offset === 0}
              onClick={() => setOffset(Math.max(0, offset - limit))}
            >
              {t('feedback.previous')}
            </button>
            <button
              className="btn btn-outline btn-sm"
              disabled={offset + limit >= total}
              onClick={() => setOffset(offset + limit)}
            >
              {t('feedback.next')}
            </button>
          </div>
        )}
      </div>
    </>
  )
}
