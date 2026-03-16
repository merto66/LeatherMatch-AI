import { FormEvent, useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getSettings, updateThreshold, updateMargin } from '../../api/client'

export default function SettingsPage() {
  const [threshold, setThreshold] = useState(0.70)
  const [draftThreshold, setDraftThreshold] = useState(0.70)
  const [margin, setMargin] = useState(0.03)
  const [draftMargin, setDraftMargin] = useState(0.03)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const { t } = useTranslation()

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      const s = await getSettings()
      setThreshold(s.threshold)
      setDraftThreshold(s.threshold)
      setMargin(s.margin ?? 0.03)
      setDraftMargin(s.margin ?? 0.03)
    } catch {
      setError(t('settings.failedToLoad'))
    } finally {
      setLoading(false)
    }
  }

  async function handleSaveThreshold(e: FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError('')
    setSuccess('')
    try {
      const s = await updateThreshold(draftThreshold)
      setThreshold(s.threshold)
      setDraftThreshold(s.threshold)
      setSuccess(t('settings.thresholdUpdated'))
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('settings.failedThreshold')
      setError(msg)
    } finally {
      setSaving(false)
    }
  }

  async function handleSaveMargin(e: FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError('')
    setSuccess('')
    try {
      const s = await updateMargin(draftMargin)
      setMargin(s.margin ?? 0.03)
      setDraftMargin(s.margin ?? 0.03)
      setSuccess(t('settings.marginUpdated'))
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('settings.failedMargin')
      setError(msg)
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <div className="empty-state" style={{ padding: 60 }}><span className="spinner" /></div>

  const pctThreshold = Math.round(draftThreshold * 100)
  const pctMargin = Math.round(draftMargin * 100)

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">{t('settings.title')}</h1>
      </div>

      <div className="page-body">
        {error   && <div className="alert alert-error">{error}</div>}
        {success && <div className="alert alert-success">{success}</div>}

        {/* Threshold Card */}
        <div className="card" style={{ maxWidth: 480, marginBottom: 24 }}>
          <p style={{ fontWeight: 600, marginBottom: 4 }}>{t('settings.thresholdTitle')}</p>
          <p className="text-muted" style={{ fontSize: 13, marginBottom: 20 }}>
            {t('settings.thresholdDesc')}
          </p>

          <form onSubmit={handleSaveThreshold}>
            <div className="form-group">
              <label className="form-label">
                {t('settings.thresholdTitle')}: <strong>{pctThreshold}%</strong>
                {' '}
                <span className="text-muted" style={{ fontSize: 11 }}>
                  {t('settings.currentValue', { pct: Math.round(threshold * 100) })}
                </span>
              </label>
              <input
                type="range"
                min={0}
                max={100}
                step={1}
                value={pctThreshold}
                onChange={e => setDraftThreshold(Number(e.target.value) / 100)}
                style={{ width: '100%', accentColor: 'var(--primary)' }}
              />
              <div className="flex" style={{ justifyContent: 'space-between', marginTop: 4 }}>
                <span className="text-muted" style={{ fontSize: 11 }}>{t('settings.matchEverything')}</span>
                <span className="text-muted" style={{ fontSize: 11 }}>{t('settings.exactOnly')}</span>
              </div>
            </div>

            {/* Quick presets */}
            <div className="flex gap-2" style={{ marginBottom: 16, flexWrap: 'wrap' }}>
              {[60, 65, 70, 75, 80].map(v => (
                <button
                  key={v}
                  type="button"
                  className={`btn btn-sm ${draftThreshold === v / 100 ? 'btn-primary' : 'btn-outline'}`}
                  onClick={() => setDraftThreshold(v / 100)}
                >
                  {v}%
                </button>
              ))}
            </div>

            {/* Confidence bands explainer */}
            <div style={{ background: 'var(--bg)', borderRadius: 6, padding: 12, marginBottom: 16, fontSize: 12 }}>
              <div style={{ marginBottom: 6, fontWeight: 600 }}>{t('settings.confidenceLevels')}</div>
              <div className="flex items-center gap-2" style={{ marginBottom: 4 }}>
                <span className="badge badge-success">{t('confidence.HIGH')}</span>
                <span className="text-muted">{t('settings.highDesc')}</span>
              </div>
              <div className="flex items-center gap-2" style={{ marginBottom: 4 }}>
                <span className="badge badge-warning">{t('confidence.MEDIUM')}</span>
                <span className="text-muted">{t('settings.mediumDesc')}</span>
              </div>
              <div className="flex items-center gap-2">
                <span className="badge badge-danger">{t('confidence.UNCERTAIN')}</span>
                <span className="text-muted">{t('settings.uncertainDesc', { pct: pctThreshold })}</span>
              </div>
            </div>

            <button className="btn btn-primary" type="submit" disabled={saving || draftThreshold === threshold}>
              {saving ? <><span className="spinner" /> {t('settings.saving')}</> : t('settings.saveThreshold')}
            </button>
          </form>
        </div>

        {/* Margin Card */}
        <div className="card" style={{ maxWidth: 480 }}>
          <p style={{ fontWeight: 600, marginBottom: 4 }}>{t('settings.marginTitle')}</p>
          <p className="text-muted" style={{ fontSize: 13, marginBottom: 20 }}>
            {t('settings.marginDesc')}
          </p>

          <form onSubmit={handleSaveMargin}>
            <div className="form-group">
              <label className="form-label">
                {t('settings.marginTitle')}: <strong>{pctMargin}%</strong>
                {' '}
                <span className="text-muted" style={{ fontSize: 11 }}>
                  {t('settings.currentValue', { pct: Math.round(margin * 100) })}
                </span>
              </label>
              <input
                type="range"
                min={0}
                max={20}
                step={1}
                value={pctMargin}
                onChange={e => setDraftMargin(Number(e.target.value) / 100)}
                style={{ width: '100%', accentColor: 'var(--primary)' }}
              />
              <div className="flex" style={{ justifyContent: 'space-between', marginTop: 4 }}>
                <span className="text-muted" style={{ fontSize: 11 }}>{t('settings.noMarginCheck')}</span>
                <span className="text-muted" style={{ fontSize: 11 }}>{t('settings.strictMargin')}</span>
              </div>
            </div>

            {/* Quick presets */}
            <div className="flex gap-2" style={{ marginBottom: 16, flexWrap: 'wrap' }}>
              {[0, 2, 3, 5, 10].map(v => (
                <button
                  key={v}
                  type="button"
                  className={`btn btn-sm ${draftMargin === v / 100 ? 'btn-primary' : 'btn-outline'}`}
                  onClick={() => setDraftMargin(v / 100)}
                >
                  {v}%
                </button>
              ))}
            </div>

            {/* Margin explainer */}
            <div style={{ background: 'var(--bg)', borderRadius: 6, padding: 12, marginBottom: 16, fontSize: 12 }}>
              <div style={{ marginBottom: 6, fontWeight: 600 }}>{t('settings.exampleScenarios')}</div>
              <div style={{ marginBottom: 8 }}>
                <div style={{ fontWeight: 500, marginBottom: 2 }}>{t('settings.marginExample', { pct: pctMargin })}</div>
                <div className="text-muted" style={{ marginBottom: 4 }}>
                  • Best: 85%, Second: 84% → Gap: 1% &lt; {pctMargin}% → <span className="badge badge-danger">{t('confidence.UNCERTAIN')}</span>
                </div>
                <div className="text-muted">
                  • Best: 85%, Second: 75% → Gap: 10% ≥ {pctMargin}% → <span className="badge badge-success">{t('matchResult.MATCH')}</span>
                </div>
              </div>
            </div>

            <button className="btn btn-primary" type="submit" disabled={saving || draftMargin === margin}>
              {saving ? <><span className="spinner" /> {t('settings.saving')}</> : t('settings.saveMargin')}
            </button>
          </form>
        </div>
      </div>
    </>
  )
}
