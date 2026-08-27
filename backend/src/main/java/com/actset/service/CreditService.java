package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.domain.CreditTransaction;
import com.actset.repository.AccountRepository;
import com.actset.repository.CreditTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 크레딧 잔액 갱신과 이력 기록을 한 트랜잭션으로 묶는다(docs/06·10 — CLAUDE.md 규칙 4).
 * DB의 CHECK(credit_balance >= 0)이 최종 방어선이고, 여기서는 그 전에 잔액을 확인해
 * 사용자에게 402를 명확히 돌려준다.
 */
@Service
public class CreditService {

    private final AccountRepository accountRepository;
    private final CreditTransactionRepository creditTransactionRepository;

    public CreditService(AccountRepository accountRepository, CreditTransactionRepository creditTransactionRepository) {
        this.accountRepository = accountRepository;
        this.creditTransactionRepository = creditTransactionRepository;
    }

    @Transactional
    public void grant(UUID accountId, int amount, String type, String description, UUID actorId) {
        Account account = accountRepository.findById(accountId).orElseThrow(ApiException::notFound);
        account.setCreditBalance(account.getCreditBalance() + amount);
        accountRepository.save(account);

        CreditTransaction tx = new CreditTransaction();
        tx.setAccountId(accountId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setBalanceAfter(account.getCreditBalance());
        tx.setActorId(actorId);
        tx.setDescription(description);
        creditTransactionRepository.save(tx);
    }

    /** 작업 등록 시점 차감. 잔액 부족이면 402로 실패시켜 작업을 만들지 않는다(docs/06). */
    @Transactional
    public void consume(UUID accountId, int amount, UUID jobId, String description) {
        if (amount <= 0) {
            throw new IllegalArgumentException("소비 크레딧은 양수여야 합니다.");
        }
        Account account = accountRepository.findById(accountId).orElseThrow(ApiException::notFound);
        if (account.getCreditBalance() < amount) {
            throw ApiException.insufficientCredits(amount, account.getCreditBalance());
        }
        account.setCreditBalance(account.getCreditBalance() - amount);
        accountRepository.save(account);

        CreditTransaction tx = new CreditTransaction();
        tx.setAccountId(accountId);
        tx.setType("consume");
        tx.setAmount(-amount);
        tx.setBalanceAfter(account.getCreditBalance());
        tx.setJobId(jobId);
        tx.setDescription(description);
        creditTransactionRepository.save(tx);
    }

    /** 작업 실패 시 환불. job_id당 consume 1건 제약과 짝을 맞춰 중복 환불을 피한다. */
    @Transactional
    public void refund(UUID accountId, int amount, UUID jobId, String description) {
        if (!creditTransactionRepository.existsByJobIdAndType(jobId, "consume")) {
            return; // 애초에 차감된 적 없는 작업은 환불하지 않는다(중복 방지)
        }
        Account account = accountRepository.findById(accountId).orElseThrow(ApiException::notFound);
        account.setCreditBalance(account.getCreditBalance() + amount);
        accountRepository.save(account);

        CreditTransaction tx = new CreditTransaction();
        tx.setAccountId(accountId);
        tx.setType("refund");
        tx.setAmount(amount);
        tx.setBalanceAfter(account.getCreditBalance());
        tx.setJobId(jobId);
        tx.setDescription(description);
        creditTransactionRepository.save(tx);
    }
}
