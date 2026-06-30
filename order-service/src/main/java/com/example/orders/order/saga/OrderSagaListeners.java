package com.example.orders.order.saga;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.support.messaging.EventConsumerAutoConfiguration;
import com.example.orders.support.messaging.EventEnvelopeReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Ответы inventory-service и payment-service для оркестратора.
 */
@Component
class OrderSagaListeners {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaListeners.class);

    private final EventEnvelopeReader reader;
    private final OrderSaga saga;

    OrderSagaListeners(EventEnvelopeReader reader, OrderSaga saga) {
        this.reader = reader;
        this.saga = saga;
    }

    /**
     * @param record запись из {@code inventory.events}
     */
    @KafkaListener(
            id = "order-inventory-events",
            topics = Topics.INVENTORY_EVENTS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onInventoryEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case StockReserved reserved -> saga.stockReserved(envelope.eventId(), reserved);
            case StockRejected rejected -> saga.stockRejected(envelope.eventId(), rejected);
            default -> log.debug("Ignoring {} on {}: not part of the order saga",
                    envelope.eventType(), record.topic());
        }
    }

    /**
     * @param record запись из {@code payment.events}
     */
    @KafkaListener(
            id = "order-payment-events",
            topics = Topics.PAYMENT_EVENTS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case PaymentCompleted completed -> saga.paymentCompleted(envelope.eventId(), completed);
            case PaymentFailed failed -> saga.paymentFailed(envelope.eventId(), failed);
            case PaymentRefunded refunded -> saga.paymentRefunded(envelope.eventId(), refunded);
            default -> log.debug("Ignoring {} on {}: not part of the order saga",
                    envelope.eventType(), record.topic());
        }
    }
}
