import { Outlet, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { clearCredentials } from '../api/client'

/**
 * Layout for the operator (match-only) UI.
 * No admin navigation — only match workflow and logout.
 */
export default function OperatorLayout() {
  const navigate = useNavigate()
  const { t, i18n } = useTranslation()

  function logout() {
    clearCredentials()
    navigate('/login')
  }

  function toggleLang() {
    i18n.changeLanguage(i18n.language === 'tr' ? 'en' : 'tr')
  }

  return (
    <div className="operator-layout">
      <header className="operator-header">
        <div className="operator-logo">Leather<span>Match</span> AI</div>
        <div className="operator-header-actions">
          <button
            type="button"
            className="btn btn-outline btn-sm operator-lang-toggle"
            onClick={toggleLang}
            title={i18n.language === 'tr' ? 'Switch to English' : 'Türkçeye geç'}
          >
            {i18n.language === 'tr' ? '🌐 EN' : '🌐 TR'}
          </button>
          <button
            type="button"
            className="btn btn-outline btn-sm operator-logout"
            onClick={logout}
          >
            {t('nav.logout')}
          </button>
        </div>
      </header>
      <main className="operator-main">
        <Outlet />
      </main>
    </div>
  )
}
