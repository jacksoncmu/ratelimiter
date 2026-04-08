package com.example.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class RateLimitingFilter implements GlobalFilter, Ordered {

    private static final String X_USER_ID_HEADER = "X-User-Id";
    private static final String X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String KEY_PREFIX = "rate_limit:";

    private static final int CAPACITY = 100;
    private static final double REFILL_RATE = CAPACITY / 60.0;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> rateLimitScript;

    public RateLimitingFilter(
            ReactiveStringRedisTemplate redisTemplate,
            DefaultRedisScript<String> rateLimitScript
    ) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
    }

    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getRequest().getHeaders().getFirst(X_USER_ID_HEADER);

        if (userId == null) {
            return chain.filter(exchange);
        }

        String key = KEY_PREFIX + userId;
        List<String> keys = List.of(key);
        List<String> args = List.of(
                String.valueOf(CAPACITY),
                String.valueOf(REFILL_RATE),
                String.valueOf(System.currentTimeMillis())
        );

        return redisTemplate.execute(rateLimitScript, keys, args)
                .next()
                .flatMap(result -> {
                    if (result == null || result.isBlank()) {
                        return writeRedisFailure(exchange);
                    }

                    String[] parts = result.split(":");
                    if (parts.length < 2) {
                        return writeRedisFailure(exchange);
                    }

                    boolean allowed = "1".equals(parts[0]);
                    String remaining = parts[1];

                    exchange.getResponse().getHeaders().set(X_RATE_LIMIT_REMAINING, remaining);

                    if (allowed) {
                        return chain.filter(exchange);
                    }

                    return writeRateLimitExceeded(exchange);
                });
    }

    private Mono<Void> writeRateLimitExceeded(ServerWebExchange exchange) {
        byte[] body = "{\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please retry later.\"}"
                .getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        exchange.getResponse().getHeaders().set(X_RATE_LIMIT_REMAINING, "0");
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> writeRedisFailure(ServerWebExchange exchange) {
        byte[] body = "{\"error\":\"Internal Server Error\",\"message\":\"Rate limiter failed.\"}"
                .getBytes(StandardCharsets.UTF_8);

        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}