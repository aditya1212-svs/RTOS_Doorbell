export class WebRtcReceiver {
  constructor() {
    this.socket = null
    this.peer = null
    this.pendingCandidates = []
    this.stopped = true
    this.reconnectTimer = null
    this.attempt = 0
    this.deviceId = null
    this.video = null
    this.stream = null
    this.onStatus = () => {}
  }

  async start(deviceId, video, onStatus = () => {}) {
    this.stop()
    this.stopped = false
    this.deviceId = deviceId
    this.video = video
    this.onStatus = onStatus
    if (this.stream && this.video) {
      this.video.srcObject = this.stream
      this.video.play().catch(() => {})
    }
    this.attempt = 0
    await this.open()
  }

  async open() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.socket = new WebSocket(`${protocol}//${location.host.replace(/:\d+$/, ':8080')}/ws/webrtc/${encodeURIComponent(this.deviceId)}`)
    this.socket.onopen = () => {
      this.attempt = 0
      this.onStatus('connected')
    }
    this.socket.onerror = () => this.onStatus('error')
    this.socket.onclose = () => this.scheduleReconnect()

    this.peer = new RTCPeerConnection()
    this.peer.ontrack = event => {
      this.stream = event.streams[0]
      if (this.video) {
        this.video.srcObject = this.stream
        this.video.play().catch(() => {})
      }
    }
    this.peer.onicecandidate = event => event.candidate && this.send({ type: 'ice-candidate', candidate: event.candidate })
    this.socket.onmessage = async event => {
      const message = JSON.parse(event.data)
      if (message.type === 'offer') {
        // A reconnection causes the backend to replay the latest offer; re-answer
        // to re-establish signaling after a Spring Boot restart.
        await this.peer.setRemoteDescription(message.sdp)
        await this.peer.setLocalDescription(await this.peer.createAnswer())
        this.send({ type: 'answer', sdp: this.peer.localDescription })
        for (const candidate of this.pendingCandidates) await this.peer.addIceCandidate(candidate)
        this.pendingCandidates = []
      } else if (message.type === 'ice-candidate') {
        if (this.peer.remoteDescription) await this.peer.addIceCandidate(message.candidate)
        else this.pendingCandidates.push(message.candidate)
      }
    }

    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true })
      this.socket.addEventListener('error', () => reject(new Error('WebRTC signaling connection failed')), { once: true })
    })
  }

  scheduleReconnect() {
    if (this.stopped) return
    this.onStatus('disconnected')
    this.attempt += 1
    const delay = Math.min(30_000, 1000 * Math.pow(2, this.attempt - 1))
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null
      this.pendingCandidates = []
      this.open().catch(() => this.onStatus('error'))
    }, delay)
  }

  send(message) { if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message)) }

  stop() {
    this.stopped = true
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    if (this.socket) { this.socket.onclose = null; this.socket.close() }
    this.peer?.close()
    this.peer = null
    this.socket = null
    this.pendingCandidates = []
    this.deviceId = null
    this.video = null
    this.stream = null
  }
}
