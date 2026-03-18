import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { apiClient, clearCredentials, setCredentials, setRole } from '../api/client'

export default function LoginPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError]       = useState('')
  const [loading, setLoading]   = useState(false)
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()

  async function handleSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)

    setCredentials(username, password)

    try {
      await apiClient.get('/admin/settings')
      setRole('admin')
      navigate('/admin/patterns')
    } catch (err: unknown) {
      const status = (err as { response?: { status?: number } })?.response?.status
      if (status === 403) {
        setRole('operator')
        navigate('/match')
      } else {
        setError(t('login.invalidCredentials'))
        clearCredentials()
      }
    } finally {
      setLoading(false)
    }
  }

  function toggleLang() {
    i18n.changeLanguage(i18n.language === 'tr' ? 'en' : 'tr')
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-title">LeatherMatch AI</div>
        <div className="login-subtitle">{t('login.subtitle')}</div>

        {error && <div className="alert alert-error">{error}</div>}

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label">{t('login.username')}</label>
            <input
              className="form-input"
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              autoFocus
              required
            />
          </div>
          <div className="form-group">
            <label className="form-label">{t('login.password')}</label>
            <input
              className="form-input"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
          </div>
          <button
            className="btn btn-primary"
            style={{ width: '100%', marginTop: 8, justifyContent: 'center' }}
            type="submit"
            disabled={loading}
          >
            {loading ? <span className="spinner" /> : t('login.signIn')}
          </button>
        </form>

        <div style={{ marginTop: 16, textAlign: 'center' }}>
          <button
            type="button"
            className="btn btn-outline btn-sm"
            onClick={toggleLang}
            style={{ fontSize: 12 }}
          >
            {i18n.language === 'tr' ? '🌐 English' : '🌐 Türkçe'}
          </button>
        </div>
      </div>
    </div>
  )
}
