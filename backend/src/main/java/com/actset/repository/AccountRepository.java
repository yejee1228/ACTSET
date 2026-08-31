package com.actset.repository;

import com.actset.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Optional<Account> findByEmail(String email);
    boolean existsByEmail(String email);
    List<Account> findByEmailContainingIgnoreCaseOrderByCreatedAtDesc(String email);
}
