# API Gateway with Rate Limiting

A Spring Cloud Gateway that enforces per-user token bucket rate limiting backed by Redis, with JWT authentication and Prometheus/Grafana observability.

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
