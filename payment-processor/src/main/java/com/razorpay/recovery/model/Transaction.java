package com.razorpay.recovery.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    @Id
    private String transactionId;
    private String merchantId;
    private String customerIdHash;
    private Double amount;
    private String paymentMethod;
    private String failureCode;
    private String failureMessage;
    private Integer retryCount;
    private String status; // "PENDING", "FAILED", "RECOVERED", "ESCALATED", "NUDGED"
    private Double groundTruthPRecovery; // seed P(recovery) for EV validation
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
