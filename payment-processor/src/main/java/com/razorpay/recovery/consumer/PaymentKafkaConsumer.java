package com.razorpay.recovery.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.razorpay.recovery.model.Transaction;
import com.razorpay.recovery.service.RecoveryEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class PaymentKafkaConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentKafkaConsumer.class);

    @Autowired
    private RecoveryEngine recoveryEngine;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> localDedupCache = new java.util.concurrent.ConcurrentHashMap<>();

    @KafkaListener(topics = "payment-events", groupId = "recovery-group")
    public void consume(String message) {
        log.info("Received Kafka message: {}", message);
        try {
            PaymentEvent event = objectMapper.readValue(message, PaymentEvent.class);

            if (event.transactionId == null || event.eventType == null) {
                log.warn("Incomplete message payload received. Dropping event.");
                return;
            }

            // Ingest only failure events
            if (!"payment.failed".equalsIgnoreCase(event.eventType)) {
                log.info("Skipping event. Recovery agent is configured to process 'payment.failed' only (received '{}').", event.eventType);
                return;
            }

            // Redis Deduplication Check (FR-1) with Local Cache Fallback
            String redisKey = String.format("dedup:%s:%s", event.transactionId, event.eventType);
            boolean isNew = true;
            try {
                Boolean res = redisTemplate.opsForValue().setIfAbsent(redisKey, "PROCESSED", Duration.ofHours(24));
                isNew = res != null && res;
            } catch (Exception e) {
                log.warn("Redis is offline. Falling back to local Map-based deduplication filter.");
                isNew = localDedupCache.putIfAbsent(redisKey, true) == null;
            }

            if (!isNew) {
                log.warn("Duplicate event detected (key: {}). Event dropped.", redisKey);
                return;
            }

            // Map payload properties
            Transaction transaction = Transaction.builder()
                    .transactionId(event.transactionId)
                    .merchantId(event.merchantId)
                    .customerIdHash(event.customerIdHash)
                    .amount(event.amount)
                    .paymentMethod(event.paymentMethod)
                    .failureCode(event.failureCode)
                    .failureMessage(event.failureMessage)
                    .groundTruthPRecovery(event.groundTruthPRecovery)
                    .build();

            // Delegate to the workflow orchestrator
            recoveryEngine.processFailureEvent(transaction);

        } catch (Exception e) {
            log.error("Failed to parse or process payment failure event: {}", e.getMessage(), e);
        }
    }

    public static class PaymentEvent {
        public String transactionId;
        public String merchantId;
        public String customerIdHash;
        public Double amount;
        public String paymentMethod;
        public String failureCode;
        public String failureMessage;
        public Double groundTruthPRecovery;
        public String eventType;
    }
}
