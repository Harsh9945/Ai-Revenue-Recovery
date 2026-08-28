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
@Table(name = "classifications")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Classification {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String transactionId;
    private String classifiedAs; // "soft" or "hard"
    private String npciCategory; // "Technical Decline" or "Business Decline"
    private String method;       // "rule" or "llm"
    private Double confidence;
    private String rationale;
    private LocalDateTime createdAt;
}
