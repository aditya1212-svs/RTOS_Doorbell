import { Client } from '@stomp/stompjs'
import { wsUrl } from '../api/client'

export function createSocket(deviceId, onMessage, onStatus) {
  const client = new Client({
    brokerURL: wsUrl(),
    reconnectDelay: 3000,
    debug: () => {},
    onConnect: () => {
      onStatus('connected')
      client.subscribe('/topic/notify', message => onMessage('notification', message.body))
      client.subscribe('/topic/digest', message => onMessage('digest', message.body))
      client.subscribe(`/topic/face/${deviceId}`, message => onMessage('face', message.body))
    },
    onWebSocketClose: () => onStatus('disconnected'),
    onWebSocketError: () => onStatus('error'),
    onStompError: frame => onStatus(`error: ${frame.headers.message || 'STOMP error'}`),
  })
  client.activate()
  return () => client.deactivate()
}
