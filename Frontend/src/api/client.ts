import axios from 'axios'

/**
 * Axios instance that automatically injects the Basic Auth header
 * from credentials stored in sessionStorage.
 *
 * Credentials are set by the login page via setCredentials()
 * and cleared on logout via clearCredentials().
 */
export const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30_000,
})

const STORAGE_KEY = 'leathermatch_auth'
const ROLE_KEY = 'leathermatch_role'

export function setCredentials(username: string, password: string): void {
  // btoa() only handles ASCII (Latin1). For non-ASCII characters in username
  // or password, encodeURIComponent + unescape gives correct Latin1 bytes.
  const encoded = btoa(unescape(encodeURIComponent(`${username}:${password}`)))
  sessionStorage.setItem(STORAGE_KEY, encoded)
}

export function clearCredentials(): void {
  sessionStorage.removeItem(STORAGE_KEY)
  sessionStorage.removeItem(ROLE_KEY)
}

export function hasCredentials(): boolean {
  return !!sessionStorage.getItem(STORAGE_KEY)
}

export function setRole(role: 'admin' | 'operator'): void {
  sessionStorage.setItem(ROLE_KEY, role)
}

export function getRole(): 'admin' | 'operator' | null {
  const r = sessionStorage.getItem(ROLE_KEY)
  return (r === 'admin' || r === 'operator') ? r : null
}

// Attach Authorization header before every request
apiClient.interceptors.request.use((config) => {
  const encoded = sessionStorage.getItem(STORAGE_KEY)
  if (encoded) {
    config.headers['Authorization'] = `Basic ${encoded}`
  }
  return config
})

// -------------------------------------------------------------------------
// Typed API helpers
// -------------------------------------------------------------------------

export interface PatternDto {
  id: number
  code: string
  createdAt: string
  referenceCount: number
  thumbnailReferenceId?: number | null
}

export interface ReferenceImageDto {
  id: number
  patternId: number
  patternCode: string
  imagePath: string
  embeddingDim: number
  createdAt: string
}

export interface SettingsDto {
  threshold: number
  margin?: number
}

export interface MatchResult {
  patternCode: string
  similarityScore: number
  isMatch: boolean
  confidence: string
  processingTimeMs: number
  threshold: number
  allPatternScores: Record<string, number>
  secondBestScore?: number
  marginUsed?: number
}

export interface FeedbackPayload {
  predictedPattern: string
  predictedScore: number
  threshold: number
  margin: number
  operatorSelectedPattern: string
  note?: string
  secondBestScore?: number
}

export interface FeedbackEntry {
  id: number
  createdAt: string
  uploadedImagePath: string
  predictedPattern: string
  predictedScore: number
  secondBestScore?: number
  threshold: number
  margin: number
  operatorSelectedPattern: string
  note: string
  status: string
  reviewedAt?: string
  reviewedBy?: string
}

export interface FeedbackListResponse {
  total: number
  limit: number
  offset: number
  data: FeedbackEntry[]
}

export interface LogEntry {
  id: number
  createdAt: string
  predictedPattern: string
  similarityScore: number
  threshold: number
  isMatch: boolean
  confidence: string
  processingTimeMs: number
}

export interface LogsResponse {
  total: number
  limit: number
  offset: number
  data: LogEntry[]
}

// Patterns
export const getAdminPatterns = () =>
  apiClient.get<PatternDto[]>('/admin/patterns').then(r => r.data)

export const createPattern = (code: string) =>
  apiClient.post<PatternDto>('/admin/patterns', { code }).then(r => r.data)

export const deletePattern = (id: number) =>
  apiClient.delete(`/admin/patterns/${id}`).then(r => r.data)

// References
export const getReferences = (patternId: number) =>
  apiClient.get<ReferenceImageDto[]>(`/admin/patterns/${patternId}/references`).then(r => r.data)

