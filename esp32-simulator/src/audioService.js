export class AudioService {
  constructor() { this.socket = null; this.context = null; this.stream = null; this.processor = null; this.nextPlayTime = 0 }
  async start(deviceId, onStatus = () => {}, { microphone = true } = {}) {
    this.stop()
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.socket = new WebSocket(`${protocol}//${location.hostname}:8080/ws/audio/${encodeURIComponent(deviceId)}`)
    this.socket.binaryType = 'arraybuffer'; this.socket.onopen = () => onStatus('connected'); this.socket.onerror = () => onStatus('error'); this.socket.onclose = () => onStatus('disconnected'); this.socket.onmessage = event => this.play(event.data)
    await new Promise((resolve, reject) => { this.socket.addEventListener('open', resolve, { once: true }); this.socket.addEventListener('error', () => reject(new Error('Audio WebSocket connection failed')), { once: true }) })
    this.context = new AudioContext({ sampleRate: 16000 })
    if (!microphone) return
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false })
    const source = this.context.createMediaStreamSource(this.stream); this.processor = this.context.createScriptProcessor(4096, 1, 1)
    this.processor.onaudioprocess = event => { if (this.socket?.readyState !== WebSocket.OPEN) return; const input = event.inputBuffer.getChannelData(0); const pcm = new Int16Array(input.length); input.forEach((value, index) => { pcm[index] = Math.max(-1, Math.min(1, value)) * 0x7fff }); this.socket.send(pcm.buffer) }
    source.connect(this.processor); this.processor.connect(this.context.destination); onStatus('connected')
  }
  play(data) {
    if (!this.context || !(data instanceof ArrayBuffer)) return
    this.context.resume().catch(() => {})
    const pcm = new Int16Array(data); const audio = this.context.createBuffer(1, pcm.length, 16000); const output = audio.getChannelData(0); pcm.forEach((value, index) => { output[index] = value / 0x7fff })
    const source = this.context.createBufferSource(); source.buffer = audio; source.connect(this.context.destination); this.nextPlayTime = Math.max(this.nextPlayTime, this.context.currentTime); source.start(this.nextPlayTime); this.nextPlayTime += audio.duration
  }
  stop() { this.processor?.disconnect(); this.stream?.getTracks().forEach(track => track.stop()); this.socket?.close(); this.context?.close(); this.processor = null; this.stream = null; this.socket = null; this.context = null; this.nextPlayTime = 0 }
}
