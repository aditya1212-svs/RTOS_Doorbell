package com.aditya.rtos_doorbell.config;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * {@link ChannelInterceptor} that protects subscriptions to {@code /api/next/face/{deviceId}}.
 *
 * <p>When {@code face.auth.enabled}</code> is false (the default) every subscription is
 * allowed, preserving current behaviour. When enabled, a subscription is only accepted if
 * the requested device is in the configured allowlist; unauthorised ones are silently
 * dropped. Publishing still flows regardless; this only gates who may subscribe.
 */
@Component
public class FaceTopicAuthInterceptor implements ChannelInterceptor {

    private static final String FACE_TOPIC_PREFIX = "/topic/face/";

    private final FaceAuthService auth;

    public FaceTopicAuthInterceptor(FaceAuthService auth) {
        this.auth = auth;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        if (!auth.isEnabled()) return message;

        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() != StompCommand.SUBSCRIBE) return message;

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(FACE_TOPIC_PREFIX)) return message;

        String deviceId = destination.substring(FACE_TOPIC_PREFIX.length());
        if (deviceId.isEmpty()) return null;

        if (!auth.allows(deviceId)) return null;
        return message;
    }
}