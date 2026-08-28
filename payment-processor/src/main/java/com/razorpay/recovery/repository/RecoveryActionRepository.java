package com.razorpay.recovery.repository;

import com.razorpay.recovery.model.RecoveryAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface RecoveryActionRepository extends JpaRepository<RecoveryAction, Long> {
    List<RecoveryAction> findByTransactionId(String transactionId);
    
    @Query("SELECT COALESCE(SUM(r.costOfAttempt), 0.0) FROM RecoveryAction r")
    Double sumTotalAttemptCost();
    
    @Query("SELECT COALESCE(SUM(r.costOfAttempt), 0.0) FROM RecoveryAction r JOIN Transaction t ON r.transactionId = t.transactionId WHERE t.status = 'FAILED'")
    Double sumFalseRetryCost();
}
