import { StrictMode, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import './styles.css'
import { AudioService } from './audioService'
import { WebRtcPublisher } from './webrtcService'

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
  stream; video; canvas = document.createElement('canvas'); timer; running = false
  async start(video, fps, onFrame) {
    this.stop(); this.stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: false })
    this.video = video; video.srcObject = this.stream; await video.play()
    if (!onFrame) return
    this.running = true
    const publish = async () => {
      if (!this.running) return
      const started = performance.now()
      try {
        const frame = await this.capture()
        if (frame) await onFrame(frame)
      } catch (error) {
        console.error('Frame upload failed', error)
      }
      const delay = Math.max(0, Math.max(67, 1000 / Math.max(1, Number(fps) || 1)) - (performance.now() - started))
      this.timer = setTimeout(publish, delay)
    }
    publish()
  }
  async capture() {
    if (!this.video?.videoWidth) return null
    const scale = Math.min(1, 640 / this.video.videoWidth)
    this.canvas.width = Math.round(this.video.videoWidth * scale); this.canvas.height = Math.round(this.video.videoHeight * scale)
    this.canvas.getContext('2d').drawImage(this.video, 0, 0, this.canvas.width, this.canvas.height)
    const blob = await new Promise(resolve => this.canvas.toBlob(resolve, 'image/jpeg', .82))
    return blob && new File([blob], `camera-${Date.now()}.jpg`, { type: 'image/jpeg' })
  }
  stop() { this.running = false; if (this.timer) clearTimeout(this.timer); this.timer = null; this.stream?.getTracks().forEach(track => track.stop()); if (this.video) this.video.srcObject = null; this.stream = null; this.video = null }
}
function App() {
  const [deviceId, setDeviceId] = useState('esp32-doorbell-01'); const [type, setType] = useState('MOTION'); const [fps, setFps] = useState(15); const [status, setStatus] = useState('Ready'); const [cameraOn, setCameraOn] = useState(false); const [file, setFile] = useState(null)
  const video = useRef(null); const camera = useRef(new LaptopCamera()); const audio = useRef(new AudioService()); const webRtc = useRef(new WebRtcPublisher())
  const [audioStatus, setAudioStatus] = useState('connecting')
  useEffect(() => {
    audio.current.start(deviceId, setAudioStatus, { microphone: false }).catch(error => setAudioStatus(`error: ${error.message}`))
    return () => { camera.current.stop(); audio.current.stop(); webRtc.current.stop() }
  }, [deviceId])
  const report = promise => promise.then(code => setStatus(`Backend accepted request (${code})`)).catch(error => setStatus(error.message))
  const event = async selected => {
    const selectedType = selected || type
    try {
      const code = await postEvent(deviceId, selectedType)
      setStatus(`Backend accepted ${selectedType} (${code})`)
      if (selectedType === 'RING' && camera.current.stream) {
        const snapshot = await camera.current.capture()
        if (snapshot) {
          const frameCode = await postFrame(deviceId, snapshot)
          setStatus(`RING accepted; recognition snapshot uploaded (${frameCode})`)
        }
      }
    } catch (error) { setStatus(error.message) }
  }
  const upload = selected => selected && report(postFrame(deviceId, selected))
  async function startCamera() { try { await camera.current.start(video.current, fps); await webRtc.current.start(deviceId, camera.current.stream, setStatus); setCameraOn(true); setStatus('Camera active; publishing WebRTC video only') } catch (error) { setStatus(error.message) } }
  function stopCamera() { camera.current.stop(); webRtc.current.stop(); setCameraOn(false); setStatus('Camera stopped') }
  return <main><header><p>HARDWARE SUBSTITUTE</p><h1>ESP32 Simulator</h1><span>Backend: {API}</span></header>
    <section className="card"><h2>Device identity</h2><label>Device ID<input value={deviceId} onChange={e => setDeviceId(e.target.value)} /></label></section>
    <section className="card"><h2>Events</h2><select value={type} onChange={e => setType(e.target.value)}>{TYPES.map(item => <option key={item}>{item}</option>)}</select><button onClick={() => event()}>Send Event</button><div className="buttons">{TYPES.map(item => <button key={item} onClick={() => event(item)}>{item}</button>)}</div></section>
    <section className="card"><h2>Laptop camera → WebRTC</h2><video ref={video} muted playsInline /><div className="buttons"><button onClick={startCamera} disabled={cameraOn}>Start camera</button><button onClick={stopCamera} disabled={!cameraOn}>Stop camera</button></div><small>Live video uses WebRTC and does not send one HTTP request per frame.</small><label>Optional recognition snapshot<input type="file" accept="image/*" onChange={e => setFile(e.target.files?.[0])} /></label><button onClick={() => upload(file)} disabled={!file}>Upload Snapshot to /api/frame</button></section>
    <section className="card"><h2>Audio receiver</h2><p className="audio-status">Audio relay: {audioStatus}</p><small>This simulator connects automatically and plays audio sent by the phone client for this device.</small></section>
    <section className="status">{status}</section></main>
}
createRoot(document.getElementById('root')).render(<StrictMode><App /></StrictMode>)
