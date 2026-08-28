package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.domain.Job;
import com.actset.domain.Project;
import com.actset.repository.AccountRepository;
import com.actset.repository.CreditTransactionRepository;
import com.actset.repository.ProjectRepository;
import com.actset.security.CurrentUser;
import com.actset.service.AdminService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 관리자 백오피스(1-20). SecurityConfig에서 /api/v1/admin/**는 이미 ROLE_ADMIN만
 * 통과하도록 막아뒀다(1-4 커밋) — 여기서는 감사 로그 기록에 집중한다.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final AdminService adminService;

    public AdminController(AccountRepository accountRepository, ProjectRepository projectRepository,
                            CreditTransactionRepository creditTransactionRepository, AdminService adminService) {
        this.accountRepository = accountRepository;
        this.projectRepository = projectRepository;
        this.creditTransactionRepository = creditTransactionRepository;
        this.adminService = adminService;
    }

    @GetMapping("/accounts")
    public Map<String, Object> accounts(@RequestParam(required = false) String q) {
        List<Account> accounts = (q == null || q.isBlank())
                ? accountRepository.findAll()
                : accountRepository.findByEmailContainingIgnoreCaseOrderByCreatedAtDesc(q);
        List<Map<String, Object>> items = accounts.stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId().toString());
            m.put("email", a.getEmail());
            m.put("role", a.getRole());
            m.put("status", a.getStatus());
            m.put("credit_balance", a.getCreditBalance());
            m.put("created_at", a.getCreatedAt().toString());
            return m;
        }).toList();
        return Map.of("items", items);
    }

    public record CreditGrantRequest(int amount, String reason) {
    }

    @PostMapping("/accounts/{id}/credits")
    public Map<String, Object> grantCredits(@PathVariable UUID id, @RequestBody CreditGrantRequest req) {
        adminService.grantCredits(CurrentUser.id(), id, req.amount(), req.reason());
        Account account = accountRepository.findById(id).orElseThrow(ApiException::notFound);
        return Map.of("credit_balance", account.getCreditBalance());
    }

    @GetMapping("/jobs")
    public Map<String, Object> jobs() {
        List<Job> jobs = adminService.failedOrStuckJobs();
        List<Map<String, Object>> items = jobs.stream().map(j -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId().toString());
            m.put("kind", j.getKind());
            m.put("status", j.getStatus());
            m.put("error", j.getError());
            m.put("attempts", j.getAttempts());
            m.put("created_at", j.getCreatedAt().toString());
            return m;
        }).toList();
        return Map.of("items", items);
    }

    @PostMapping("/jobs/{id}/retry")
    public void retryJob(@PathVariable UUID id) {
        adminService.retryJob(CurrentUser.id(), id);
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> project(@PathVariable UUID id) {
        Project project = projectRepository.findById(id).orElseThrow(ApiException::notFound);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", project.getId().toString());
        m.put("owner_id", project.getOwnerId().toString());
        m.put("status", project.getStatus());
        m.put("main_title", project.getMainTitle());
        m.put("performance_info", project.getPerformanceInfo());
        return m;
    }

    /** 크레딧 소비 분포 — 정식 집계는 6-8에서 확장한다. */
    @GetMapping("/usage")
    public Map<String, Object> usage() {
        var grouped = creditTransactionRepository.findAll().stream()
                .collect(Collectors.groupingBy(com.actset.domain.CreditTransaction::getType,
                        Collectors.summingInt(t -> Math.abs(t.getAmount()))));
        return Map.of("by_type", grouped);
    }
}
