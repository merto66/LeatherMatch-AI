import { DragEvent, useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  getPatterns,
  matchImage,
  MatchResult,
  submitFeedback,
} from '../api/client'
import PatternThumbnail from '../components/PatternThumbnail'

export default function MatchPage() {
  const fileInput    = useRef<HTMLInputElement>(null)
  const [lastFile, setLastFile] = useState<File | null>(null)
  const [preview, setPreview]   = useState<string | null>(null)
  const [result, setResult]     = useState<MatchResult | null>(null)
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState('')
  const [dragOver, setDragOver] = useState(false)
  const [showFeedbackForm, setShowFeedbackForm] = useState(false)
  const [patterns, setPatterns] = useState<string[]>([])
  const [feedbackPattern, setFeedbackPattern] = useState('')
  const [feedbackNote, setFeedbackNote] = useState('')
  const [feedbackSubmitting, setFeedbackSubmitting] = useState(false)
  const [feedbackSuccess, setFeedbackSuccess] = useState('')
  const [patternsLoading, setPatternsLoading] = useState(false)
  const { t } = useTranslation()

  useEffect(() => {
    if (showFeedbackForm) {
      setPatternsLoading(true)
      getPatterns()
        .then(setPatterns)
        .catch(() => setPatterns([]))
        .finally(() => setPatternsLoading(false))
    }
  }, [showFeedbackForm])

  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview)
  }, [preview])

  function handleFiles(files: FileList | null) {
    if (!files || files.length === 0) return
    const file = files[0]
    setLastFile(file)
    setPreview(prev => {
      if (prev) URL.revokeObjectURL(prev)
      return URL.createObjectURL(file)
    })
    setResult(null)
    setError('')
    setShowFeedbackForm(false)
    setFeedbackSuccess('')
    runMatch(file)
  }

  async function runMatch(file: File) {
    setLoading(true)
    setError('')
    try {
      const r = await matchImage(file)
      setResult(r)
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('match.matchFailed')
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragOver(false)
    handleFiles(e.dataTransfer.files)
  }

  async function handleSubmitFeedback() {
    if (!result || !feedbackPattern.trim()) return
    if (!lastFile) {
      setError(t('match.reUploadError'))
      return
    }
    setFeedbackSubmitting(true)
    setError('')
    setFeedbackSuccess('')
    try {
      await submitFeedback(lastFile, {
        predictedPattern: result.patternCode,
        predictedScore: result.similarityScore,
        threshold: result.threshold,
        margin: result.marginUsed ?? 0.03,
        operatorSelectedPattern: feedbackPattern.trim(),
        note: feedbackNote.trim() || undefined,
        secondBestScore: result.secondBestScore ?? undefined,
      })
      setFeedbackSuccess(t('match.feedbackSubmitted'))
      setShowFeedbackForm(false)
      setFeedbackPattern('')
      setFeedbackNote('')
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('match.failedToSubmitFeedback')
      setError(msg)
    } finally {
      setFeedbackSubmitting(false)
    }
  }

  function confidenceBadge(c: string) {
    if (c === 'HIGH')      return 'badge badge-success'
    if (c === 'MEDIUM')    return 'badge badge-warning'
    return 'badge badge-danger'
  }

  const topScores = result
    ? Object.entries(result.allPatternScores)
        .sort(([, a], [, b]) => b - a)
        .slice(0, 5)
    : []

  const patternOptions = patterns.length > 0
    ? patterns
    : (result ? Object.keys(result.allPatternScores).sort() : [])

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">{t('match.title')}</h1>
      </div>

      <div className="page-body">
        <div className="grid-2" style={{ alignItems: 'start' }}>
          {/* Upload panel */}
          <div className="card">
            <p style={{ marginBottom: 12, fontWeight: 600 }}>{t('match.uploadLabel')}</p>

            <div
              className={`upload-area${dragOver ? ' drag-over' : ''}`}
              onClick={() => fileInput.current?.click()}
              onDragOver={e => { e.preventDefault(); setDragOver(true) }}
              onDragLeave={() => setDragOver(false)}
              onDrop={onDrop}
            >
              <input
                ref={fileInput}
                type="file"
                accept=".jpg,.jpeg,.png"
                onChange={e => handleFiles(e.target.files)}
              />
              {preview ? (
                <img
                  src={preview}
                  alt="preview"
                  style={{ maxHeight: 220, maxWidth: '100%', borderRadius: 6, objectFit: 'contain' }}
                />
              ) : (
                <>
                  <div style={{ fontSize: 36, marginBottom: 8 }}>&#128247;</div>
                  <div style={{ fontWeight: 600 }}>{t('match.clickOrDrag')}</div>
                  <div className="text-muted" style={{ fontSize: 12, marginTop: 4 }}>
                    {t('match.fileHint')}
                  </div>
                </>
              )}
            </div>

            {loading && (
              <div className="flex items-center gap-2 mt-2" style={{ justifyContent: 'center' }}>
                <span className="spinner" />
                <span className="text-muted">{t('match.matching')}</span>
              </div>
            )}
          </div>

          {/* Result panel */}
          <div className="card">
            <p style={{ marginBottom: 12, fontWeight: 600 }}>{t('match.result')}</p>

            {error && <div className="alert alert-error">{error}</div>}

            {!result && !error && !loading && (
              <div className="empty-state">{t('match.emptyResult')}</div>
            )}

            {result && (
              <>
                <div style={{ marginBottom: 16 }}>
                  <div style={{ fontSize: 28, fontWeight: 800, marginBottom: 4 }}>
                    {result.patternCode}
                  </div>
                  <span className={confidenceBadge(result.confidence)}>
                    {t(`confidence.${result.confidence}`, result.confidence)}
                  </span>
                  {result.isMatch ? (
                    <span className="badge badge-success" style={{ marginLeft: 6 }}>{t('matchResult.MATCH')}</span>
                  ) : (
                    <span className="badge badge-warning" style={{ marginLeft: 6 }}>{t('matchResult.UNCERTAIN')}</span>
                  )}
                </div>

                <div className="grid-2" style={{ marginBottom: 16 }}>
                  <div>
                    <div className="text-muted" style={{ fontSize: 11, marginBottom: 2 }}>{t('match.score')}</div>
                    <div style={{ fontWeight: 700, fontSize: 18 }}>
                      {(result.similarityScore * 100).toFixed(1)}%
                    </div>
                  </div>
                  <div>
                    <div className="text-muted" style={{ fontSize: 11, marginBottom: 2 }}>{t('match.threshold')}</div>
                    <div style={{ fontWeight: 700, fontSize: 18 }}>
                      {(result.threshold * 100).toFixed(0)}%
                    </div>
                  </div>
                  <div>
                    <div className="text-muted" style={{ fontSize: 11, marginBottom: 2 }}>{t('match.time')}</div>
                    <div style={{ fontWeight: 600 }}>{result.processingTimeMs} ms</div>
                  </div>
                </div>

                {/* Score bar */}
                <div style={{ marginBottom: 2, fontSize: 12, color: 'var(--text-muted)' }}>
                  {t('match.similarity')}
                </div>
                <div className="progress-bar" style={{ marginBottom: 16 }}>
                  <div
                    className="progress-fill"
                    style={{
                      width: `${result.similarityScore * 100}%`,
                      background: result.isMatch ? 'var(--success)' : 'var(--danger)',
                    }}
                  />
                </div>

                {/* Top scores */}
                <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 6 }}>
                  {t('match.top5')}
                </div>
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>{t('match.pattern')}</th>
                        <th>{t('match.score')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {topScores.map(([code, score]) => (
                        <tr key={code}>
                          <td style={{ fontWeight: code === result.patternCode ? 700 : 400 }}>
                            <div className="pattern-cell">
                              <PatternThumbnail code={code} />
                              <span>{code}</span>
                            </div>
                          </td>
                          <td>{(score * 100).toFixed(1)}%</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {feedbackSuccess && (
                  <div className="alert alert-success" style={{ marginTop: 16 }}>
                    {feedbackSuccess}
                  </div>
                )}
                {!showFeedbackForm ? (
                  <div style={{ marginTop: 16 }}>
                    <button
                      className="btn btn-outline"
                      onClick={() => {
                        if (!lastFile) {
                          setError(t('match.reUploadError'))
                          return
                        }
                        setShowFeedbackForm(true)
                        setFeedbackPattern('')
                        setFeedbackNote('')
                        setFeedbackSuccess('')
                      }}
                    >
                      {t('match.thisIsWrong')}
                    </button>
                    {!lastFile && result && (
                      <span className="text-muted" style={{ marginLeft: 8, fontSize: 12 }}>
                        {t('match.reUploadHint')}
                      </span>
                    )}
                  </div>
                ) : (
                  <div className="card" style={{ marginTop: 16, padding: 16, background: 'var(--bg-secondary)' }}>
                    <p style={{ fontWeight: 600, marginBottom: 12 }}>{t('match.submitCorrection')}</p>
                    <div style={{ marginBottom: 12 }}>
                      <label style={{ display: 'block', fontSize: 12, marginBottom: 4 }}>{t('match.correctPattern')}</label>
                      <select
                        className="form-input"
                        value={feedbackPattern}
                        onChange={e => setFeedbackPattern(e.target.value)}
                        style={{ width: '100%' }}
                        disabled={patternsLoading && patternOptions.length === 0}
                      >
                        <option value="">
                          {patternsLoading && patternOptions.length === 0 ? t('match.loading') : t('match.selectPattern')}
                        </option>
                        {patternOptions.map(code => (
                          <option key={code} value={code}>{code}</option>
                        ))}
                      </select>
                    </div>
                    <div style={{ marginBottom: 12 }}>
                      <label style={{ display: 'block', fontSize: 12, marginBottom: 4 }}>{t('match.note')}</label>
                      <textarea
                        className="form-input"
                        value={feedbackNote}
                        onChange={e => setFeedbackNote(e.target.value)}
                        placeholder={t('match.notePlaceholder')}
                        rows={2}
                        style={{ width: '100%', resize: 'vertical' }}
                      />
                    </div>
                    <div className="flex gap-2">
                      <button
                        className="btn btn-primary"
                        onClick={handleSubmitFeedback}
                        disabled={feedbackSubmitting || !feedbackPattern.trim()}
                      >
                        {feedbackSubmitting ? <><span className="spinner" /> {t('match.submitting')}</> : t('match.submitFeedback')}
                      </button>
                      <button
                        className="btn btn-outline"
                        onClick={() => setShowFeedbackForm(false)}
                        disabled={feedbackSubmitting}
                      >
                        {t('match.cancel')}
                      </button>
                    </div>
                  </div>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </>
  )
}
