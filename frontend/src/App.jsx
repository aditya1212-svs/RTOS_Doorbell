import { useEffect, useMemo, useState } from 'react'
import { apiBaseUrl, fetchSummary, streamUrl } from './api/client'
import { createSocket } from './websocket/socket'

function App() {
  const [deviceId, setDeviceId] = useState('esp32-doorbell-01')
  const [socketStatus, setSocketStatus] = useState('connecting')
  const [notifications, setNotifications] = useState([])
  const [digests, setDigests] = useState([])
  const [activity, setActivity] = useState([])
  const [summaryDate, setSummaryDate] = useState(new Date().toISOString().slice(0, 10))
  const [summary, setSummary] = useState(null)
  const [summaryError, setSummaryError] = useState('')
  const [streamConnected, setStreamConnected] = useState(false)
  const liveUrl = useMemo(() => streamUrl(deviceId), [deviceId])

  useEffect(() => createSocket((kind, raw) => {
    let body
    try { body = JSON.parse(raw) } catch { body = raw }
    const item = { id: crypto.randomUUID(), time: new Date().toLocaleTimeString(), raw, body }
    if (kind === 'notification') setNotifications(items => [item, ...items].slice(0, 30))
    else setDigests(items => [item, ...items].slice(0, 10))
    setActivity(items => [{ ...item, kind: 'WebSocket', request: `/topic/${kind}`, result: body?.type || 'digest' }, ...items].slice(0, 100))
  }, setSocketStatus), [])

  async function getSummary() {
    setSummaryError('')
    try {
      const response = await fetchSummary(summaryDate)
      setSummary(response.body)
      setActivity(items => [{ id: crypto.randomUUID(), time: new Date().toLocaleTimeString(), kind: 'GET', request: `/api/summary/${summaryDate}`, result: `${response.status} OK`, detail: response.body }, ...items].slice(0, 100))
    } catch (error) {
      setSummaryError(error.message)
      setActivity(items => [{ id: crypto.randomUUID(), time: new Date().toLocaleTimeString(), kind: 'GET', request: `/api/summary/${summaryDate}`, result: error.message, error: true }, ...items].slice(0, 100))
    }
  }

  return <main>
    <header className="topbar">
      <div><p className="eyebrow">PHONE / CLIENT VIEW</p><h1>Smart Doorbell</h1></div>
      <div className="backend-status"><span className="dot green" /> Backend target <code>{apiBaseUrl}</code></div>
    </header>
    <section className="client-layout">
      <div className="column">
        <Panel title="Live Notifications" accent="blue">
          <Status label="Notification WebSocket" status={socketStatus} />
          {notifications.length ? notifications.map(item => <NotificationCard key={item.id} item={item} />) : <Empty text="Waiting for backend notifications..." />}
        </Panel>
        <Panel title="Daily Digest" accent="blue">
          <Status label="Digest WebSocket" status={socketStatus} />
          {digests.length ? <pre>{JSON.stringify(digests[0].body, null, 2)}</pre> : <Empty text="Waiting for the nightly digest..." />}
        </Panel>
      </div>
      <div className="column">
        <Panel title="Live Camera" accent="blue">
          <label>Device ID<input value={deviceId} onChange={e => { setDeviceId(e.target.value); setStreamConnected(false) }} /></label>
          <div className="stream-box">{streamConnected ? <img src={liveUrl} alt="Live doorbell stream" onError={() => setStreamConnected(false)} /> : <span>Waiting for stream connection...</span>}</div>
          <button onClick={() => setStreamConnected(true)} className="primary">Connect Stream</button>
          <button onClick={() => setStreamConnected(false)} className="secondary">Disconnect</button>
          <small>Frames must be supplied by the separate ESP32 simulator or physical device.</small>
        </Panel>
        <Panel title="Daily Summary">
          <div className="inline"><input type="date" value={summaryDate} onChange={e => setSummaryDate(e.target.value)} /><button onClick={getSummary} className="primary">Fetch Summary</button></div>
          {summaryError && <p className="error">{summaryError}</p>}
          {summary && <div className="summary"><h3>{summary.date || summaryDate}</h3><p>{summary.summary}</p><details><summary>Raw API response</summary><pre>{JSON.stringify(summary, null, 2)}</pre></details></div>}
          {!summary && !summaryError && <Empty text="No summary loaded" />}
        </Panel>
      </div>
    </section>
    <Panel title="Backend Activity" accent="purple">
      <div className="console-actions"><button onClick={() => setActivity([])} className="secondary">Clear Console</button><span className="muted">Read-only client activity and WebSocket messages.</span></div>
      <div className="activity">{activity.length ? activity.map(item => <details key={item.id} className={item.error ? 'activity-row failed' : 'activity-row'}><summary><time>{item.time}</time><b>{item.kind}</b><span>{item.result}</span><em>{item.request}</em></summary><pre>{JSON.stringify(item.detail || item.body, null, 2)}</pre></details>) : <Empty text="Backend messages will appear here." />}</div>
    </Panel>
  </main>
}

function Panel({ title, accent, children }) { return <section className={`panel ${accent}`}><h2>{title}</h2>{children}</section> }
function Status({ label, status }) { return <p className="status"><span className={`dot ${status === 'connected' ? 'green' : 'red'}`} />{label}: <strong>{status}</strong></p> }
function Empty({ text }) { return <p className="empty">{text}</p> }
function NotificationCard({ item }) {
  const type = item.body?.type || 'MESSAGE'
  return <article className={`notification ${type.includes('UNKNOWN') ? 'unknown' : ''}`}><strong>{type.replaceAll('_', ' ')}</strong><p>{item.body?.message || item.raw}</p><time>{item.time}</time></article>
}

export default App
