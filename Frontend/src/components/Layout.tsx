import { useEffect, useState } from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { clearCredentials } from '../api/client'

function closeDrawer(setOpen: (v: boolean) => void) {
  return () => setOpen(false)
}

export default function Layout() {
  const navigate = useNavigate()
  const [isDrawerOpen, setDrawerOpen] = useState(false)
  const { t, i18n } = useTranslation()

  useEffect(() => {
    if (!isDrawerOpen) return
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') setDrawerOpen(false)
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [isDrawerOpen])

  function logout() {
    clearCredentials()
    navigate('/login')
  }

  function toggleLang() {
    i18n.changeLanguage(i18n.language === 'tr' ? 'en' : 'tr')
  }

  const navContent = (
    <>
      <NavLink to="/match" onClick={closeDrawer(setDrawerOpen)}>
        &#9654; {t('nav.match')}
      </NavLink>
      <div className="sidebar-section">{t('nav.admin')}</div>
      <NavLink to="/admin/feedback" onClick={closeDrawer(setDrawerOpen)}>
        &#9998; {t('nav.reviewQueue')}
      </NavLink>
      <NavLink to="/admin/patterns" onClick={closeDrawer(setDrawerOpen)}>
        &#9776; {t('nav.patterns')}
      </NavLink>
      <NavLink to="/admin/logs" onClick={closeDrawer(setDrawerOpen)}>
        &#9881; {t('nav.logs')}
      </NavLink>
      <NavLink to="/admin/settings" onClick={closeDrawer(setDrawerOpen)}>
        &#9965; {t('nav.settings')}
      </NavLink>
    </>
  )

  const footerContent = (
    <div className="sidebar-footer">
      <button
        type="button"
        className="btn btn-outline btn-sm sidebar-lang-toggle"
        onClick={toggleLang}
        title={i18n.language === 'tr' ? 'Switch to English' : 'Türkçeye geç'}
      >
        {i18n.language === 'tr' ? '🌐 EN' : '🌐 TR'}
      </button>
      <button
        type="button"
        className="btn btn-outline btn-sm sidebar-logout-btn"
        onClick={logout}
      >
        {t('nav.logout')}
      </button>
    </div>
  )

  return (
    <div className="layout">
      <header className="layout-header">
        <button
          type="button"
          className="hamburger"
          onClick={() => setDrawerOpen(true)}
          aria-label="Open menu"
        >
          <span className="hamburger-bar" />
          <span className="hamburger-bar" />
          <span className="hamburger-bar" />
        </button>
        <div className="layout-header-logo">
          Leather<span>Match</span> AI
        </div>
      </header>

      {isDrawerOpen && (
        <>
          <div
            className="drawer-backdrop"
            onClick={() => setDrawerOpen(false)}
            aria-hidden
          />
          <aside className="drawer">
            <div className="drawer-logo">
              Leather<span>Match</span> AI
            </div>
            <nav className="drawer-nav">
              {navContent}
            </nav>
            <div className="drawer-footer">
              <button
                type="button"
                className="btn btn-outline btn-sm drawer-lang-toggle"
                onClick={toggleLang}
                title={i18n.language === 'tr' ? 'Switch to English' : 'Türkçeye geç'}
              >
                {i18n.language === 'tr' ? '🌐 EN' : '🌐 TR'}
              </button>
              <button
                type="button"
                className="btn btn-outline btn-sm drawer-logout"
                onClick={logout}
              >
                {t('nav.logout')}
              </button>
            </div>
          </aside>
        </>
      )}

      <aside className="sidebar">
        <div className="sidebar-logo">
          Leather<span>Match</span> AI
        </div>
        <nav>
          {navContent}
        </nav>
        {footerContent}
      </aside>

      <main className="main-content">
        <Outlet />
      </main>
    </div>
  )
}
