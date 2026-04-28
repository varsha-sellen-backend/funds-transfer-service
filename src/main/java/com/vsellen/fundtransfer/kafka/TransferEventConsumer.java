package com.vsellen.fundtransfer.kafka;

import com.vsellen.fundtransfer.event.TransferInitiatedEvent;
import com.vsellen.fundtransfer.service.TransferService;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

@Component
public class TransferEventConsumer {

    private final TransferService transferService;

    public TransferEventConsumer(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Only transient failures (DB timeouts, optimistic-lock conflicts from a concurrent
     * transfer touching the same account) get retried with backoff. Anything else - a bug,
     * a business rule we didn't anticipate - goes straight to the dead-letter topic instead
     * of being retried into the same failure four times.
     */
    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10_000),
            include = {TransientDataAccessException.class, ObjectOptimisticLockingFailureException.class},
            dltStrategy = DltStrategy.FAIL_ON_ERROR
    )
    @KafkaListener(topics = TransferEventProducer.TOPIC, groupId = "funds-transfer-service")
    public void onTransferInitiated(TransferInitiatedEvent event) {
        // processTransfer is idempotent (checks status before acting), so a redelivery of
        // this same message - after a retry, or a rebalance - is safe to process again.
        transferService.processTransfer(event.getTransferId());
    }

    @DltHandler
    public void onDeadLetter(TransferInitiatedEvent event,
                              @Header(KafkaHeaders.EXCEPTION_MESSAGE) String exceptionMessage) {
        transferService.markFailed(event.getTransferId(), exceptionMessage);
    }
}
