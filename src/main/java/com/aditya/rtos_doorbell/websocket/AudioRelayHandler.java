package com.aditya.rtos_doorbell.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Component
public class AudioRelayHandler extends BinaryWebSocketHandler {
    private final ConcurrentMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.computeIfAbsent(deviceId(session), ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }
    @Override
    protected void handleBinaryMessage(WebSocketSession source, BinaryMessage message) {
        Set<WebSocketSession> peers = sessions.getOrDefault(deviceId(source), Set.of());
        peers.removeIf(peer -> !peer.isOpen());
        peers.forEach(peer -> {
            if (peer != source && peer.isOpen()) {
                try { peer.sendMessage(message); }
                catch (IOException ignored) { closeQuietly(peer); }
            }
        });
    }
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<WebSocketSession> peers = sessions.get(deviceId(session));
        if (peers != null) { peers.remove(session); if (peers.isEmpty()) sessions.remove(deviceId(session)); }
    }
    private String deviceId(WebSocketSession session) {
        Object value = session.getAttributes().get("deviceId");
        return value == null ? session.getUri().getPath().substring(session.getUri().getPath().lastIndexOf('/') + 1) : value.toString();
    }
    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
    }
}
