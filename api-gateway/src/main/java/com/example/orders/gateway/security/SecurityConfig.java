package com.example.orders.gateway.security;

import com.example.orders.gateway.config.GatewayEdgeProperties;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * Аутентификация и авторизация на шлюзе.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(GatewayEdgeProperties.class)
class SecurityConfig {

    /** Роли из auth-service всегда приходят в виде массива, например {@code ["USER"]}. */
    static final String ROLES_CLAIM = "roles";

    /**
     * Необязательный ключ идемпотентности для {@code POST /api/orders}. Шлюз должен разрешать этот заголовок и при браузерном запросе.
     */
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            ReactiveJwtDecoder jwtDecoder,
            CorsConfigurationSource corsConfigurationSource,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                // Состояние хранится в токене. Сессии и сохранённые запросы не используются.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .authorizeExchange(exchanges -> exchanges
                        // Фильтр CORS уже обработал preflight. Проверка ниже относится к основному запросу.
                        .pathMatchers(HttpMethod.OPTIONS).permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        // Чтение публичного каталога доступно без учётной записи.
                        .pathMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Таблица маршрутов доступна администраторам.
                        .pathMatchers("/actuator/gateway/**").hasRole("ADMIN")
                        // Отдельные правила доступа для операций управления складом.
                        .pathMatchers("/api/inventory/**").hasRole("ADMIN")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt
                                .jwtDecoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }

    /**
     * Ключи JWKS загружаются при первом запросе и кешируются.
     * @param properties параметры конфигурации
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(GatewayEdgeProperties properties) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(properties.security().jwksUri())
                .build();
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(properties.security().issuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    /**
     * @param properties разрешённые источники браузерных запросов
     * @return правила CORS для {@code /**} с точным списком источников
     */
    @Bean
    CorsConfigurationSource corsConfigurationSource(GatewayEdgeProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name()));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION,
                HttpHeaders.CONTENT_TYPE,
                IDEMPOTENCY_KEY_HEADER));
        configuration.setExposedHeaders(List.of(GatewayHeaders.CORRELATION_ID));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Преобразует роли из {@code roles}, например {@code ["ADMIN"]}, в полномочие {@code ROLE_ADMIN}. */
    private static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
