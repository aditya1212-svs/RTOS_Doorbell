export class WebRtcReceiver {
  constructor() { this.socket = null; this.peer = null; this.pendingCandidates = [] }
  async start(deviceId, video, onStatus = () => {}) {
    this.stop()
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    this.socket = new WebSocket(`${protocol}//${location.host.replace(/:\d+$/, ':8080')}/ws/webrtc/${encodeURIComponent(deviceId)}`)
    this.socket.onopen = () => onStatus('connected')
    this.socket.onclose = () => onStatus('disconnected')
    this.socket.onerror = () => onStatus('error')
    this.peer = new RTCPeerConnection()
    this.peer.ontrack = event => { video.srcObject = event.streams[0]; video.play().catch(() => {}) }
    this.peer.onicecandidate = event => event.candidate && this.send({ type: 'ice-candidate', candidate: event.candidate })
    this.socket.onmessage = async event => {
      const message = JSON.parse(event.data)
      if (message.type === 'offer') {
        await this.peer.setRemoteDescription(message.sdp)
        await this.peer.setLocalDescription(await this.peer.createAnswer())
        this.send({ type: 'answer', sdp: this.peer.localDescription })
      } else if (message.type === 'ice-candidate') {
        if (this.peer.remoteDescription) await this.peer.addIceCandidate(message.candidate)
        else this.pendingCandidates.push(message.candidate)
      }
      if (message.type === 'offer') {
        for (const candidate of this.pendingCandidates) await this.peer.addIceCandidate(candidate)
        this.pendingCandidates = []
      }
    }
    await new Promise((resolve, reject) => {
      this.socket.addEventListener('open', resolve, { once: true })
      this.socket.addEventListener('error', () => reject(new Error('WebRTC signaling connection failed')), { once: true })
    })
  }
  send(message) { if (this.socket?.readyState === WebSocket.OPEN) this.socket.send(JSON.stringify(message)) }
  stop() { this.peer?.close(); this.socket?.close(); this.peer = null; this.socket = null; this.pendingCandidates = [] }
}
