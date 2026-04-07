package com.example.gateway.config;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("users-service", r -> r
                        .path("/api/v1/users/**")
                        .uri("http://localhost:8081"))
                .route("orders-service", r -> r
                        .path("/api/v1/orders/**")
                        .uri("http://localhost:8082"))
                .build();
    }
}
