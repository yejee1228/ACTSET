package com.actset.repository;

import com.actset.domain.CreditTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {
    List<CreditTransaction> findTop50ByAccountIdOrderByCreatedAtDesc(UUID accountId);
    boolean existsByJobIdAndType(UUID jobId, String type);
    Optional<CreditTransaction> findByJobIdAndType(UUID jobId, String type);
}
