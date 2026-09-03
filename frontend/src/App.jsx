import { useEffect, useMemo, useRef, useState } from 'react'
import { apiBaseUrl, deletePerson, detectAndStoreFace, fetchPeople, fetchStoredFaces, fetchSummary, fetchVisitorHistory, registerFace } from './api/client'
import { createSocket } from './websocket/socket'
import { AudioService } from './services/audioService'
import { WebRtcReceiver } from './services/webrtcService'

function App() {
  const [deviceId, setDeviceId] = useState('esp32-doorbell-01')
  const [socketStatus, setSocketStatus] = useState('connecting')
  const [notifications, setNotifications] = useState([])
  const [unreadNotifications, setUnreadNotifications] = useState(0)
  const [activeTab, setActiveTab] = useState('dashboard')
  const [digests, setDigests] = useState([])
  const [activity, setActivity] = useState([])
  const [summaryDate, setSummaryDate] = useState(new Date().toISOString().slice(0, 10))
  const [summary, setSummary] = useState(null)
  const [summaryError, setSummaryError] = useState('')
  const [streamConnected, setStreamConnected] = useState(false)
  const [audioStatus, setAudioStatus] = useState('disconnected')
  const [videoStatus, setVideoStatus] = useState('disconnected')
  const [faceDetection, setFaceDetection] = useState(null)
  const [storedFaces, setStoredFaces] = useState([])
  const [faceError, setFaceError] = useState('')
  const [people, setPeople] = useState([])
  const [personName, setPersonName] = useState('')
  const [registrationFile, setRegistrationFile] = useState(null)
  const [peopleError, setPeopleError] = useState('')
  const [visitorHistory, setVisitorHistory] = useState([])
  const [historyError, setHistoryError] = useState('')
  const audio = useMemo(() => new AudioService(), [])
  const video = useMemo(() => new WebRtcReceiver(), [])
  const videoElement = useRef(null)

  useEffect(() => createSocket(deviceId, (kind, raw) => {
    let body
    try { body = JSON.parse(raw) } catch { body = raw }
    const item = { id: crypto.randomUUID(), time: new Date().toLocaleTimeString(), raw, body, unread: true }
    if (kind === 'notification') {
      setNotifications(items => [item, ...items].slice(0, 30))
      setUnreadNotifications(count => count + 1)
      loadVisitorHistory()
    }
    else if (kind === 'face') {
      setFaceDetection(body)
      if (body?.storedFaces?.length) setStoredFaces(items => mergeStoredFaces(items, body.storedFaces))
    } else setDigests(items => [item, ...items].slice(0, 10))
    setActivity(items => [{ ...item, kind: 'WebSocket', request: `/topic/${kind}`, result: body?.type || 'digest' }, ...items].slice(0, 100))
  }, setSocketStatus), [deviceId])
  useEffect(() => () => { audio.stop(); video.stop() }, [audio, video])
  useEffect(() => {
    if (activeTab === 'dashboard' && videoElement.current && video.stream) {
      videoElement.current.srcObject = video.stream
      videoElement.current.play().catch(() => {})
    }
  }, [activeTab, video])
  useEffect(() => { loadPeople(); loadVisitorHistory() }, [])

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
  async function detectWithRetry(deviceId, frame, attempts = 3) {
    // The backend throttles detection globally; a click can race with the simulator's
    // sampled frames and get a 429. Wait briefly and retry before surfacing an error.
    for (let attempt = 1; ; attempt += 1) {
      try { return await detectAndStoreFace(deviceId, frame) }
      catch (error) {
        if (attempt >= attempts || !String(error.message).includes('429')) throw error
        await new Promise(resolve => setTimeout(resolve, 300))
      }
    }
  }
  async function storeCurrentFace() {
    setFaceError('')
    const currentVideo = videoElement.current
    if (!currentVideo?.videoWidth) {
      setFaceError('Connect the camera stream before storing a face.')
      return
    }
    const canvas = document.createElement('canvas')
    canvas.width = currentVideo.videoWidth
    canvas.height = currentVideo.videoHeight
    canvas.getContext('2d').drawImage(currentVideo, 0, 0)
    const frame = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', .88))
    if (!frame) {
      setFaceError('Unable to capture the current video frame.')
      return
    }
    try {
      const response = await detectWithRetry(deviceId, frame)
      setFaceDetection(response.body)
      setStoredFaces(items => mergeStoredFaces(items, response.body.storedFaces || []))
      setActivity(items => [{ id: crypto.randomUUID(), time: new Date().toLocaleTimeString(), kind: 'POST', request: '/api/face/detect?store=true', result: `${response.status} OK`, detail: response.body }, ...items].slice(0, 100))
    } catch (error) { setFaceError(error.message) }
  }
  async function loadStoredFaces() {
    setFaceError('')
    try { setStoredFaces((await fetchStoredFaces(deviceId)).body) }
    catch (error) { setFaceError(error.message) }
  }

  async function loadPeople() {
    setPeopleError('')
    try { setPeople((await fetchPeople()).body || []) }
    catch (error) { setPeopleError(error.message) }
  }

  async function loadVisitorHistory() {
    setHistoryError('')
    try { setVisitorHistory((await fetchVisitorHistory()).body || []) }
    catch (error) { setHistoryError(error.message) }
  }

  async function registerPersonFace() {
    setPeopleError('')
    const name = personName.trim()
    if (!name) {
      setPeopleError('Enter a person name first.')
      return
    }
    let frame = registrationFile
    if (!frame) {
      if (!faceDetection?.faces?.length) {
        setPeopleError('Wait for a detected face or choose a registration image.')
        return
      }
      const currentVideo = videoElement.current
      if (!currentVideo?.videoWidth) {
        setPeopleError('Choose an image or connect the camera stream first.')
        return
      }
      const canvas = document.createElement('canvas')
      canvas.width = currentVideo.videoWidth
      canvas.height = currentVideo.videoHeight
      canvas.getContext('2d').drawImage(currentVideo, 0, 0)
      frame = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', .9))
    }
    if (!frame) {
      setPeopleError('Unable to read the registration image.')
      return
    }
    try {
      const response = await registerFace(name, frame)
      setPeople(items => mergePeople(items, response.body.person))
      setPersonName('')
      setRegistrationFile(null)
      setPeopleError(`Registered ${response.body.person.name} (${response.body.person.samples} sample${response.body.person.samples === 1 ? '' : 's'}).`)
    } catch (error) { setPeopleError(error.message) }
  }

  async function removePerson(person) {
    if (!window.confirm(`Delete ${person.name} and all of their face samples?`)) return
    setPeopleError('')
    try {
      await deletePerson(person.id)
      setPeople(items => items.filter(item => item.id !== person.id))
    } catch (error) { setPeopleError(error.message) }
  }

  return <main>
    <header className="topbar">
      <div><p className="eyebrow">PHONE / CLIENT VIEW</p><h1>Smart Doorbell</h1></div>
      <div className="backend-status"><span className="dot green" /> Backend target <code>{apiBaseUrl}</code></div>
    </header>
    <nav className="tabs" aria-label="Primary navigation">
      <button className={activeTab === 'dashboard' ? 'tab active' : 'tab'} onClick={() => setActiveTab('dashboard')}>Dashboard</button>
      <button className={activeTab === 'notifications' ? 'tab active' : 'tab'} onClick={() => { setActiveTab('notifications'); setUnreadNotifications(0); setNotifications(items => items.map(item => ({ ...item, unread: false }))) }}>
        Notifications {unreadNotifications > 0 && <span className="tab-badge">{unreadNotifications}</span>}
      </button>
      <button className={activeTab === 'events' ? 'tab active' : 'tab'} onClick={() => { setActiveTab('events'); loadVisitorHistory() }}>Recent Events</button>
    </nav>
    {activeTab === 'notifications' && <Panel title="Notifications" accent="blue">
      <div className="panel-actions"><Status label="Notification WebSocket" status={socketStatus} /><button onClick={() => setNotifications([])} className="secondary" disabled={!notifications.length}>Clear</button></div>
      {notifications.length ? notifications.map(item => <NotificationCard key={item.id} item={item} unread={item.unread} />) : <Empty text="No notifications yet." />}
    </Panel>}
    {activeTab === 'events' && <Panel title="Recent Events" accent="purple">
      <button onClick={loadVisitorHistory} className="secondary">Refresh Events</button>
      {historyError && <p className="error">{historyError}</p>}
      {visitorHistory.length ? <div className="history-list">{visitorHistory.map(event => <article key={event.id} className={event.type === 'RECOGNIZED' ? 'recognized' : event.type === 'UNKNOWN' ? 'unknown-event' : ''}><strong>{event.recognizedName || (event.type === 'UNKNOWN' ? 'Unknown person' : event.type)}</strong><span>{event.type} · {new Date(event.timestamp).toLocaleString()}</span></article>)}</div> : <Empty text="No visitor events recorded." />}
    </Panel>}
    {activeTab === 'dashboard' && <section className="client-layout">
      <Panel title="Live Camera" accent="blue">
        <label>Device ID<input value={deviceId} onChange={e => { setDeviceId(e.target.value); setStreamConnected(false); setFaceDetection(null); setStoredFaces([]) }} /></label>
        <div className="stream-box"><div className="video-stage" style={{ aspectRatio: `${faceDetection?.frameWidth || 16} / ${faceDetection?.frameHeight || 9}` }}><video ref={videoElement} autoPlay playsInline style={{ display: streamConnected ? 'block' : 'none' }} /><FaceOverlay detection={streamConnected ? faceDetection : null} />{!streamConnected && <span>Waiting for WebRTC connection...</span>}</div></div>
        <Status label="WebRTC video" status={videoStatus} />
        <button onClick={connectVideo} className="primary">Connect WebRTC Video</button>
        <button onClick={() => { video.stop(); setStreamConnected(false); setVideoStatus('disconnected') }} className="secondary">Disconnect</button>
        <small>Video is published by the separate ESP32 simulator through the backend WebRTC signaling relay.</small>
      </Panel>
      <section className="priority-grid">
        <Panel title="Activity Overview" accent="purple">
          <p className="status">Recent detections are available in the Recent Events tab.</p>
          <button onClick={() => setActiveTab('events')} className="secondary">View Recent Events</button>
        </Panel>
      </section>
      <div className="column">
        <Panel title="Face Detection & Storage" accent="blue">
          <p className="status">Live result: <strong>{faceDetection ? `${faceDetection.facesDetected} face(s)` : 'Waiting for sampled frame'}</strong></p>
          {faceDetection?.faces?.length > 0 && <pre>{JSON.stringify(faceDetection.faces, null, 2)}</pre>}
          <button onClick={storeCurrentFace} className="primary">Detect & Store Current Frame</button>
          <button onClick={loadStoredFaces} className="secondary">Load Stored Faces</button>
          {faceError && <p className="error">{faceError}</p>}
          {storedFaces.length ? <div className="stored-faces">{storedFaces.map(face => <article key={face.id}><img src={`${apiBaseUrl}${face.imageUrl}`} alt="Detected face" /><span>{new Date(face.detectedAt).toLocaleString()}</span></article>)}</div> : <Empty text="No face crops stored for this device." />}
          <small>Stored records contain a cropped face image and detection coordinates only; they do not identify a person.</small>
        </Panel>
        <Panel title="Registered People" accent="purple">
          <label>Person name<input value={personName} onChange={event => setPersonName(event.target.value)} placeholder="John" /></label>
          <label>Registration image (optional)<input type="file" accept="image/*" onChange={event => setRegistrationFile(event.target.files?.[0] || null)} /></label>
          <button onClick={registerPersonFace} className="primary">Save Current Face as Person</button>
          <button onClick={loadPeople} className="secondary">Refresh People</button>
          {peopleError && <p className={peopleError.startsWith('Registered') ? 'status' : 'error'}>{peopleError}</p>}
          {people.length ? <div className="people-list">{people.map(person => <article key={person.id}><div><strong>{person.name}</strong><small>{person.samples} sample{person.samples === 1 ? '' : 's'}</small></div><button onClick={() => removePerson(person)} className="secondary">Delete</button></article>)}</div> : <Empty text="No registered people." />}
          <small>Save Face uses the currently detected video frame when no image is selected. Each image must contain exactly one face.</small>
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
    </section>}
    <Panel title="Backend Activity" accent="purple">
      <div className="console-actions"><button onClick={() => setActivity([])} className="secondary">Clear Console</button><span className="muted">Read-only client activity and WebSocket messages.</span></div>
      <div className="activity">{activity.length ? activity.map(item => <details key={item.id} className={item.error ? 'activity-row failed' : 'activity-row'}><summary><time>{item.time}</time><b>{item.kind}</b><span>{item.result}</span><em>{item.request}</em></summary><pre>{JSON.stringify(item.detail || item.body, null, 2)}</pre></details>) : <Empty text="Backend messages will appear here." />}</div>
    </Panel>
  </main>
}

