import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { getLogs, LogEntry } from '../../api/client'

const PAGE_SIZE = 50

export default function LogsPage() {
  const [logs, setLogs]           = useState<LogEntry[]>([])
  const [total, setTotal]         = useState(0)
  const [offset, setOffset]       = useState(0)
  const [loading, setLoading]     = useState(true)
  const [error, setError]         = useState('')
  const { t } = useTranslation()

  const [filterPattern, setFilterPattern] = useState('')
  const [filterMatch, setFilterMatch]     = useState<'all' | 'true' | 'false'>('all')

  useEffect(() => {
    setOffset(0)
  }, [filterPattern, filterMatch])

  useEffect(() => {
    load()
  }, [offset, filterPattern, filterMatch])

  async function load() {
    setLoading(true)
    setError('')
    try {
      const params: { limit: number; offset: number; pattern?: string; isMatch?: boolean } = {
        limit: PAGE_SIZE,
        offset,
      }
      if (filterPattern.trim()) params.pattern = filterPattern.trim()
      if (filterMatch === 'true')  params.isMatch = true
      if (filterMatch === 'false') params.isMatch = false

      const data = await getLogs(params)
      setLogs(data.data)
      setTotal(data.total)
    } catch {
      setError(t('logs.failedToLoad'))
    } finally {
      setLoading(false)
    }
  }

  function confidenceBadge(c: string) {
    if (c === 'HIGH')   return 'badge badge-success'
    if (c === 'MEDIUM') return 'badge badge-warning'
    return 'badge badge-danger'
  }

  const totalPages = Math.ceil(total / PAGE_SIZE)
  const currentPage = Math.floor(offset / PAGE_SIZE) + 1

  return (
    <>
      <div className="page-header">
        <h1 className="page-title">{t('logs.title')}</h1>
        <span className="text-muted" style={{ fontSize: 13 }}>{t('logs.total', { count: total })}</span>
      </div>

      <div className="page-body">
        {error && <div className="alert alert-error">{error}</div>}

        {/* Filters */}
        <div className="card mb-4" style={{ marginBottom: 16 }}>
          <div className="flex gap-3" style={{ alignItems: 'flex-end', flexWrap: 'wrap' }}>
            <div>
              <label className="form-label">{t('logs.filterByPattern')}</label>
              <input
                className="form-input"
                style={{ width: 180 }}
                placeholder="e.g. PB-363"
                value={filterPattern}
                onChange={e => setFilterPattern(e.target.value)}
              />
            </div>
            <div>
              <label className="form-label">{t('logs.matchResult')}</label>
              <select
                className="form-input"
                style={{ width: 140 }}
                value={filterMatch}
                onChange={e => setFilterMatch(e.target.value as 'all' | 'true' | 'false')}
              >
                <option value="all">{t('logs.all')}</option>
                <option value="true">{t('logs.match')}</option>
                <option value="false">{t('logs.noMatch')}</option>
              </select>
            </div>
            <button className="btn btn-outline" onClick={() => { setFilterPattern(''); setFilterMatch('all') }}>
              {t('logs.clear')}
            </button>
          </div>
        </div>

        {/* Table */}
        <div className="card">
          {loading ? (
            <div className="empty-state"><span className="spinner" /></div>
          ) : logs.length === 0 ? (
            <div className="empty-state">{t('logs.noLogs')}</div>
          ) : (
            <>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>{t('logs.time')}</th>
                      <th>{t('logs.pattern')}</th>
                      <th>{t('logs.score')}</th>
                      <th>{t('logs.threshold')}</th>
                      <th>{t('logs.result')}</th>
                      <th>{t('logs.confidence')}</th>
                      <th>{t('logs.ms')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logs.map(l => (
                      <tr key={l.id}>
                        <td className="text-muted" style={{ whiteSpace: 'nowrap' }}>
                          {l.createdAt.slice(0, 19).replace('T', ' ')}
                        </td>
                        <td style={{ fontWeight: 600 }}>{l.predictedPattern}</td>
                        <td>{(l.similarityScore * 100).toFixed(1)}%</td>
                        <td className="text-muted">{(l.threshold * 100).toFixed(0)}%</td>
                        <td>
                          {l.isMatch
                            ? <span className="badge badge-success">{t('matchResult.MATCH')}</span>
                            : <span className="badge badge-danger">{t('matchResult.NO_MATCH')}</span>}
                        </td>
                        <td>
                          <span className={confidenceBadge(l.confidence)}>{t(`confidence.${l.confidence}`, l.confidence)}</span>
                        </td>
                        <td className="text-muted">{l.processingTimeMs}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Pagination */}
              {totalPages > 1 && (
                <div className="flex gap-2 items-center mt-2" style={{ marginTop: 16 }}>
                  <button
                    className="btn btn-outline btn-sm"
                    disabled={offset === 0}
                    onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}
                  >
                    {t('logs.prev')}
                  </button>
                  <span className="text-muted" style={{ fontSize: 12 }}>
                    {t('logs.page', { current: currentPage, total: totalPages })}
                  </span>
                  <button
                    className="btn btn-outline btn-sm"
                    disabled={offset + PAGE_SIZE >= total}
                    onClick={() => setOffset(offset + PAGE_SIZE)}
                  >
                    {t('logs.next')}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      </div>
    </>
  )
}
