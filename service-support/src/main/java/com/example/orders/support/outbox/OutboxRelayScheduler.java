package com.example.orders.support.outbox;

import org.springframework.scheduling.annotation.Scheduled;

/**
 * Вызывает обработчик публикации отдельно от {@link OutboxRelay}, чтобы опрос можно было отключить.
 */
public class OutboxRelayScheduler {

    private final OutboxRelay relay;

    /**
     * @param relay обработчик, вызываемый планировщиком
     */
    public OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(
            fixedDelayString = "${outbox.poll-interval-ms:500}",
            initialDelayString = "${outbox.poll-interval-ms:500}")
    void poll() {
        relay.publishPending();
    }

    @Scheduled(
            fixedDelayString = "${outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${outbox.cleanup-interval-ms:3600000}")
    void cleanUp() {
        relay.purgePublished();
    }
}
