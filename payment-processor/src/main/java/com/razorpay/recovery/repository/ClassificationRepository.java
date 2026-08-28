package com.razorpay.recovery.repository;

import com.razorpay.recovery.model.Classification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ClassificationRepository extends JpaRepository<Classification, Long> {
    Optional<Classification> findByTransactionId(String transactionId);
}
