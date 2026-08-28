package com.actset.web;

import com.actset.domain.CustomerInquiry;
import com.actset.domain.FeedbackSubmission;
import com.actset.repository.CustomerInquiryRepository;
import com.actset.repository.FeedbackSubmissionRepository;
import com.actset.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 6-5 피드백 수집(인터뷰 접점) + 6-5b 고객 문의 창구 — 서로 다른 목적의 별개 채널(docs/13). */
@RestController
public class FeedbackController {

    private final FeedbackSubmissionRepository feedbackRepository;
    private final CustomerInquiryRepository inquiryRepository;

    public FeedbackController(FeedbackSubmissionRepository feedbackRepository, CustomerInquiryRepository inquiryRepository) {
        this.feedbackRepository = feedbackRepository;
        this.inquiryRepository = inquiryRepository;
    }

    public record FeedbackRequest(String message, String contact) {
    }

    @PostMapping("/api/v1/feedback")
    public ResponseEntity<Void> submitFeedback(@RequestBody FeedbackRequest req) {
        FeedbackSubmission fb = new FeedbackSubmission();
        fb.setAccountId(CurrentUser.id());
        fb.setMessage(req.message());
        fb.setContact(req.contact());
        feedbackRepository.save(fb);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    public record InquiryRequest(String subject, String message, String contact) {
    }

    @PostMapping("/api/v1/inquiries")
    public ResponseEntity<Void> submitInquiry(@RequestBody InquiryRequest req) {
        CustomerInquiry inquiry = new CustomerInquiry();
        inquiry.setAccountId(CurrentUser.id());
        inquiry.setSubject(req.subject());
        inquiry.setMessage(req.message());
        inquiry.setContact(req.contact());
        inquiryRepository.save(inquiry);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** 관리자 수신함(6-5b "수신함"). */
    @GetMapping("/api/v1/admin/inquiries")
    public Map<String, Object> listInquiries() {
        var items = inquiryRepository.findAllByOrderByCreatedAtDesc().stream().map(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.getId().toString());
            m.put("subject", i.getSubject());
            m.put("message", i.getMessage());
            m.put("contact", i.getContact());
            m.put("status", i.getStatus());
            m.put("created_at", i.getCreatedAt().toString());
            return m;
        }).toList();
        return Map.of("items", items);
    }

    public record InquiryStatusUpdate(String status) {
    }

    @PatchMapping("/api/v1/admin/inquiries/{id}")
    public ResponseEntity<Void> updateInquiryStatus(@PathVariable UUID id, @RequestBody InquiryStatusUpdate req) {
        CustomerInquiry inquiry = inquiryRepository.findById(id).orElseThrow(com.actset.common.ApiException::notFound);
        inquiry.setStatus(req.status());
        inquiryRepository.save(inquiry);
        return ResponseEntity.noContent().build();
    }
}
