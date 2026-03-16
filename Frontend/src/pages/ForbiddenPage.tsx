import { Link } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

/**
 * Shown when the user tries to access admin routes without permission
 * (e.g. operator hitting /admin/* once role-based access is in place).
 */
export default function ForbiddenPage() {
  const { t } = useTranslation()
  return (
    <div className="forbidden-page">
      <div className="forbidden-card">
        <h1 className="forbidden-title">403</h1>
        <p className="forbidden-message">{t('forbidden.message')}</p>
        <Link to="/match" className="btn btn-primary">
          {t('forbidden.backToMatch')}
        </Link>
      </div>
    </div>
  )
}
