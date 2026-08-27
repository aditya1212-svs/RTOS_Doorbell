export class WebRtcPublisher {
  constructor() { this.socket = null; this.peer = null; this.pendingCandidates = [] }
  async start(deviceId, stream, onStatus = () => {}) {
    this.stop()
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.socket = new WebSocket(`${protocol}//${location.hostname}:8080/ws/webrtc/${encodeURIComponent(deviceId)}`)
    this.socket.onopen = () => onStatus('connected')
    this.socket.onclose = () => onStatus('disconnected')
    this.socket.onerror = () => onStatus('error')
    this.peer = new RTCPeerConnection()
    stream.getTracks().forEach(track => this.peer.addTrack(track, stream))
    this.peer.onicecandidate = event => event.candidate && this.send({ type: 'ice-candidate', candidate: event.candidate })
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
    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true })
      this.socket.addEventListener('error', () => reject(new Error('WebRTC signaling connection failed')), { once: true })
    })
    await this.peer.setLocalDescription(await this.peer.createOffer())
    this.send({ type: 'offer', sdp: this.peer.localDescription })
  }
  send(message) { if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message)) }
  stop() { this.peer?.close(); this.socket?.close(); this.peer = null; this.socket = null; this.pendingCandidates = [] }
}
