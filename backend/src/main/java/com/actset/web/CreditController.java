package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.domain.CreditTransaction;
import com.actset.repository.AccountRepository;
import com.actset.repository.CreditTransactionRepository;
import com.actset.security.CurrentUser;
import com.actset.service.CostEstimateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 6-3 크레딧 잔액·이력 화면 + 생성 전 예상 소비량, docs/11 "6-2. 크레딧". */
@RestController
public class CreditController {

    private final AccountRepository accountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final CostEstimateService costEstimateService;

    public CreditController(AccountRepository accountRepository, CreditTransactionRepository creditTransactionRepository,
                             CostEstimateService costEstimateService) {
        this.accountRepository = accountRepository;
        this.creditTransactionRepository = creditTransactionRepository;
        this.costEstimateService = costEstimateService;
    }

    @GetMapping("/api/v1/credits")
    public Map<String, Object> credits() {
        Account account = accountRepository.findById(CurrentUser.id()).orElseThrow(ApiException::notFound);
        List<CreditTransaction> recent = creditTransactionRepository.findTop50ByAccountIdOrderByCreatedAtDesc(account.getId());

        List<Map<String, Object>> items = recent.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", t.getType());
            m.put("amount", t.getAmount());
            m.put("balance_after", t.getBalanceAfter());
            m.put("description", t.getDescription());
            m.put("created_at", t.getCreatedAt().toString());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("balance", account.getCreditBalance());
        body.put("recent", items);
        return body;
    }

    @GetMapping("/api/v1/credits/estimate")
    public Map<String, Object> estimate(@RequestParam String kind,
                                          @RequestParam(required = false) List<String> format_codes,
                                          @RequestParam(defaultValue = "initial") String mode) {
        Account account = accountRepository.findById(CurrentUser.id()).orElseThrow(ApiException::notFound);
        int cost = costEstimateService.estimate(kind, format_codes, mode);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("estimated_cost", cost);
        body.put("balance", account.getCreditBalance());
        body.put("sufficient", account.getCreditBalance() >= cost);
        return body;
    }
}
