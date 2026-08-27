package com.aditya.rtos_doorbell.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@Component
public class WebRtcSignalingHandler extends TextWebSocketHandler {
    private final ConcurrentMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TextMessage> latestOffers = new ConcurrentHashMap<>();
    @Override public void afterConnectionEstablished(WebSocketSession session) {
        String deviceId = deviceId(session);
        sessions.computeIfAbsent(deviceId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        TextMessage offer = latestOffers.get(deviceId);
        if (offer != null) {
            try { session.sendMessage(offer); } catch (IOException ignored) { closeQuietly(session); }
        }
    }
    @Override protected void handleTextMessage(WebSocketSession source, TextMessage message) {
        String deviceId = deviceId(source);
        if (message.getPayload().contains("\"type\":\"offer\"")) latestOffers.put(deviceId, message);
        for (WebSocketSession peer : sessions.getOrDefault(deviceId, Set.of())) {
            if (peer != source && peer.isOpen()) {
                try { peer.sendMessage(message); } catch (IOException ignored) { closeQuietly(peer); }
            }
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Set<WebSocketSession> peers = sessions.get(deviceId(session));
        if (peers != null) { peers.remove(session); if (peers.isEmpty()) sessions.remove(deviceId(session)); }
    }
    private String deviceId(WebSocketSession session) {
        String path = Objects.requireNonNull(session.getUri()).getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }
    private void closeQuietly(WebSocketSession session) {
        try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) { }
    }
}
