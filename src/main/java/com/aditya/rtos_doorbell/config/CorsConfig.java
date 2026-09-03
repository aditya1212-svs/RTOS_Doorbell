package com.aditya.rtos_doorbell.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String frontendOrigin;
    private final FaceRestAuthInterceptor faceRestAuth;

    public CorsConfig(@Value("${app.frontend-origin:http://localhost:5173}") String frontendOrigin,
                      FaceRestAuthInterceptor faceRestAuth) {
        this.frontendOrigin = frontendOrigin;
        this.faceRestAuth = faceRestAuth;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(frontendOrigin.split("\\s*,\\s*"))
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The face REST gate only takes effect when face.auth.enabled=true and an
        // allowlist is configured; otherwise it is a no-op for device-scoped /
        // api/face/** endpoints.
        registry.addInterceptor(faceRestAuth).addPathPatterns("/api/face/**");
    }
}
