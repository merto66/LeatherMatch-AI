import { getRole } from '../api/client'
import Layout from './Layout'
import OperatorLayout from './OperatorLayout'

/**
 * Admin: Match inside Layout (sidebar). Operator: Match inside OperatorLayout (no sidebar).
 */
export default function MatchLayoutWrapper() {
  return getRole() === 'admin' ? <Layout /> : <OperatorLayout />
}
