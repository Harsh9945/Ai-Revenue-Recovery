package com.razorpay.recovery.service;

import com.razorpay.recovery.model.AuditLog;
import com.razorpay.recovery.model.Classification;
import com.razorpay.recovery.model.RecoveryAction;
import com.razorpay.recovery.model.Transaction;
import com.razorpay.recovery.repository.AuditLogRepository;
import com.razorpay.recovery.repository.ClassificationRepository;
import com.razorpay.recovery.repository.RecoveryActionRepository;
import com.razorpay.recovery.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class RecoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryEngine.class);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ClassificationRepository classificationRepository;

    @Autowired
    private RecoveryActionRepository recoveryActionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private ClassificationClient classificationClient;

    @Value("${recovery.max-retries}")
    private int maxRetries;

    @Value("${recovery.value-escalation-threshold-in-inr}")
    private double valueEscalationThreshold;

    @Value("${recovery.bank-throttle-risk-cost-in-inr}")
    private double bankThrottleRiskCost;

    @Value("${recovery.customer-friction-base-cost-in-inr}")
    private double customerFrictionBaseCost;

    /**
     * Entry point to process a new or updated payment failure event.
     */
    @Transactional
    public void processFailureEvent(Transaction transaction) {
        log.info("Processing failure event for transaction ID: {}", transaction.getTransactionId());

        // 1. Check if transaction already exists and is in final state
        Optional<Transaction> existingOpt = transactionRepository.findById(transaction.getTransactionId());
        Transaction tx;
        if (existingOpt.isPresent()) {
            tx = existingOpt.get();
            if ("RECOVERED".equals(tx.getStatus()) || "ESCALATED".equals(tx.getStatus()) || "FAILED".equals(tx.getStatus())) {
                log.info("Transaction {} already in final state: {}. Dropping event.", tx.getTransactionId(), tx.getStatus());
                return;
            }
            tx.setFailureCode(transaction.getFailureCode());
            tx.setFailureMessage(transaction.getFailureMessage());
            tx.setUpdatedAt(LocalDateTime.now());
        } else {
            tx = transaction;
            tx.setStatus("PENDING");
            tx.setRetryCount(0);
            tx.setCreatedAt(LocalDateTime.now());
            tx.setUpdatedAt(LocalDateTime.now());
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("INGESTION")
                    .actor("SYSTEM")
                    .detail(String.format("Payment failure event ingested. Amount: ₹%.2f, Failure Code: %s, Message: %s", 
                            tx.getAmount(), tx.getFailureCode(), tx.getFailureMessage()))
                    .timestamp(LocalDateTime.now())
                    .build());
        }

        // 2. Value-based Escalation Guardrail
        if (tx.getAmount() > valueEscalationThreshold) {
            tx.setStatus("ESCALATED");
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("GUARDRAIL_CHECK")
                    .actor("SYSTEM")
                    .detail(String.format("Transaction amount ₹%.2f exceeds autonomous threshold (₹%.2f). Escalated to human queue.", 
                            tx.getAmount(), valueEscalationThreshold))
                    .timestamp(LocalDateTime.now())
                    .build());
            return;
        }

        // 3. Classification Check (Rule-based / LLM)
        Classification classification = classificationRepository.findByTransactionId(tx.getTransactionId())
                .orElseGet(() -> {
                    ClassificationClient.ClassificationResponse response = classificationClient.classify(
                            tx.getTransactionId(), tx.getFailureCode(), tx.getFailureMessage());
                    
                    Classification c = Classification.builder()
                            .transactionId(tx.getTransactionId())
                            .classifiedAs(response.getClassifiedAs())
                            .npciCategory(response.getNpciCategory())
                            .method(response.getMethod())
                            .confidence(response.getConfidence())
                            .rationale(response.getRationale())
                            .createdAt(LocalDateTime.now())
                            .build();
                    classificationRepository.save(c);

                    auditLogRepository.save(AuditLog.builder()
                            .transactionId(tx.getTransactionId())
                            .step("CLASSIFICATION")
                            .actor("SYSTEM")
                            .detail(String.format("Failure classified as %s (%s) via %s (confidence: %.0f%%). Rationale: %s",
                                    c.getClassifiedAs().toUpperCase(), c.getNpciCategory(), c.getMethod().toUpperCase(), 
                                    c.getConfidence() * 100, c.getRationale()))
                            .timestamp(LocalDateTime.now())
                            .build());
                    return c;
                });

        // 4. Low-Confidence Classification Escalation Guardrail
        if (classification.getConfidence() < 0.70) {
            tx.setStatus("ESCALATED");
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("GUARDRAIL_CHECK")
                    .actor("SYSTEM")
                    .detail(String.format("Classification confidence (%.0f%%) is below safety threshold (70%%). Escalated to human queue.", 
                            classification.getConfidence() * 100))
                    .timestamp(LocalDateTime.now())
                    .build());
            return;
        }

        // 5. Hard Failure Resolution (Generate payment link & Nudge, No Retry)
        if ("hard".equals(classification.getClassifiedAs())) {
            tx.setStatus("FAILED");
            transactionRepository.save(tx);

            String payLink = createRazorpayPaymentLink(tx);
            String nudgeContext = tx.getFailureMessage() + " Please pay securely using this link: " + payLink;
            String nudgeMsg = classificationClient.getNudgeMessage(tx.getTransactionId(), tx.getFailureCode(), 
                    nudgeContext, tx.getAmount(), tx.getPaymentMethod());

            recoveryActionRepository.save(RecoveryAction.builder()
                    .transactionId(tx.getTransactionId())
                    .actionTaken("CUSTOMER_NUDGE")
                    .retryAttemptNo(0)
                    .executedAt(LocalDateTime.now())
                    .outcome("SUCCESS")
                    .costOfAttempt(0.0)
                    .nudgeMessage(nudgeMsg)
                    .build());

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("NUDGE_SENT")
                    .actor("SYSTEM")
                    .detail(String.format("Hard failure detected. Skipped retries. Customer nudge sent: \"%s\"", nudgeMsg))
                    .timestamp(LocalDateTime.now())
                    .build());
            return;
        }

        // 6. Soft Failure Workflow (Check Retries + EV Gate)
        int currentAttempt = tx.getRetryCount();
        if (currentAttempt >= maxRetries) {
            tx.setStatus("ESCALATED");
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("RETRY_LIMIT")
                    .actor("SYSTEM")
                    .detail(String.format("Transaction exceeded max auto-retries (%d/%d). Routed to exception queue.", 
                            currentAttempt, maxRetries))
                    .timestamp(LocalDateTime.now())
                    .build());
            return;
        }

        // Evaluate Expected Value (EV) Gate
        double pRecovery = getPRecovery(tx.getFailureCode(), currentAttempt);
        double frictionCost = currentAttempt * 2.00;
        double totalCost = bankThrottleRiskCost + customerFrictionBaseCost + frictionCost;
        double ev = (pRecovery * tx.getAmount()) - totalCost;

        auditLogRepository.save(AuditLog.builder()
                .transactionId(tx.getTransactionId())
                .step("EV_GATE")
                .actor("SYSTEM")
                .detail(String.format("EV Gate Evaluation (Attempt %d): P(recovery)=%.2f, Amount=₹%.2f, Est Cost=₹%.2f (Throttle Risk: ₹%.2f, Friction Base: ₹%.2f, Dynamic Fatigue: ₹%.2f). Expected Value: ₹%.2f",
                        currentAttempt + 1, pRecovery, tx.getAmount(), totalCost, bankThrottleRiskCost, customerFrictionBaseCost, frictionCost, ev))
                .timestamp(LocalDateTime.now())
                .build());

        if (ev <= 0) {
            tx.setStatus("FAILED");
            transactionRepository.save(tx);

            String payLink = createRazorpayPaymentLink(tx);
            String nudgeContext = tx.getFailureMessage() + " Please pay securely using this link: " + payLink;
            String nudgeMsg = classificationClient.getNudgeMessage(tx.getTransactionId(), tx.getFailureCode(), 
                    nudgeContext, tx.getAmount(), tx.getPaymentMethod());

            recoveryActionRepository.save(RecoveryAction.builder()
                    .transactionId(tx.getTransactionId())
                    .actionTaken("CUSTOMER_NUDGE")
                    .retryAttemptNo(currentAttempt)
                    .executedAt(LocalDateTime.now())
                    .outcome("SUCCESS")
                    .costOfAttempt(0.0)
                    .nudgeMessage(nudgeMsg)
                    .build());

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("NUDGE_SENT")
                    .actor("SYSTEM")
                    .detail(String.format("EV Gate failed (EV <= 0). Auto-retry not economically viable. Customer nudge sent: \"%s\"", nudgeMsg))
                    .timestamp(LocalDateTime.now())
                    .build());
        } else {
            scheduleRetry(tx, totalCost);
        }
    }

    /**
     * Query Razorpay API in Test Mode to create a real checkout Payment Link.
     */
    private String createRazorpayPaymentLink(Transaction tx) {
        String url = "https://api.razorpay.com/v1/payment_links";
        String keyId = System.getenv("RAZORPAY_KEY_ID");
        String keySecret = System.getenv("RAZORPAY_KEY_SECRET");

        if (keyId == null || keySecret == null || keyId.contains("YOUR_KEY_ID") || keyId.isEmpty()) {
            log.warn("Razorpay API credentials not configured in environment. Returning fallback mock link.");
            return "https://razorpay.me/l/mock_" + tx.getTransactionId();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(keyId, keySecret);

        // Convert amount to paisa
        long amountInPaisa = Math.round(tx.getAmount() * 100);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("amount", amountInPaisa);
        requestBody.put("currency", "INR");
        requestBody.put("accept_partial", false);
        requestBody.put("reference_id", tx.getTransactionId());
        requestBody.put("description", "AI Revenue Recovery Payment Link for ID: " + tx.getTransactionId());

        Map<String, String> customer = new HashMap<>();
        customer.put("name", "Customer Hash " + tx.getCustomerIdHash().substring(Math.max(0, tx.getCustomerIdHash().length() - 4)));
        customer.put("contact", "+919999999999");
        customer.put("email", "customer@example.com");
        requestBody.put("customer", customer);

        Map<String, Boolean> notify = new HashMap<>();
        notify.put("sms", false);
        notify.put("email", false);
        requestBody.put("notify", notify);

        // Redirect URL upon payment success
        requestBody.put("callback_url", "http://localhost:5173/");
        requestBody.put("callback_method", "get");

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String shortUrl = (String) response.getBody().get("short_url");
                log.info("Created real Razorpay payment link: {}", shortUrl);
                return shortUrl;
            }
        } catch (Exception e) {
            log.error("Failed to call Razorpay Payment Links API: {}", e.getMessage());
        }

        return "https://razorpay.me/l/mock_" + tx.getTransactionId();
    }

    /**
     * Callback handler triggered when Razorpay sends a payment.captured webhook
     * for a previously generated payment link reference.
     */
    @Transactional
    public void resolveAsCaptured(String transactionId, double amount) {
        log.info("Resolving transaction {} as RECOVERED via Webhook payment.captured", transactionId);
        Optional<Transaction> txOpt = transactionRepository.findById(transactionId);
        if (txOpt.isPresent()) {
            Transaction tx = txOpt.get();
            if (!"RECOVERED".equals(tx.getStatus())) {
                tx.setStatus("RECOVERED");
                tx.setUpdatedAt(LocalDateTime.now());
                transactionRepository.save(tx);

                recoveryActionRepository.save(RecoveryAction.builder()
                        .transactionId(tx.getTransactionId())
                        .actionTaken("PAYMENT_LINK_SUCCESS")
                        .retryAttemptNo(tx.getRetryCount())
                        .executedAt(LocalDateTime.now())
                        .outcome("SUCCESS")
                        .costOfAttempt(0.0)
                        .nudgeMessage("Payment link completed by customer.")
                        .build());

                auditLogRepository.save(AuditLog.builder()
                        .transactionId(tx.getTransactionId())
                        .step("RECOVERY_WEBHOOK")
                        .actor("SYSTEM")
                        .detail(String.format("Razorpay Webhook 'payment.captured' received. Revenue of ₹%.2f successfully recovered via Payment Link!", amount))
                        .timestamp(LocalDateTime.now())
                        .build());
            }
        } else {
            Transaction tx = Transaction.builder()
                    .transactionId(transactionId)
                    .merchantId("mer_live_razor")
                    .customerIdHash("cust_live")
                    .amount(amount)
                    .paymentMethod("CARD")
                    .status("RECOVERED")
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(transactionId)
                    .step("INGESTION_SUCCESS")
                    .actor("SYSTEM")
                    .detail(String.format("New successful payment ingested directly via Webhook. Amount: ₹%.2f", amount))
                    .timestamp(LocalDateTime.now())
                    .build());
        }
    }

    /**
     * Delay and trigger retry simulation.
     */
    private void scheduleRetry(Transaction transaction, double costOfAttempt) {
        int delaySeconds = 3 + (transaction.getRetryCount() * 2);
        log.info("Scheduling retry for transaction {} in {} seconds.", transaction.getTransactionId(), delaySeconds);

        auditLogRepository.save(AuditLog.builder()
                .transactionId(transaction.getTransactionId())
                .step("RETRY_SCHEDULED")
                .actor("SYSTEM")
                .detail(String.format("Automatic retry scheduled to run in %d seconds (exponential backoff).", delaySeconds))
                .timestamp(LocalDateTime.now())
                .build());

        scheduler.schedule(() -> {
            executeRetry(transaction.getTransactionId(), costOfAttempt);
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /**
     * Executes the retry simulation against database.
     */
    @Transactional
    public void executeRetry(String transactionId, double costOfAttempt) {
        Optional<Transaction> txOpt = transactionRepository.findById(transactionId);
        if (txOpt.isEmpty()) return;

        Transaction tx = txOpt.get();
        if (!"PENDING".equals(tx.getStatus())) {
            log.info("Transaction {} status is no longer PENDING (status: {}). Aborting scheduled retry.", transactionId, tx.getStatus());
            return;
        }

        int nextAttempt = tx.getRetryCount() + 1;
        tx.setRetryCount(nextAttempt);
        tx.setUpdatedAt(LocalDateTime.now());

        double successProb = tx.getGroundTruthPRecovery() != null ? tx.getGroundTruthPRecovery() : getPRecovery(tx.getFailureCode(), nextAttempt - 1);
        boolean success = Math.random() <= successProb;

        RecoveryAction action = RecoveryAction.builder()
                .transactionId(tx.getTransactionId())
                .actionTaken(nextAttempt == 1 ? "RETRY_IMMEDIATE" : "RETRY_DELAYED")
                .retryAttemptNo(nextAttempt)
                .executedAt(LocalDateTime.now())
                .outcome(success ? "SUCCESS" : "FAILED")
                .costOfAttempt(costOfAttempt)
                .build();
        recoveryActionRepository.save(action);

        if (success) {
            tx.setStatus("RECOVERED");
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("RETRY_EXECUTION")
                    .actor("SYSTEM")
                    .detail(String.format("Retry attempt %d SUCCEEDED. Payment captured. Status updated to RECOVERED. Revenue of ₹%.2f recovered.", 
                            nextAttempt, tx.getAmount()))
                    .timestamp(LocalDateTime.now())
                    .build());
        } else {
            transactionRepository.save(tx);

            auditLogRepository.save(AuditLog.builder()
                    .transactionId(tx.getTransactionId())
                    .step("RETRY_EXECUTION")
                    .actor("SYSTEM")
                    .detail(String.format("Retry attempt %d FAILED. Cost of attempt: ₹%.2f.", nextAttempt, costOfAttempt))
                    .timestamp(LocalDateTime.now())
                    .build());

            processFailureEvent(tx);
        }
    }

    @Transactional
    public void forceRetry(String transactionId) {
        Optional<Transaction> txOpt = transactionRepository.findById(transactionId);
        if (txOpt.isEmpty()) return;

        Transaction tx = txOpt.get();
        if (!"ESCALATED".equals(tx.getStatus())) {
            throw new IllegalStateException("Only ESCALATED transactions can be manually retried.");
        }

        tx.setStatus("PENDING");
        tx.setUpdatedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        auditLogRepository.save(AuditLog.builder()
                .transactionId(tx.getTransactionId())
                .step("MANUAL_OVERRIDE")
                .actor("HUMAN")
                .detail("Human operator triggered FORCE RETRY. Bypassing EV-gate restrictions.")
                .timestamp(LocalDateTime.now())
                .build());

        double totalCost = bankThrottleRiskCost + customerFrictionBaseCost + (tx.getRetryCount() * 2.0);
        executeRetry(tx.getTransactionId(), totalCost);
    }

    @Transactional
    public void acceptLoss(String transactionId) {
        Optional<Transaction> txOpt = transactionRepository.findById(transactionId);
        if (txOpt.isEmpty()) return;

        Transaction tx = txOpt.get();
        if (!"ESCALATED".equals(tx.getStatus())) {
            throw new IllegalStateException("Only ESCALATED transactions can be resolved.");
        }

        tx.setStatus("FAILED");
        tx.setUpdatedAt(LocalDateTime.now());
        transactionRepository.save(tx);

        auditLogRepository.save(AuditLog.builder()
                .transactionId(tx.getTransactionId())
                .step("MANUAL_OVERRIDE")
                .actor("HUMAN")
                .detail("Human operator resolved exception: ACCEPT LOSS. Rationale: Unrecoverable failure.")
                .timestamp(LocalDateTime.now())
                .build());
    }

    private double getPRecovery(String failureCode, int attemptIndex) {
        String code = failureCode != null ? failureCode.toUpperCase() : "";
        switch (code) {
            case "BANK_TIMEOUT":
                return attemptIndex == 0 ? 0.65 : (attemptIndex == 1 ? 0.30 : 0.10);
            case "NETWORK_DROP":
                return attemptIndex == 0 ? 0.70 : (attemptIndex == 1 ? 0.35 : 0.12);
            case "GATEWAY_TIMEOUT":
                return attemptIndex == 0 ? 0.60 : (attemptIndex == 1 ? 0.25 : 0.08);
            case "SWITCH_UNAVAILABLE":
                return attemptIndex == 0 ? 0.50 : (attemptIndex == 1 ? 0.20 : 0.05);
            case "OTP_TIMEOUT":
            case "OTP_NOT_DELIVERED":
                return attemptIndex == 0 ? 0.80 : (attemptIndex == 1 ? 0.40 : 0.15);
            default:
                return attemptIndex == 0 ? 0.45 : (attemptIndex == 1 ? 0.20 : 0.05);
        }
    }
}
