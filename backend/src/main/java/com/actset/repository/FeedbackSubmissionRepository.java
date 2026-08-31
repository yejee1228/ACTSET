package com.actset.repository;

import com.actset.domain.FeedbackSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FeedbackSubmissionRepository extends JpaRepository<FeedbackSubmission, UUID> {
}
