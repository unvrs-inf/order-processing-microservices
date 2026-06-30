package com.example.orders.support.error;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Подключает общую обработку ошибок контроллеров и Spring Security для servlet-сервисов.
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ServiceErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ApiExceptionHandler apiExceptionHandler() {
        return new ApiExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiErrorHttpWriter apiErrorHttpWriter(ObjectMapper objectMapper) {
        return new ApiErrorHttpWriter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(AuthenticationEntryPoint.class)
    public RestAuthenticationEntryPoint restAuthenticationEntryPoint(ApiErrorHttpWriter writer) {
        return new RestAuthenticationEntryPoint(writer);
    }

    @Bean
    @ConditionalOnMissingBean(AccessDeniedHandler.class)
    public RestAccessDeniedHandler restAccessDeniedHandler(ApiErrorHttpWriter writer) {
        return new RestAccessDeniedHandler(writer);
    }
}
