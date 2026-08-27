const configuredBase = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export const apiBaseUrl = configuredBase.replace(/\/$/, '')

export function wsUrl() {
  const url = new URL('/ws', apiBaseUrl)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}

export async function request(path, options = {}) {
  const response = await fetch(`${apiBaseUrl}${path}`, options)
  const text = await response.text()
  let body = text
  try { body = text ? JSON.parse(text) : null } catch { /* preserve plain text */ }
  if (!response.ok) {
    const detail = typeof body === 'string' ? body : JSON.stringify(body)
    throw new Error(`HTTP ${response.status}: ${detail || response.statusText}`)
  }
  return { status: response.status, body }
}

export function fetchSummary(date) {
  return request(`/api/summary/${date}`)
}

export function streamUrl(deviceId) {
  return `${apiBaseUrl}/stream/live/${encodeURIComponent(deviceId)}`
}
