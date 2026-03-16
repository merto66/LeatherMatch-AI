import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import Layout from './components/Layout'
import MatchLayoutWrapper from './components/MatchLayoutWrapper'
import ProtectedRoute from './components/ProtectedRoute'
import ForbiddenPage from './pages/ForbiddenPage'
import LoginPage from './pages/LoginPage'
import MatchPage from './pages/MatchPage'
import PatternsPage from './pages/admin/PatternsPage'
import PatternDetailPage from './pages/admin/PatternDetailPage'
import FeedbackReviewPage from './pages/admin/FeedbackReviewPage'
import LogsPage from './pages/admin/LogsPage'
import SettingsPage from './pages/admin/SettingsPage'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        {/* Public */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/forbidden" element={<ForbiddenPage />} />

        {/*
          Protected routes: require login.
          /match: admin → Layout (sidebar), operator → OperatorLayout (no sidebar).
        */}
        <Route element={<ProtectedRoute />}>
          <Route element={<MatchLayoutWrapper />}>
            <Route path="/match" element={<MatchPage />} />
          </Route>
          <Route element={<Layout />}>
            <Route path="/admin/feedback" element={<FeedbackReviewPage />} />
            <Route path="/admin/patterns" element={<PatternsPage />} />
            <Route path="/admin/patterns/:id" element={<PatternDetailPage />} />
            <Route path="/admin/logs" element={<LogsPage />} />
            <Route path="/admin/settings" element={<SettingsPage />} />
          </Route>
        </Route>

        <Route path="/" element={<Navigate to="/match" replace />} />
        <Route path="*" element={<Navigate to="/match" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
