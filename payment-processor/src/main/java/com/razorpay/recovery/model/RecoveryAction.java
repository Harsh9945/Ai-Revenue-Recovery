package com.razorpay.recovery.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "recovery_actions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecoveryAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String transactionId;
    private String actionTaken; // "RETRY_IMMEDIATE", "RETRY_DELAYED", "RE_ROUTE", "CUSTOMER_NUDGE", "ESCALATE"
    private Integer retryAttemptNo;
    private LocalDateTime executedAt;
    private String outcome; // "SUCCESS", "FAILED", "PENDING"
    private Double costOfAttempt;
    private String nudgeMessage; // Store sent nudge message if any
}
