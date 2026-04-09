# API Gateway with Rate Limiting

A Spring Cloud Gateway that enforces per-user token bucket rate limiting backed by Redis, with JWT authentication and Prometheus/Grafana observability.

## Architecture

```
Client → Spring Cloud Gateway (8080)
              ├── JwtAuthenticationFilter   (order -2)
              ├── RateLimitingFilter        (order -1)
              └── Upstream services (httpbin on 8081/8082)

Redis ← token bucket state (Lua script, atomic)
Prometheus (9090) ← /actuator/prometheus scrape
Grafana (3000) ← Prometheus datasource
```

**Token bucket via Lua script.** The bucket logic runs as a single atomic Lua script on Redis — no compare-and-swap loops, no race conditions under concurrent VUs. State is two fields (`tokens`, `last_refill`) in a Redis hash with a 120 s TTL.

**Bucket parameters.** Capacity is 100 tokens, refill rate is 100/60 ≈ 1.67 tokens/second. All requests from the same `X-User-Id` share one bucket. The load test wires 50 VUs to a single user ID intentionally, so the bucket saturates in under two seconds and holds a sustained 429 rate.

**Filter ordering.** JWT auth runs at order -2, rate limiting at -1. Auth extracts the user ID from the JWT and injects it as `X-User-Id` before the rate limiter reads it. Public paths (`/api/v1/auth/*`) bypass auth and are never rate-limited.

**Short-circuit responses.** Both filters write their own error responses and return without calling `chain.filter`. No upstream hop occurs for rejected or unauthorized requests.

**StripPrefix(3).** The gateway strips the `/api/v1/{service}` prefix before forwarding so upstream services see clean paths.

## Load test results (50 VUs, 70 s)

| Metric | Value |
|---|---|
| Total requests | 440,701 |
| Throughput | ~6,294 req/s |
| Rate-limited (429) | 99.95% |
| Allowed (200) | ~215 requests |
| p95 latency — 200 | 20.22 ms |
| p95 latency — 429 | 9.94 ms |
| All checks passed | 100% |

429 responses are roughly half the latency of 200s — the Redis rejection path skips the upstream hop entirely.

Note: `http_req_failed` appears high because k6 counts HTTP 429 responses as failed by default, even when they are expected in a rate-limiter stress test.

## Running

```bash
# Start Redis, downstream services, Prometheus, Grafana
docker compose up -d

# Start the gateway
mvn spring-boot:run

# Run the load test
K6_WEB_DASHBOARD=true k6 run load-test/rate-limiter.js
```

Grafana: http://localhost:3000 (admin/admin)  
Prometheus: http://localhost:9090  
k6 dashboard: http://localhost:5665
