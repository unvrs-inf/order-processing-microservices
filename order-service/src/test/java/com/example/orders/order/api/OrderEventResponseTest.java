package com.example.orders.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OrderEventResponseTest {

    @Test
    void theTraceIdIsTheMiddleSegmentOfAWellFormedTraceparent() {
        assertThat(OrderEventResponse.traceIdOf(
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "garbage",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-01",                  // Слишком мало частей.
            "01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", // Неизвестная версия.
            "00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01", // Идентификатор трейса в верхнем регистре.
            "00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01",  // 31 знак в идентификаторе трейса.
            "00-00000000000000000000000000000000-00f067aa0ba902b7-01", // Идентификатор трейса из нулей.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00F067aa0ba902b7-01", // Идентификатор родителя в верхнем регистре.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01",  // 15 знаков в идентификаторе родителя.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01", // Идентификатор родителя из нулей.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0A", // Флаги в верхнем регистре.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-0g", // Флаг содержит символ вне шестнадцатеричной системы.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",  // Флаг из одного символа.
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01suffix",
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01 ",
            " 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    })
    void aMissingOrMalformedTraceparentYieldsNoTraceId(String traceparent) {
        assertThat(OrderEventResponse.traceIdOf(traceparent)).isNull();
    }
}
