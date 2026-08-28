package com.actset.repository;

import com.actset.domain.CustomerInquiry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CustomerInquiryRepository extends JpaRepository<CustomerInquiry, UUID> {
    List<CustomerInquiry> findAllByOrderByCreatedAtDesc();
}
