import { Client } from '@stomp/stompjs'
import { wsUrl } from '../api/client'

export function createSocket(onMessage, onStatus) {
  const client = new Client({
    brokerURL: wsUrl(),
    reconnectDelay: 3000,
    debug: () => {},
    onConnect: () => {
      onStatus('connected')
      client.subscribe('/topic/notify', message => onMessage('notification', message.body))
      client.subscribe('/topic/digest', message => onMessage('digest', message.body))
    },
    onWebSocketClose: () => onStatus('disconnected'),
    onWebSocketError: () => onStatus('error'),
    onStompError: frame => onStatus(`error: ${frame.headers.message || 'STOMP error'}`),
  })
  client.activate()
  return () => client.deactivate()
}