function Panel({ title, accent, children }) { return <section className={`panel ${accent}`}><h2>{title}</h2>{children}</section> }
function Status({ label, status }) { return <p className="status"><span className={`dot ${status === 'connected' ? 'green' : 'red'}`} />{label}: <strong>{status}</strong></p> }
function Empty({ text }) { return <p className="empty">{text}</p> }
function mergeStoredFaces(existing, additions) {
  return [...new Map([...additions, ...existing].map(face => [face.id, face])).values()].slice(0, 20)
}
function mergePeople(existing, addition) {
  return [...new Map([addition, ...existing].map(person => [person.id, person])).values()].sort((a, b) => a.name.localeCompare(b.name))
}
function FaceOverlay({ detection }) {
  const canvas = useRef(null)
  useEffect(() => {
    const element = canvas.current
    const stage = element?.parentElement
    if (!element || !stage) return undefined
    const draw = () => {
      const width = stage.clientWidth
      const height = stage.clientHeight
      const scale = window.devicePixelRatio || 1
      element.width = Math.max(1, Math.round(width * scale))
      element.height = Math.max(1, Math.round(height * scale))
      element.style.width = `${width}px`
      element.style.height = `${height}px`
      const context = element.getContext('2d')
      context.setTransform(scale, 0, 0, scale, 0, 0)
      context.clearRect(0, 0, width, height)
      const frameWidth = detection?.frameWidth || width
      const frameHeight = detection?.frameHeight || height
      context.strokeStyle = '#49d17d'
      context.lineWidth = 2
      context.font = '12px DM Mono, monospace'
      for (const face of detection?.faces || []) {
        const x = face.x * width / frameWidth
        const y = face.y * height / frameHeight
        const faceWidth = face.width * width / frameWidth
        const faceHeight = face.height * height / frameHeight
        context.strokeRect(x, y, faceWidth, faceHeight)
        context.fillStyle = '#49d17d'
        context.fillText('FACE', x + 4, Math.max(14, y - 4))
      }
    }
    draw()
    const observer = new ResizeObserver(draw)
    observer.observe(stage)
    return () => observer.disconnect()
  }, [detection])
  return <canvas ref={canvas} className="face-overlay" aria-hidden="true" />
}
function NotificationCard({ item, unread }) {
  const type = item.body?.type || 'MESSAGE'
  const timestamp = item.body?.timestamp ? new Date(item.body.timestamp).toLocaleString() : item.time
  return <article className={`notification ${type.includes('UNKNOWN') ? 'unknown' : ''} ${unread ? 'unread' : ''}`}><strong>{type.replaceAll('_', ' ')}</strong><p>{item.body?.message || item.raw}</p>{item.body?.name && <small>Registered person: {item.body.name}</small>}<time>Detected {timestamp}</time></article>
}

export default App
