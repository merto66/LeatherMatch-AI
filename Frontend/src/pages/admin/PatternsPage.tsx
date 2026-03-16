import { FormEvent, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { createPattern, deletePattern, getAdminPatterns, PatternDto } from '../../api/client'

export default function PatternsPage() {
  const [patterns, setPatterns] = useState<PatternDto[]>([])
  const [newCode, setNewCode]   = useState('')
  const [loading, setLoading]   = useState(true)
  const [creating, setCreating] = useState(false)
  const [error, setError]       = useState('')
  const [success, setSuccess]   = useState('')
  const { t } = useTranslation()

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      setPatterns(await getAdminPatterns())
    } catch {
      setError(t('patterns.failedToLoad'))
    } finally {
      setLoading(false)
    }
  }

  async function handleCreate(e: FormEvent) {
    e.preventDefault()
    if (!newCode.trim()) return
    setCreating(true)
    setError('')
    setSuccess('')
    try {
      const p = await createPattern(newCode.trim())
      setPatterns(prev => [...prev, p])
      setNewCode('')
      setSuccess(t('patterns.created_success', { code: p.code }))
    } catch (err: unknown) {
      const msg = (err as { response?: { data?: { message?: string } } })
        ?.response?.data?.message ?? t('patterns.failedToCreate')
      setError(msg)
    } finally {
      setCreating(false)
    }
  }

  async function handleDelete(pattern: PatternDto) {
    if (!confirm(t('patterns.confirmDelete', { code: pattern.code, count: pattern.referenceCount }))) return
    try {
      await deletePattern(pattern.id)
      setPatterns(prev => prev.filter(p => p.id !== pattern.id))
      setSuccess(t('patterns.deleted_success', { code: pattern.code }))
    } catch {
      setError(t('patterns.failedToDelete'))
    }
  }

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">{t('patterns.title')}</h1>
      </div>

      <div className="page-body">
        {error   && <div className="alert alert-error">{error}</div>}
        {success && <div className="alert alert-success">{success}</div>}

        {/* Create form */}
        <div className="card mb-4" style={{ marginBottom: 20 }}>
          <p style={{ fontWeight: 600, marginBottom: 12 }}>{t('patterns.addNew')}</p>
          <form onSubmit={handleCreate} className="flex gap-2" style={{ alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <input
                className="form-input"
                placeholder={t('patterns.patternCode')}
                value={newCode}
                onChange={e => setNewCode(e.target.value)}
                required
              />
            </div>
            <button className="btn btn-primary" type="submit" disabled={creating}>
              {creating ? <span className="spinner" /> : t('patterns.addPattern')}
            </button>
          </form>
        </div>

        {/* Table */}
        <div className="card">
          {loading ? (
            <div className="empty-state"><span className="spinner" /></div>
          ) : patterns.length === 0 ? (
            <div className="empty-state">{t('patterns.noPatterns')}</div>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>{t('patterns.code')}</th>
                    <th>{t('patterns.references')}</th>
                    <th>{t('patterns.created')}</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {patterns.map(p => (
                    <tr key={p.id}>
                      <td>
                        <Link
                          to={`/admin/patterns/${p.id}`}
                          style={{ color: 'var(--primary)', fontWeight: 600 }}
                        >
                          {p.code}
                        </Link>
                      </td>
                      <td>
                        <span className="badge badge-info">{p.referenceCount}</span>
                      </td>
                      <td className="text-muted">{p.createdAt.slice(0, 19).replace('T', ' ')}</td>
                      <td>
                        <div className="flex gap-2">
                          <Link
                            to={`/admin/patterns/${p.id}`}
                            className="btn btn-outline btn-sm"
                          >
                            {t('patterns.manageRefs')}
                          </Link>
                          <button
                            className="btn btn-danger btn-sm"
                            onClick={() => handleDelete(p)}
                          >
                            {t('patterns.delete')}
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </>
  )
}
