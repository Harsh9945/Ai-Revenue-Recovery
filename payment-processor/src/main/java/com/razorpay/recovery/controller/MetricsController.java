package com.razorpay.recovery.controller;

import com.razorpay.recovery.model.AuditLog;
import com.razorpay.recovery.model.RecoveryAction;
import com.razorpay.recovery.model.Transaction;
import com.razorpay.recovery.repository.AuditLogRepository;
import com.razorpay.recovery.repository.RecoveryActionRepository;
import com.razorpay.recovery.repository.TransactionRepository;
import com.razorpay.recovery.service.RecoveryEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class MetricsController {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private RecoveryActionRepository recoveryActionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RecoveryEngine recoveryEngine;

    /**
     * Aggregate recovery statistics.
     */
    @GetMapping("/metrics/summary")
    public ResponseEntity<Map<String, Object>> getMetricsSummary() {
        List<Transaction> allTx = transactionRepository.findAll();
        
        long totalCount = allTx.size();
        long recoveredCount = allTx.stream().filter(t -> "RECOVERED".equals(t.getStatus())).count();
        long escalatedCount = allTx.stream().filter(t -> "ESCALATED".equals(t.getStatus())).count();
        long failedCount = allTx.stream().filter(t -> "FAILED".equals(t.getStatus())).count();
        long pendingCount = allTx.stream().filter(t -> "PENDING".equals(t.getStatus())).count();

        double totalRecoveredAmt = transactionRepository.sumRecoveredAmount();
        double falseRetryCost = recoveryActionRepository.sumFalseRetryCost();
        
        // Calculate average time-to-recovery in seconds
        double avgLatencySeconds = 0.0;
        List<Transaction> recoveredTx = allTx.stream()
                .filter(t -> "RECOVERED".equals(t.getStatus()))
                .collect(Collectors.toList());

        if (!recoveredTx.isEmpty()) {
            double totalDurationSeconds = 0;
            for (Transaction tx : recoveredTx) {
                List<RecoveryAction> actions = recoveryActionRepository.findByTransactionId(tx.getTransactionId());
                Optional<RecoveryAction> successAction = actions.stream()
                        .filter(a -> "SUCCESS".equals(a.getOutcome()))
                        .findFirst();
                if (successAction.isPresent()) {
                    Duration duration = Duration.between(tx.getCreatedAt(), successAction.get().getExecutedAt());
                    totalDurationSeconds += Math.max(0, duration.getSeconds());
                }
            }
            avgLatencySeconds = totalDurationSeconds / recoveredTx.size();
        }

        double recoveryRate = totalCount > 0 ? ((double) recoveredCount / totalCount) * 100 : 0.0;

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalIngested", totalCount);
        summary.put("recoveredCount", recoveredCount);
        summary.put("escalatedCount", escalatedCount);
        summary.put("failedCount", failedCount);
        summary.put("pendingCount", pendingCount);
        summary.put("recoveryRate", recoveryRate);
        summary.put("revenueRecovered", totalRecoveredAmt);
        summary.put("falseRetryCost", falseRetryCost);
        summary.put("avgRecoveryLatency", avgLatencySeconds);

        return ResponseEntity.ok(summary);
    }

    /**
     * Get all transactions.
     */
    @GetMapping("/transactions")
    public ResponseEntity<List<Transaction>> getTransactions(@RequestParam(required = false) String status) {
        if (status != null && !status.isEmpty()) {
            return ResponseEntity.ok(transactionRepository.findByStatus(status.toUpperCase()));
        }
        return ResponseEntity.ok(transactionRepository.findAllByOrderByCreatedAtDesc());
    }

    /**
     * Get details of a single transaction.
     */
    @GetMapping("/transactions/{id}")
    public ResponseEntity<Transaction> getTransactionById(@PathVariable String id) {
        return transactionRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get the audit timeline for a transaction.
     */
    @GetMapping("/transactions/{id}/audit")
    public ResponseEntity<List<AuditLog>> getTransactionAuditTimeline(@PathVariable String id) {
        return ResponseEntity.ok(auditLogRepository.findByTransactionIdOrderByTimestampAsc(id));
    }

    /**
     * Get recovery actions taken on a transaction.
     */
    @GetMapping("/transactions/{id}/actions")
    public ResponseEntity<List<RecoveryAction>> getTransactionActions(@PathVariable String id) {
        return ResponseEntity.ok(recoveryActionRepository.findByTransactionId(id));
    }

    /**
     * Get the exception queue list (escalated transactions).
     */
    @GetMapping("/exceptions")
    public ResponseEntity<List<Transaction>> getExceptions() {
        return ResponseEntity.ok(transactionRepository.findByStatus("ESCALATED"));
    }

    /**
     * Handle manual action on escalated transaction.
     */
    @PostMapping("/exceptions/{id}/resolve")
    public ResponseEntity<Map<String, String>> resolveException(
            @PathVariable String id, 
            @RequestBody Map<String, String> request) {
        
        String action = request.get("action");
        if (action == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Action field is required ('FORCE_RETRY' or 'ACCEPT_LOSS')."));
        }

        try {
            if ("FORCE_RETRY".equalsIgnoreCase(action)) {
                recoveryEngine.forceRetry(id);
                return ResponseEntity.ok(Map.of("message", "Force retry triggered successfully."));
            } else if ("ACCEPT_LOSS".equalsIgnoreCase(action)) {
                recoveryEngine.acceptLoss(id);
                return ResponseEntity.ok(Map.of("message", "Transaction marked as failed (loss accepted)."));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid action. Supported: FORCE_RETRY, ACCEPT_LOSS"));
            }
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to resolve exception: " + e.getMessage()));
        }
    }

    /**
     * Ingest payment failure events directly via REST API (supports both custom events and Razorpay Webhook format).
     */
    @PostMapping("/events/ingest")
    public ResponseEntity<Map<String, String>> ingestEvent(@RequestBody Map<String, Object> payload) {
        System.out.println("=== WEBHOOK RECEIVED OVER TUNNEL ===");
        System.out.println("Payload: " + payload);
        try {
            // Check if it is the official Razorpay Webhook format
            if (payload.containsKey("event")) {
                String rzpEvent = (String) payload.get("event");
                Map<String, Object> innerPayload = (Map<String, Object>) payload.get("payload");
                
                if (innerPayload != null && innerPayload.containsKey("payment")) {
                    Map<String, Object> payment = (Map<String, Object>) innerPayload.get("payment");
                    Map<String, Object> entity = (Map<String, Object>) payment.get("entity");
                    
                    if (entity != null) {
                        String rzpId = (String) entity.get("id");
                        Number amountPaisa = (Number) entity.get("amount");
                        double amount = amountPaisa.doubleValue() / 100.0;
                        String method = (String) entity.get("method");
                        
                        // Look for our custom transaction_id inside metadata notes or reference_id
                        Map<String, String> notes = null;
                        if (entity.get("notes") instanceof Map) {
                            notes = (Map<String, String>) entity.get("notes");
                        }
                        String referenceId = (String) entity.get("reference_id");
                        String txIdTemp = rzpId;
                        if (referenceId != null && !referenceId.isEmpty()) {
                            txIdTemp = referenceId;
                        } else if (notes != null && notes.containsKey("transaction_id")) {
                            txIdTemp = notes.get("transaction_id");
                        }
                        final String txId = txIdTemp;

                        if ("payment.failed".equals(rzpEvent)) {
                            String code = (String) entity.get("error_code");
                            String msg = (String) entity.get("error_description");
                            if (code == null) code = "UNKNOWN";
                            if (msg == null) msg = "Razorpay payment failure.";

                            Transaction transaction = Transaction.builder()
                                    .transactionId(txId)
                                    .merchantId("mer_live_razor")
                                    .customerIdHash("cust_" + rzpId.substring(Math.max(0, rzpId.length() - 6)))
                                    .amount(amount)
                                    .paymentMethod(method != null ? method.toUpperCase() : "CARD")
                                    .failureCode(code)
                                    .failureMessage(msg)
                                    .groundTruthPRecovery(0.75) // Default PRecovery for webhooks
                                    .build();

                            new Thread(() -> {
                                try {
                                    recoveryEngine.processFailureEvent(transaction);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }).start();

                            return ResponseEntity.ok(Map.of("status", "INGESTED", "transactionId", txId));
                        } else if ("payment.captured".equals(rzpEvent)) {
                            // Payment link was successfully captured
                            new Thread(() -> {
                                try {
                                    recoveryEngine.resolveAsCaptured(txId, amount);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            }).start();

                            return ResponseEntity.ok(Map.of("status", "RECOVERED", "transactionId", txId));
                        }
                    }
                }
            }

            // Fallback: Parse our custom simulation dataset payload format
            String txId = (String) payload.get("transactionId");
            String merchantId = (String) payload.get("merchantId");
            String custId = (String) payload.get("customerIdHash");

            Double amount = null;
            if (payload.get("amount") instanceof Number) {
                amount = ((Number) payload.get("amount")).doubleValue();
            }

            String method = (String) payload.get("paymentMethod");
            String code = (String) payload.get("failureCode");
            String msg = (String) payload.get("failureMessage");

            Double groundTruth = null;
            if (payload.get("groundTruthPRecovery") instanceof Number) {
                groundTruth = ((Number) payload.get("groundTruthPRecovery")).doubleValue();
            }

            Transaction transaction = Transaction.builder()
                    .transactionId(txId)
                    .merchantId(merchantId)
                    .customerIdHash(custId)
                    .amount(amount)
                    .paymentMethod(method)
                    .failureCode(code)
                    .failureMessage(msg)
                    .groundTruthPRecovery(groundTruth)
                    .build();

            new Thread(() -> {
                try {
                    recoveryEngine.processFailureEvent(transaction);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }).start();

            return ResponseEntity.ok(Map.of("status", "INGESTED", "transactionId", txId));
        } catch (Exception e) {
            System.err.println("=== WEBHOOK PARSE EXCEPTION ===");
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Failed parsing webhook payload: " + e.getMessage()));
        }
    }
}
