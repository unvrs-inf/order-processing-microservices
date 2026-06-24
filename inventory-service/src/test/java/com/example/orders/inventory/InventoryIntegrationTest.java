package com.example.orders.inventory;

import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ProductRepository;
import com.example.orders.inventory.domain.StockReservationRepository;
import com.example.orders.inventory.service.ProductService;
import com.example.orders.inventory.service.StockReservationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Общее окружение с PostgreSQL, Kafka и тестовыми HTTP-ответами для inventory-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
abstract class InventoryIntegrationTest {

    /** Издатель JWT, которому сервис доверяет в тестах. */
    protected static final String ISSUER = "order-processing-auth";

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    /** Тестовый ключ подписи. Заглушка публикует его открытую часть. */
    protected static final RSAKey SIGNING_KEY = generateKey("inventory-test-key");

    /** Второй ключ не публикуется. Сервис отклоняет подписанные им токены. */
    protected static final RSAKey ROGUE_KEY = generateKey("inventory-test-key");

    @SuppressWarnings("resource")
    protected static final MockWebServer JWKS_STUB = new MockWebServer();

    /** Заглушка order-service, см. {@link #stubOrderStatus} и {@link #orderLookups()}. */
    @SuppressWarnings("resource")
    protected static final MockWebServer ORDER_STUB = new MockWebServer();

    private static final Map<UUID, Supplier<MockResponse>> ORDER_ANSWERS = new ConcurrentHashMap<>();

    private static final List<OrderLookup> ORDER_LOOKUPS = new CopyOnWriteArrayList<>();

    /**
     * Запрос склада к заглушке order-service.
     * @param orderId идентификатор заказа
     * @param authorization значение заголовка Authorization
     */
    protected record OrderLookup(UUID orderId, String authorization) {
    }

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    static {
        POSTGRES.start();
        KAFKA.start();
        JWKS_STUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith(JWKS_PATH)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            JWKS_STUB.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the JWKS stub", e);
        }
        ORDER_STUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                String prefix = "/api/orders/";
                if (!"GET".equals(request.getMethod()) || !path.startsWith(prefix)) {
                    return new MockResponse().setResponseCode(404);
                }
                UUID orderId;
                try {
                    orderId = UUID.fromString(path.substring(prefix.length()));
                }
                catch (IllegalArgumentException e) {
                    return new MockResponse().setResponseCode(400);
                }
                ORDER_LOOKUPS.add(new OrderLookup(orderId, request.getHeader("Authorization")));
                Supplier<MockResponse> answer = ORDER_ANSWERS.get(orderId);
                return answer == null ? new MockResponse().setResponseCode(404) : answer.get();
            }
        });
        try {
            ORDER_STUB.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the order-service stub", e);
        }
    }

    @DynamicPropertySource
    static void inventoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Увеличиваем пул для теста с 20 потоками и короткими транзакциями.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 16);
        registry.add("AUTH_JWKS_URI", () -> "http://localhost:" + JWKS_STUB.getPort() + JWKS_PATH);
        registry.add("AUTH_JWT_ISSUER", () -> ISSUER);
        registry.add("ORDER_BASE_URL", () -> "http://localhost:" + ORDER_STUB.getPort());
        // Короткие тайм-ауты ускоряют тесты недоступности заказа.
        registry.add("ORDER_READ_TIMEOUT_MS", () -> 700);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        // Частый опрос outbox сокращает ожидание события.
        registry.add("OUTBOX_POLL_MS", () -> 250);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected ProductRepository products;

    @Autowired
    protected StockReservationRepository reservations;

    @Autowired
    protected ProductService productService;

    @Autowired
    protected StockReservationService reservationService;

    /** Очищаем каталог перед тестом. Резервы удаляем первыми из-за внешнего ключа. */
    @BeforeEach
    void resetDatabase() {
        reservations.deleteAllInBatch();
        products.deleteAllInBatch();
        ORDER_ANSWERS.clear();
        ORDER_LOOKUPS.clear();
    }

    /**
     * @param orderId заказ, известный заглушке
     * @param status статус
     */
    protected static void stubOrderStatus(UUID orderId, String status) {
        stubOrderAnswer(orderId, () -> new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"id\":\"%s\",\"status\":\"%s\",\"orderNumber\":\"ORD-STUB\"}"
                        .formatted(orderId, status)));
    }

    /**
     * @param orderId идентификатор для ответа заглушки
     * @param answer ответ тестового сервиса
     */
    protected static void stubOrderAnswer(UUID orderId, Supplier<MockResponse> answer) {
        ORDER_ANSWERS.put(orderId, answer);
    }

    /** @return запросы к заглушке order-service в порядке получения */
    protected static List<OrderLookup> orderLookups() {
        return List.copyOf(ORDER_LOOKUPS);
    }

    /**
     * @param sku артикул товара
     * @param quantity количество единиц
     */
    protected Product givenProduct(String sku, int quantity) {
        return givenProduct(sku, "Product " + sku, new BigDecimal("10.00"), quantity);
    }

    /**
     * @param sku артикул товара
     * @param name название
     * @param price цена
     * @param quantity количество единиц
     */
    protected Product givenProduct(String sku, String name, BigDecimal price, int quantity) {
        return products.saveAndFlush(Product.create(sku, name, "seeded by a test", price, quantity));
    }

    /** @return токен с ролью ADMIN в формате auth-service */
    protected static String adminToken() {
        return token(UUID.randomUUID(), "ADMIN");
    }

    /** @return токен только с ролью USER */
    protected static String userToken() {
        return token(UUID.randomUUID(), "USER");
    }

    /**
     * @param userId значение поля {@code sub}
     * @param roles роли пользователя
     */
    protected static String token(UUID userId, String... roles) {
        Instant now = Instant.now();
        return sign(SIGNING_KEY, ISSUER, userId.toString(), List.of(roles), now, now.plus(Duration.ofMinutes(30)));
    }

    /** @return токен ADMIN, подписанный неопубликованным ключом */
    protected static String tokenSignedByAnotherKey() {
        Instant now = Instant.now();
        return sign(ROGUE_KEY, ISSUER, UUID.randomUUID().toString(), List.of("ADMIN"), now,
                now.plus(Duration.ofMinutes(30)));
    }

    /** @return токен ADMIN с истёкшим временем {@code exp} */
    protected static String expiredAdminToken() {
        Instant issuedAt = Instant.now().minus(Duration.ofHours(2));
        return sign(SIGNING_KEY, ISSUER, UUID.randomUUID().toString(), List.of("ADMIN"), issuedAt,
                issuedAt.plus(Duration.ofMinutes(30)));
    }

    /** @return корректно подписанный токен ADMIN с недоверенным издателем */
    protected static String tokenFromAnotherIssuer() {
        Instant now = Instant.now();
        return sign(SIGNING_KEY, "https://evil.example/auth", UUID.randomUUID().toString(), List.of("ADMIN"), now,
                now.plus(Duration.ofMinutes(30)));
    }

    private static String sign(RSAKey key, String issuer, String subject, List<String> roles, Instant issuedAt,
            Instant expiresAt) {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("email", "user@orders.local")
                .claim("name", "Test User")
                .claim("roles", roles)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(),
                claims);
        try {
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
        }
        catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
        return jwt.serialize();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        }
        catch (JOSEException e) {
            throw new IllegalStateException("Could not generate the test RSA key", e);
        }
    }
}
