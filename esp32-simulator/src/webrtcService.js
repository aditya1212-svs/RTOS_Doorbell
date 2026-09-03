export class WebRtcPublisher {
  constructor() {
    this.socket = null
    this.peer = null
    this.pendingCandidates = []
    this.stopped = true
    this.reconnectTimer = null
    this.attempt = 0
    this.deviceId = null
    this.stream = null
    this.onStatus = () => {}
  }

  async start(deviceId, stream, onStatus = () => {}) {
    this.stop()
    this.stopped = false
    this.deviceId = deviceId
    this.stream = stream
    this.onStatus = onStatus
    this.attempt = 0
    await this.open()
  }

  async open() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.socket = new WebSocket(`${protocol}//${location.hostname}:8080/ws/webrtc/${encodeURIComponent(this.deviceId)}`)
    // The publisher creates the connection and streams the local camera tracks to it.
    // (Before this, `this.peer` was never constructed, so reading `connectionState`
    // in the onopen handler threw "Cannot read properties of null".)
    this.peer = new RTCPeerConnection()
    if (this.stream) this.stream.getTracks().forEach(track => this.peer.addTrack(track, this.stream))
    this.peer.onicecandidate = event => event.candidate && this.send({ type: 'ice-candidate', candidate: event.candidate })

    this.socket.onopen = async () => {
      this.attempt = 0
      this.onStatus('connected')
      // Only re-offer if the media path is not already established. A transient
      // signaling drop should not tear down a working peer.
      if (this.peer.connectionState !== 'connected') {
        await this.peer.setLocalDescription(await this.peer.createOffer())
        this.send({ type: 'offer', sdp: this.peer.localDescription })
      }
    }
    this.socket.onmessage = async event => {
      const message = JSON.parse(event.data)
      if (message.type === 'answer') {
        await this.peer.setRemoteDescription(message.sdp)
        for (const candidate of this.pendingCandidates) await this.peer.addIceCandidate(candidate)
        this.pendingCandidates = []
      } else if (message.type === 'ice-candidate') {
        if (this.peer.remoteDescription) await this.peer.addIceCandidate(message.candidate)
        else this.pendingCandidates.push(message.candidate)
      }
    }
    this.socket.onerror = () => this.onStatus('error')
    this.socket.onclose = () => this.scheduleReconnect()
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
    this.stream = null
  }
}
