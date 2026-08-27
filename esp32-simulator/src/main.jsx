import { StrictMode, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'

const API = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '')
const TYPES = ['MOTION', 'RING', 'RECOGNIZED', 'UNKNOWN', 'UNLOCK_GRANTED']

async function postEvent(deviceId, type) {
  const response = await fetch(`${API}/api/event`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ deviceId, timestamp: new Date().toISOString(), type }) })
  if (!response.ok) throw new Error(`Event failed: HTTP ${response.status} ${await response.text()}`)
  return response.status
}
async function postFrame(deviceId, file) {
  const form = new FormData(); form.append('deviceId', deviceId); form.append('frame', file, file.name || 'laptop-camera.jpg')
  const response = await fetch(`${API}/api/frame`, { method: 'POST', body: form })
  if (!response.ok) throw new Error(`Frame failed: HTTP ${response.status} ${await response.text()}`)
  return response.status
}
class LaptopCamera {
  stream; video; canvas = document.createElement('canvas'); timer
  async start(video, fps, onFrame) {
    this.stop(); this.stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
    this.video = video; video.srcObject = this.stream; await video.play()
    this.timer = setInterval(async () => { const frame = await this.capture(); if (frame) await onFrame(frame) }, Math.max(100, 1000 / Math.max(1, Number(fps) || 1)))
  }
  async capture() {
    if (!this.video?.videoWidth) return null
    this.canvas.width = this.video.videoWidth; this.canvas.height = this.video.videoHeight; this.canvas.getContext('2d').drawImage(this.video, 0, 0)
    const blob = await new Promise(resolve => this.canvas.toBlob(resolve, 'image/jpeg', .82))
    return blob && new File([blob], `camera-${Date.now()}.jpg`, { type: 'image/jpeg' })
  }
  stop() { if (this.timer) clearInterval(this.timer); this.timer = null; this.stream?.getTracks().forEach(track => track.stop()); if (this.video) this.video.srcObject = null; this.stream = null; this.video = null }
}
function App() {
  const [deviceId, setDeviceId] = useState('esp32-doorbell-01'); const [type, setType] = useState('MOTION'); const [fps, setFps] = useState(5); const [status, setStatus] = useState('Ready'); const [cameraOn, setCameraOn] = useState(false); const [file, setFile] = useState(null)
  const video = useRef(null); const camera = useRef(new LaptopCamera())
  useEffect(() => () => camera.current.stop(), [])
  const report = promise => promise.then(code => setStatus(`Backend accepted request (${code})`)).catch(error => setStatus(error.message))
  const event = selected => report(postEvent(deviceId, selected || type))
  const upload = selected => selected && report(postFrame(deviceId, selected))
  async function startCamera() { try { await camera.current.start(video.current, fps, frame => postFrame(deviceId, frame)); setCameraOn(true); setStatus('Camera active; uploading frames to backend') } catch (error) { setStatus(error.message) } }
  function stopCamera() { camera.current.stop(); setCameraOn(false); setStatus('Camera stopped') }
  return <main><header><p>HARDWARE SUBSTITUTE</p><h1>ESP32 Simulator</h1><span>Backend: {API}</span></header>
    <section className="card"><h2>Device identity</h2><label>Device ID<input value={deviceId} onChange={e => setDeviceId(e.target.value)} /></label></section>
    <section className="card"><h2>Events</h2><select value={type} onChange={e => setType(e.target.value)}>{TYPES.map(item => <option key={item}>{item}</option>)}</select><button onClick={() => event()}>Send Event</button><div className="buttons">{TYPES.map(item => <button key={item} onClick={() => event(item)}>{item}</button>)}</div></section>
    <section className="card"><h2>Laptop camera → /api/frame</h2><video ref={video} muted playsInline /><div className="buttons"><button onClick={startCamera} disabled={cameraOn}>Start camera</button><button onClick={stopCamera} disabled={!cameraOn}>Stop camera</button></div><label>Upload a still image<input type="file" accept="image/*" onChange={e => setFile(e.target.files?.[0])} /></label><button onClick={() => upload(file)} disabled={!file}>Upload frame</button><label>Camera FPS<input type="number" min="1" max="15" value={fps} onChange={e => setFps(e.target.value)} /></label></section>
    <section className="status">{status}</section></main>
}
createRoot(document.getElementById('root')).render(<StrictMode><App /></StrictMode>)
