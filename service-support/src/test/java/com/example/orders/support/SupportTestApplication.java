package com.example.orders.support;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Минимальный сервис для интеграционных тестов общих компонентов.
 */
@SpringBootApplication
@EntityScan(basePackages = "com.example.orders.support")
@EnableJpaRepositories(basePackages = "com.example.orders.support")
public class SupportTestApplication {
}
