package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {

    @Bean
    public RouteLocator gatewayRoutes(
            RouteLocatorBuilder builder,
            @Value("${gateway.routes.users-uri:http://localhost:8081}") String usersUri,
            @Value("${gateway.routes.orders-uri:http://localhost:8082}") String ordersUri) {

        return builder.routes()
                .route("users-service", r -> r
                        .path("/api/v1/users/**")
                        .filters(f -> f.stripPrefix(3))
                        .uri(usersUri))
                .route("orders-service", r -> r
                        .path("/api/v1/orders/**")
                        .filters(f -> f.stripPrefix(3))
                        .uri(ordersUri))
                .build();
    }
}
