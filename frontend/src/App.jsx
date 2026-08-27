import { useEffect, useMemo, useRef, useState } from 'react'
import { apiBaseUrl, fetchSummary } from './api/client'
import { createSocket } from './websocket/socket'
import { AudioService } from './services/audioService'
import { WebRtcReceiver } from './services/webrtcService'

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
  const [audioStatus, setAudioStatus] = useState('disconnected')
  const [videoStatus, setVideoStatus] = useState('disconnected')
  const audio = useMemo(() => new AudioService(), [])
  const video = useMemo(() => new WebRtcReceiver(), [])
  const videoElement = useRef(null)

  useEffect(() => createSocket((kind, raw) => {
    let body
    try { body = JSON.parse(raw) } catch { body = raw }
    const item = { id: crypto.randomUUID(), time: new Date().toLocaleTimeString(), raw, body }
    if (kind === 'notification') setNotifications(items => [item, ...items].slice(0, 30))
    else setDigests(items => [item, ...items].slice(0, 10))
    setActivity(items => [{ ...item, kind: 'WebSocket', request: `/topic/${kind}`, result: body?.type || 'digest' }, ...items].slice(0, 100))
  }, setSocketStatus), [])
  useEffect(() => () => { audio.stop(); video.stop() }, [audio, video])

  async function toggleAudio() {
    if (audioStatus === 'connected') {
      audio.stop()
      setAudioStatus('disconnected')
      return
    }
    try { await audio.start(deviceId, setAudioStatus) }
    catch (error) { setAudioStatus(`error: ${error.message}`) }
  }
  async function connectVideo() {
    try {
      await video.start(deviceId, videoElement.current, status => {
        setVideoStatus(status)
        if (status === 'connected') setStreamConnected(true)
      })
    } catch (error) { setVideoStatus(`error: ${error.message}`) }
  }

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
          <div className="stream-box"><video ref={videoElement} autoPlay playsInline style={{ display: streamConnected ? 'block' : 'none' }} />{!streamConnected && <span>Waiting for WebRTC connection...</span>}</div>
          <Status label="WebRTC video" status={videoStatus} />
          <button onClick={connectVideo} className="primary">Connect WebRTC Video</button>
          <button onClick={() => { video.stop(); setStreamConnected(false); setVideoStatus('disconnected') }} className="secondary">Disconnect</button>
          <small>Video is published by the separate ESP32 simulator through the backend WebRTC signaling relay.</small>
        </Panel>
        <Panel title="Daily Summary">
          <div className="inline"><input type="date" value={summaryDate} onChange={e => setSummaryDate(e.target.value)} /><button onClick={getSummary} className="primary">Fetch Summary</button></div>
          {summaryError && <p className="error">{summaryError}</p>}
          {summary && <div className="summary"><h3>{summary.date || summaryDate}</h3><p>{summary.summary}</p><details><summary>Raw API response</summary><pre>{JSON.stringify(summary, null, 2)}</pre></details></div>}
          {!summary && !summaryError && <Empty text="No summary loaded" />}
        </Panel>
        <Panel title="Two-way Audio" accent="blue">
          <Status label="Audio WebSocket" status={audioStatus} />
          <button onClick={toggleAudio} className="primary">{audioStatus === 'connected' ? 'Stop Microphone' : 'Start Two-way Audio'}</button>
          <small>Open the ESP32 simulator and this client with the same device ID.</small>
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
