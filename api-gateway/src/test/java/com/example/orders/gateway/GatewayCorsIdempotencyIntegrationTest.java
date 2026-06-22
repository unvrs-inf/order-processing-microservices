package com.example.orders.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.gateway.security.GatewayHeaders;
import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Шлюз разрешает браузеру передать {@code Idempotency-Key} при оформлении заказа. Остальные правила CORS сохраняются.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "RATE_LIMIT_REPLENISH_RATE=1000",
                "RATE_LIMIT_BURST_CAPACITY=2000"
        })
class GatewayCorsIdempotencyIntegrationTest extends GatewayTestEnvironment {

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ---------------------------------------------------------------- preflight для ключа идемпотентности

    @Test
    void aPreflightMayAskForTheIdempotencyKeyHeader() {
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,Idempotency-Key")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        allowed -> assertThat(allowed.toLowerCase())
                                .contains("idempotency-key", "authorization", "content-type"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        allowed -> assertThat(allowed.toLowerCase()).contains("post"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS,
                        allowed -> assertThat(allowed).isEqualTo("true"));
    }

    @Test
    void theAllowedPreflightNeverAnswersWithAWildcardOrigin() {
        // При allowCredentials нельзя разрешать произвольный источник.
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, value -> {
                    assertThat(value)
                            .isEqualTo(ALLOWED_ORIGIN)
                            .isNotEqualTo("*");
                });
    }

    // ---------------------------------------------------------------- правила отказа

    @Test
    void aPreflightFromAnotherOriginIsStillRejectedEvenWithTheGuardHeader() {
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,Idempotency-Key")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    }

    @Test
    void aPreflightNamingAnUnlistedHeaderNeverAdmitsThatHeader() {
        // Добавлен конкретный заголовок идемпотентности. Произвольные заголовки всё ещё отклоняются.
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "idempotency-key,X-Custom-Forage")
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        value -> assertThat(value)
                                .isEqualTo(ALLOWED_ORIGIN)
                                .isNotEqualTo("*"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        allowed -> assertThat(allowed.toLowerCase())
                                .contains("idempotency-key")
                                .doesNotContain("custom-forage"));
    }

    @Test
    void aPreflightNamingOnlyUnlistedHeadersIsStillRejected() {
        // Если среди заголовков запроса нет разрешённых, preflight отклоняется целиком.
        client().options().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Custom-Forage,X-Evil")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    // ---------------------------------------------------------------- основной запрос

    @Test
    void theIdempotentCheckoutReachesOrderServiceWithItsReplayGuard() throws InterruptedException {
        String idempotencyKey = UUID.randomUUID().toString();

        client().post().uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(newUserId(), "USER"))
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .bodyValue("{\"items\":[]}")
                .exchange()
                .expectStatus().isOk()
                // Ответ на запрос с учётными данными также возвращает точный источник.
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN);

        RecordedRequest proxied = takeProxiedRequest();
        assertThat(proxied.getPath()).isEqualTo("/api/orders");
        assertThat(proxied.getHeader("Idempotency-Key")).isEqualTo(idempotencyKey);
        // Идентификатор корреляции сохраняется при маршрутизации.
        assertThat(proxied.getHeader(GatewayHeaders.CORRELATION_ID)).isNotBlank();
    }
}
