package com.razorpay.recovery.repository;

import com.razorpay.recovery.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, String> {
    List<Transaction> findByStatus(String status);
    
    @Query("SELECT COALESCE(SUM(t.amount), 0.0) FROM Transaction t WHERE t.status = 'RECOVERED'")
    Double sumRecoveredAmount();
    
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.status = 'ESCALATED'")
    Long countEscalatedTransactions();

    List<Transaction> findAllByOrderByCreatedAtDesc();
}
