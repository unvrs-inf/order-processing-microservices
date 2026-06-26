package com.example.orders.payment.saga;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.Topics;
import com.example.orders.support.messaging.EventConsumerAutoConfiguration;
import com.example.orders.support.messaging.EventEnvelopeReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Платёжный сервис выполняет команды оркестратора. Решения об оплате и возврате принимаются в Saga.
 */
@Component
class PaymentListeners {

    private static final Logger log = LoggerFactory.getLogger(PaymentListeners.class);

    private final EventEnvelopeReader reader;
    private final PaymentSaga saga;

    PaymentListeners(EventEnvelopeReader reader, PaymentSaga saga) {
        this.reader = reader;
        this.saga = saga;
    }

    /**
     * @param record запись из {@code payment.commands}
     */
    @KafkaListener(
            id = "payment-commands",
            topics = Topics.PAYMENT_COMMANDS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onCommand(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case ProcessPayment charge -> saga.processPayment(envelope.eventId(), charge);
            case RefundPayment refund -> saga.refundPayment(envelope.eventId(), refund);
            default -> log.debug("Ignoring {} on {}: not a payment command",
                    envelope.eventType(), record.topic());
        }
    }
}