export const uploadReferences = (patternId: number, files: File[]) => {
  const form = new FormData()
  files.forEach(f => form.append('files', f))
  return apiClient
    .post<ReferenceImageDto[]>(`/admin/patterns/${patternId}/references`, form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then(r => r.data)
}

export const deleteReference = (referenceId: number) =>
  apiClient.delete(`/admin/references/${referenceId}`).then(r => r.data)

export const setThumbnailReference = (patternId: number, referenceId: number) =>
  apiClient.put<{ thumbnailReferenceId: number }>(`/admin/patterns/${patternId}/thumbnail-reference`, { referenceId }).then(r => r.data)

export interface ImportResult {
  imported: number
  skipped: number
  errors?: string[]
}

export const importFromDisk = (patternId: number) =>
  apiClient.post<ImportResult>(`/admin/patterns/${patternId}/import-from-disk`).then(r => r.data)

// Settings
export const getSettings = () =>
  apiClient.get<SettingsDto>('/admin/settings').then(r => r.data)

export const updateThreshold = (threshold: number) =>
  apiClient.put<SettingsDto>('/admin/settings/threshold', { threshold }).then(r => r.data)

export const updateMargin = (margin: number) =>
  apiClient.put<SettingsDto>('/admin/settings/margin', { margin }).then(r => r.data)

// Logs
export const getLogs = (params: {
  limit?: number
  offset?: number
  pattern?: string
  isMatch?: boolean
}) => apiClient.get<LogsResponse>('/admin/logs', { params }).then(r => r.data)

// Patterns (public, for pattern picker in feedback form)
export const getPatterns = () =>
  apiClient.get<string[]>('/patterns').then(r => r.data)

/** Returns the URL path for the pattern thumbnail endpoint (used with fetch + auth). */
export function getPatternThumbnailUrl(code: string): string {
  return `/api/patterns/${encodeURIComponent(code)}/thumbnail`
}

/**
 * Per-pattern cache-bust version counter.
 * Incremented only when the admin explicitly sets a new thumbnail
 * (via invalidatePatternThumbnail).  This gives a stable URL for HTTP
 * caching while still allowing manual invalidation.
 */
const thumbnailVersions = new Map<string, number>()

/** Returns a stable URL for the pattern thumbnail, with a version param
 *  that only changes after invalidatePatternThumbnail() is called. */
export function getPatternThumbnailCacheUrl(code: string): string {
  const v = thumbnailVersions.get(code) ?? 0
  return getPatternThumbnailUrl(code) + (v > 0 ? `?v=${v}` : '')
}

/**
 * Invalidate the cached thumbnail for a specific pattern.
 * Call this after the admin sets a new thumbnail so the next request
 * fetches the updated image.  Does NOT revoke any existing blob URLs —
 * callers should call imageCache.invalidate() with the old URL first.
 */
export function invalidatePatternThumbnail(code: string): void {
  const current = thumbnailVersions.get(code) ?? 0
  thumbnailVersions.set(code, current + 1)
}

/** Fetches thumbnail as blob; returns object URL or null on 404/error. */
export async function fetchPatternThumbnail(code: string): Promise<string | null> {
  const url = getPatternThumbnailCacheUrl(code)
  try {
    const res = await apiClient.get(url, { responseType: 'blob' })
    return URL.createObjectURL(res.data as Blob)
  } catch {
    return null
  }
}

// Match (uses apiClient so auth is sent; backend requires auth for /api/match)
export const matchImage = (file: File) => {
  const form = new FormData()
  form.append('file', file)
  return apiClient
    .post<MatchResult>('/match', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then(r => r.data)
}

// Feedback (operator submits correction; requires auth)
export const submitFeedback = (file: File, payload: FeedbackPayload) => {
  const form = new FormData()
  form.append('file', file)
  form.append('predictedPattern', payload.predictedPattern)
  form.append('predictedScore', String(payload.predictedScore))
  form.append('threshold', String(payload.threshold))
  form.append('margin', String(payload.margin))
  form.append('operatorSelectedPattern', payload.operatorSelectedPattern)
  if (payload.note) form.append('note', payload.note)
  if (payload.secondBestScore != null) form.append('secondBestScore', String(payload.secondBestScore))
  return apiClient
    .post<{ id: number; status: string; message: string }>('/feedback', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then(r => r.data)
}

// Admin feedback review
export const getFeedbackList = (params: {
  status?: string
  limit?: number
  offset?: number
}) => apiClient.get<FeedbackListResponse>('/admin/feedback', { params }).then(r => r.data)

export const approveFeedback = (id: number) =>
  apiClient.post<{ status: string; id: number }>(`/admin/feedback/${id}/approve`).then(r => r.data)

export const rejectFeedback = (id: number) =>
  apiClient.post<{ status: string; id: number }>(`/admin/feedback/${id}/reject`).then(r => r.data)

export const approveAndAddReference = (id: number) =>
  apiClient.post<{ status: string; id: number; addedAsReference: boolean; patternCode: string }>(
    `/admin/feedback/${id}/approve-and-add-reference`
  ).then(r => r.data)
