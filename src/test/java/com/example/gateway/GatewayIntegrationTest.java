package com.example.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
class GatewayIntegrationTest {

    // Must match application.yml so the JWT filter accepts tokens minted here
    private static final String JWT_SECRET = "bXlTdXBlclNlY3JldEtleUZvckpXVEF1dGhUaGlzSXNBVGVzdA==";

    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    // MockWebServer must be initialised in a static block so the port is known
    // before @DynamicPropertySource runs (which happens before @BeforeAll).
    static final MockWebServer mockBackend;

    static {
        try {
            mockBackend = new MockWebServer();
            mockBackend.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    return new MockResponse().setResponseCode(200).setBody("upstream OK");
                }
            });
            mockBackend.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockBackend.shutdown();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Point both gateway routes at the MockWebServer instead of the real backends
        String backendUrl = "http://" + mockBackend.getHostName() + ":" + mockBackend.getPort();
        registry.add("gateway.routes.users-uri", () -> backendUrl);
        registry.add("gateway.routes.orders-uri", () -> backendUrl);
    }

    @LocalServerPort
    int port;

    @Autowired
    WebTestClient webTestClient;

    // -------------------------------------------------------------------------
    // JWT filter tests
    // -------------------------------------------------------------------------

    @Test
    void jwtFilter_returns401_whenAuthorizationHeaderIsMissing() {
        webTestClient.get()
                .uri("/api/v1/users/profile")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    void jwtFilter_returns401_whenTokenSignatureIsInvalid() {
        webTestClient.get()
                .uri("/api/v1/users/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.bad.payload")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.error").isEqualTo("Unauthorized");
    }

    @Test
    void jwtFilter_skipsAuthCheck_forPublicPaths() {
        // /api/v1/auth/login has no matching route so the gateway returns 404 —
        // the important assertion is that the JWT filter does NOT return 401.
        webTestClient.post()
                .uri("/api/v1/auth/login")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    // -------------------------------------------------------------------------
    // Rate-limiter test
    // -------------------------------------------------------------------------

    @Test
    void rateLimiter_allows100AndRejects5_outOf105ConcurrentRequests() {
        // A fresh UUID subject guarantees a clean token bucket in Redis for this run.
        String token = mintJwt(UUID.randomUUID().toString());

        WebClient client = WebClient.builder()
                .baseUrl("http://localhost:" + port)
                .build();

        // flatMap with no concurrency cap fires all 105 requests onto the wire
        // simultaneously; Reactor/Netty pipelines them without blocking.
        List<Integer> statusCodes = Flux.range(1, 105)
                .flatMap(i -> client.get()
                        .uri("/api/v1/users/anything")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .exchangeToMono(response -> Mono.just(response.statusCode().value())))
                .collectList()
                .block(Duration.ofSeconds(30));

        assertThat(statusCodes).hasSize(105);

        long okCount          = statusCodes.stream().filter(s -> s == 200).count();
        long rateLimitedCount = statusCodes.stream().filter(s -> s == 429).count();

        // The token bucket starts full at 100. Refill rate is ~1.67 tokens/sec;
        // at near-zero elapsed time across 105 concurrent requests no meaningful
        // refill occurs, so the split must be exactly 100 / 5.
        assertThat(okCount)
                .as("exactly 100 requests should pass the rate limiter")
                .isEqualTo(100);
        assertThat(rateLimitedCount)
                .as("exactly 5 requests should be rejected with 429")
                .isEqualTo(5);
    }

    // -------------------------------------------------------------------------

    private String mintJwt(String subject) {
        SecretKey key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(JWT_SECRET));
        return Jwts.builder()
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000L))
                .signWith(key)
                .compact();
    }
}
