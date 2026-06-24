package com.example.orders.order;

import com.example.orders.order.domain.OrderRepository;
import com.example.orders.support.outbox.OutboxRepository;
import com.example.orders.support.correlation.CorrelationId;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
 * Общее окружение тестов order-service. Реальные PostgreSQL и Kafka для проверки базы и сообщений, HTTP-заглушки для JWKS и каталога.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class OrderIntegrationTest {

    /** Издатель JWT, которому доверяет сервис в тестах. */
    protected static final String ISSUER = "order-processing-auth";

    protected static final String JWKS_PATH = "/.well-known/jwks.json";

    protected static final String CATALOG_PATH_PREFIX = "/api/products/";

    /** Ключ подписи тестовых токенов. Заглушка публикует его открытую часть. */
    protected static final RSAKey SIGNING_KEY = generateKey("order-test-key");

    /** Товары, доступные заглушке каталога, по идентификатору. */
    protected static final Map<UUID, StubProduct> CATALOG = new ConcurrentHashMap<>();

    /** Идентификаторы корреляции, полученные каталогом, в порядке запросов. */
    protected static final List<String> CATALOG_CORRELATION_IDS = new CopyOnWriteArrayList<>();

    /**
     * Поведение каталога можно переключать для сценариев отказа.
     */
    protected static volatile Function<UUID, MockResponse> catalogBehaviour = OrderIntegrationTest::serveFromCatalog;

    @SuppressWarnings("resource")
    protected static final MockWebServer HTTP_STUB = new MockWebServer();

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @SuppressWarnings("resource")
    protected static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.2"));

    static {
        POSTGRES.start();
        KAFKA.start();
        HTTP_STUB.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith(JWKS_PATH)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
                }
                if (path.startsWith(CATALOG_PATH_PREFIX)) {
                    CATALOG_CORRELATION_IDS.add(String.valueOf(request.getHeader(CorrelationId.HEADER)));
                    return catalogBehaviour.apply(productIdIn(path));
                }
                return new MockResponse().setResponseCode(404);
            }
        });
        try {
            HTTP_STUB.start();
        }
        catch (IOException e) {
            throw new UncheckedIOException("Could not start the HTTP stub", e);
        }
    }

    @DynamicPropertySource
    static void orderProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("AUTH_JWKS_URI", () -> stubUrl(JWKS_PATH));
        registry.add("AUTH_JWT_ISSUER", () -> ISSUER);
        registry.add("INVENTORY_BASE_URL", () -> stubUrl(""));
        // Частый опрос outbox сокращает ожидание события в тестах.
        registry.add("OUTBOX_POLL_MS", () -> 250);
        // Короткие задержки повторов ускоряют тесты на сбои.
        registry.add("INVENTORY_RETRY_WAIT", () -> "20ms");
        // Circuit breaker использует обычные пороги, включая минимальное число вызовов.
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected OrderRepository orders;

    @Autowired
    protected OutboxRepository outbox;

    /**
     * Каждый тест начинается с пустой базы и каталога.
     */
    @BeforeEach
    void resetState() {
        orders.deleteAllInBatch();
        outbox.deleteAllInBatch();
        CATALOG.clear();
        CATALOG_CORRELATION_IDS.clear();
        catalogBehaviour = OrderIntegrationTest::serveFromCatalog;
    }

    // ------------------------------------------------------------------ заглушка каталога

    /**
     * Товар в формате ответа {@code GET /api/products/{id}}.
     * @param id идентификатор товара
     * @param sku артикул
     * @param name название
     * @param price цена
     */
    protected record StubProduct(UUID id, String sku, String name, BigDecimal price) {
    }

    /**
     * @param sku артикул
     * @param name название
     * @param price цена
     * @return идентификатор тестового товара
     */
    protected static UUID givenCatalogProduct(String sku, String name, String price) {
        UUID id = UUID.randomUUID();
        CATALOG.put(id, new StubProduct(id, sku, name, new BigDecimal(price)));
        return id;
    }

    /**
     * @param behaviour функция, которая формирует ответ каталога по идентификатору
     */
    protected static void catalogAnswers(Function<UUID, MockResponse> behaviour) {
        catalogBehaviour = behaviour;
    }

    private static MockResponse serveFromCatalog(UUID productId) {
        StubProduct product = CATALOG.get(productId);
        if (product == null) {
            return new MockResponse()
                    .setResponseCode(404)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":404,\"message\":\"Product %s does not exist\"}".formatted(productId));
        }
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"%s","sku":"%s","name":"%s","description":null,"price":%s,"availableQuantity":500}"""
                        .formatted(product.id(), product.sku(), product.name(), product.price().toPlainString()));
    }

    private static UUID productIdIn(String path) {
        String tail = path.substring(CATALOG_PATH_PREFIX.length());
        int query = tail.indexOf('?');
        String id = query < 0 ? tail : tail.substring(0, query);
        try {
            return UUID.fromString(id);
        }
        catch (IllegalArgumentException e) {
            return new UUID(0L, 0L);
        }
    }

    private static String stubUrl(String path) {
        return "http://localhost:" + HTTP_STUB.getPort() + path;
    }

    // ------------------------------------------------------------------ запросы

    /**
     * @param lines количество по идентификаторам товаров в порядке добавления
     * @return тело запроса на оформление
     */
    protected static String orderBody(Object... lines) {
        StringBuilder body = new StringBuilder("{\"items\":[");
        for (int i = 0; i < lines.length; i += 2) {
            if (i > 0) {
                body.append(',');
            }
            body.append("{\"productId\":\"%s\",\"quantity\":%s}".formatted(lines[i], lines[i + 1]));
        }
        return body.append("]}").toString();
    }

    /**
     * @param body тело ответа
     * @return идентификатор заказа
     */
    protected JsonNode parse(String body) throws IOException {
        return json.readTree(body);
    }

    /**
     * Опрос завершится, как только условие выполнится, либо истечёт срок ожидания.
     * @param condition проверяемое условие
     * @param timeout предельное время ожидания
     */
    protected static void awaitUntil(Duration timeout, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting", e);
            }
        }
        throw new AssertionError("Condition did not become true within " + timeout);
    }

    // ------------------------------------------------------------------ токены

    /** @return JWT с ролью USER для нового случайного пользователя */
    protected static String userToken() {
        return token(UUID.randomUUID(), "USER");
    }

    /** @return JWT с ролью ADMIN для нового случайного пользователя */
    protected static String adminToken() {
        return token(UUID.randomUUID(), "ADMIN");
    }

    /**
     * @param userId поле {@code sub}
     * @param roles роли пользователя
     * @return подписанный токен
     */
    protected static String token(UUID userId, String... roles) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(30))))
                .claim("email", "user@orders.local")
                .claim("name", "Test User")
                .claim("roles", List.of(roles))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(SIGNING_KEY.toRSAPrivateKey()));
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
