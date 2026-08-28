package com.razorpay.recovery.repository;

import com.razorpay.recovery.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByTransactionIdOrderByTimestampAsc(String transactionId);
}
