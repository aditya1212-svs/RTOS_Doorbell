package com.aditya.rtos_doorbell.config;

import com.aditya.rtos_doorbell.config.FaceTopicAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    private final FaceTopicAuthInterceptor faceTopicAuth;

    public WebSocketConfig(FaceTopicAuthInterceptor faceTopicAuth) {
        this.faceTopicAuth = faceTopicAuth;
    }

    @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
    @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }
    @Override public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(faceTopicAuth);
    }
}
