package com.vsellen.fundtransfer.kafka;

import com.vsellen.fundtransfer.event.TransferInitiatedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransferEventProducer {

    static final String TOPIC = "transfer-initiated";

    private final KafkaTemplate<String, TransferInitiatedEvent> kafkaTemplate;

    public TransferEventProducer(KafkaTemplate<String, TransferInitiatedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishTransferInitiated(TransferInitiatedEvent event) {
        // Key by transferId so retries/duplicates for the same transfer stay on one
        // partition and are processed in order by a single consumer thread.
        kafkaTemplate.send(TOPIC, event.getTransferId(), event);
    }
}
