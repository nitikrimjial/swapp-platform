package com.swapp.realtime;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import com.swapp.security.AuthCookies;
import com.swapp.security.TokenAuthenticator;

/** Authenticates the WebSocket upgrade using the same HttpOnly auth cookie as the REST API. */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    private final TokenAuthenticator authenticator;
    private final AuthCookies cookies;

    public AuthHandshakeInterceptor(TokenAuthenticator authenticator, AuthCookies cookies) {
        this.authenticator = authenticator;
        this.cookies = cookies;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String token = cookies.read(servletRequest.getServletRequest());
            var user = authenticator.authenticate(token);
            if (user.isPresent()) {
                attributes.put(RealtimeHub.USER_ID_ATTR, user.get().id());
                return true;
            }
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
