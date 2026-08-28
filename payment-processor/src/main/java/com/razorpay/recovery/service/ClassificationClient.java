package com.razorpay.recovery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class ClassificationClient {
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${services.classification-url}")
    private String classificationServiceUrl;

    public ClassificationResponse classify(String transactionId, String failureCode, String failureMessage) {
        String url = classificationServiceUrl + "/classify";
        ClassificationRequest request = new ClassificationRequest(transactionId, failureCode, failureMessage);
        try {
            return restTemplate.postForObject(url, request, ClassificationResponse.class);
        } catch (Exception e) {
            // Fallback to safe local rule-based parsing if FastAPI is unavailable
            return fallbackClassify(failureCode, failureMessage);
        }
    }

    public String getNudgeMessage(String transactionId, String failureCode, String failureMessage, Double amount, String paymentMethod) {
        String url = classificationServiceUrl + "/nudge";
        NudgeRequest request = new NudgeRequest(transactionId, failureCode, failureMessage, amount, paymentMethod);
        try {
            NudgeResponse response = restTemplate.postForObject(url, request, NudgeResponse.class);
            return response != null ? response.getNudgeMessage() : "Payment failed. Please try another method.";
        } catch (Exception e) {
            return "Payment failed. Please retry using UPI or a different card.";
        }
    }

    private ClassificationResponse fallbackClassify(String failureCode, String failureMessage) {
        String code = failureCode != null ? failureCode.toUpperCase() : "";
        if (code.equals("BANK_TIMEOUT") || code.equals("NETWORK_DROP") || code.equals("GATEWAY_TIMEOUT") 
            || code.equals("SWITCH_UNAVAILABLE") || code.equals("OTP_TIMEOUT") || code.equals("OTP_NOT_DELIVERED")) {
            return new ClassificationResponse("soft", code.contains("OTP") ? "Business Decline" : "Technical Decline", "rule", 1.0, "Fallback rule match");
        } else {
            return new ClassificationResponse("hard", "Business Decline", "rule", 1.0, "Fallback rule match");
        }
    }

    public static class ClassificationRequest {
        public String transactionId;
        public String failureCode;
        public String failureMessage;

        public ClassificationRequest(String transactionId, String failureCode, String failureMessage) {
            this.transactionId = transactionId;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
        }
    }

    public static class ClassificationResponse {
        private String classifiedAs;
        private String npciCategory;
        private String method;
        private Double confidence;
        private String rationale;

        public ClassificationResponse() {}

        public ClassificationResponse(String classifiedAs, String npciCategory, String method, Double confidence, String rationale) {
            this.classifiedAs = classifiedAs;
            this.npciCategory = npciCategory;
            this.method = method;
            this.confidence = confidence;
            this.rationale = rationale;
        }

        public String getClassifiedAs() { return classifiedAs; }
        public void setClassifiedAs(String classifiedAs) { this.classifiedAs = classifiedAs; }
        public String getNpciCategory() { return npciCategory; }
        public void setNpciCategory(String npciCategory) { this.npciCategory = npciCategory; }
        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }
        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
        public String getRationale() { return rationale; }
        public void setRationale(String rationale) { this.rationale = rationale; }
    }

    public static class NudgeRequest {
        public String transactionId;
        public String failureCode;
        public String failureMessage;
        public Double amount;
        public String paymentMethod;

        public NudgeRequest(String transactionId, String failureCode, String failureMessage, Double amount, String paymentMethod) {
            this.transactionId = transactionId;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
            this.amount = amount;
            this.paymentMethod = paymentMethod;
        }
    }

    public static class NudgeResponse {
        private String nudgeMessage;
        public NudgeResponse() {}
        public String getNudgeMessage() { return nudgeMessage; }
        public void setNudgeMessage(String nudgeMessage) { this.nudgeMessage = nudgeMessage; }
    }
}
