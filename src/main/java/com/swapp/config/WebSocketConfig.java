package com.swapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import com.swapp.realtime.AuthHandshakeInterceptor;
import com.swapp.realtime.RealtimeHub;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeHub hub;
    private final AuthHandshakeInterceptor interceptor;
    private final AppProperties props;

    public WebSocketConfig(RealtimeHub hub, AuthHandshakeInterceptor interceptor, AppProperties props) {
        this.hub = hub;
        this.interceptor = interceptor;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Same-origin connections are always allowed; additional origins come from CORS_ALLOWED_ORIGINS.
        registry.addHandler(hub, "/ws")
                .addInterceptors(interceptor)
                .setAllowedOrigins(props.cors().allowedOrigins().toArray(String[]::new));
    }
}
