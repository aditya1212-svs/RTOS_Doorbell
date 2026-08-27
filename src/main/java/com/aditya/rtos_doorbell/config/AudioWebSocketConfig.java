package com.aditya.rtos_doorbell.config;

import com.aditya.rtos_doorbell.websocket.AudioRelayHandler;
import com.aditya.rtos_doorbell.websocket.WebRtcSignalingHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class AudioWebSocketConfig implements WebSocketConfigurer {
    private final AudioRelayHandler handler;
    private final WebRtcSignalingHandler webRtcHandler;
    public AudioWebSocketConfig(AudioRelayHandler handler, WebRtcSignalingHandler webRtcHandler) {
        this.handler = handler; this.webRtcHandler = webRtcHandler;
    }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/audio/{deviceId}").setAllowedOriginPatterns("*");
        registry.addHandler(webRtcHandler, "/ws/webrtc/{deviceId}").setAllowedOriginPatterns("*");
    }
}
