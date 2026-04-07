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
    // 100 tokens refilled over 60 seconds
    private static final double REFILL_RATE = CAPACITY / 60.0;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<String> rateLimitScript;

    public RateLimitingFilter(ReactiveStringRedisTemplate redisTemplate,
                               DefaultRedisScript<String> rateLimitScript) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = rateLimitScript;
    }

    @Override
    public int getOrder() {
        // Runs after JwtAuthenticationFilter (-2) so X-User-Id is already present
        return -1;
    }

    
}
