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

export function fetchStoredFaces(deviceId) {
  return request(`/api/face/stored?deviceId=${encodeURIComponent(deviceId)}`)
}

export function fetchPeople() {
  return request('/api/person')
}

export function fetchVisitorHistory(limit = 30) {
  return request(`/api/event?limit=${encodeURIComponent(limit)}`)
}

export function createPerson(name) {
  return request('/api/person', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
}

export function deletePerson(id) {
  return request(`/api/person/${encodeURIComponent(id)}`, { method: 'DELETE' })
}

export async function detectAndStoreFace(deviceId, frame) {
  const form = new FormData()
  form.append('frame', frame, 'doorbell-frame.jpg')
  return request(`/api/face/detect?deviceId=${encodeURIComponent(deviceId)}&store=true`, {
    method: 'POST', body: form,
  })
}

export async function registerFace(name, frame, personId = null) {
  const form = new FormData()
  if (!personId) form.append('name', name)
  form.append('frame', frame, frame.name || 'registered-face.jpg')
  const path = personId
    ? `/api/face/register/${encodeURIComponent(personId)}`
    : '/api/face/register'
  return request(path, { method: 'POST', body: form })
}

export function recognizeFace(frame) {
  const form = new FormData()
  form.append('frame', frame, frame.name || 'visitor.jpg')
  return request('/api/face/recognize', { method: 'POST', body: form })
}

export function streamUrl(deviceId) {
  return `${apiBaseUrl}/stream/live/${encodeURIComponent(deviceId)}`
}
