import { useLocation, Navigate, Outlet } from 'react-router-dom'
import { getRole, hasCredentials } from '../api/client'

/**
 * Wraps routes that require authentication.
 * Operators cannot access /admin/* (redirect to 403).
 */
export default function ProtectedRoute() {
  const location = useLocation()
  if (!hasCredentials()) {
    if (location.pathname.startsWith('/admin')) {
      return <Navigate to="/forbidden" replace />
    }
    return <Navigate to="/login" replace />
  }
  if (location.pathname.startsWith('/admin') && getRole() === 'operator') {
    return <Navigate to="/forbidden" replace />
  }
  return <Outlet />
}
