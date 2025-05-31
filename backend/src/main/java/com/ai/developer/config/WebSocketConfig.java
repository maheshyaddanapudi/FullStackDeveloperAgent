package com.ai.developer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    
    @Bean
    public ToolOutputWebSocketHandler toolOutputWebSocketHandler() {
        return new ToolOutputWebSocketHandler();
    }
    
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(toolOutputWebSocketHandler(), "/ws/tools")
                .setAllowedOrigins("*");
    }
}
