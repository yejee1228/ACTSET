package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.domain.CreditTransaction;
import com.actset.repository.AccountRepository;
import com.actset.repository.CreditTransactionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 1-24(실패 시 크레딧 환불) 관련 CreditService 동작 검증. 동시 요청 경쟁 상태는
 * 실제 DB의 CHECK(credit_balance >= 0) + 트랜잭션 격리에 의존하므로 P-3에서
 * 통합 테스트로 별도 확인한다 — 여기서는 서비스 로직 자체의 정확성만 본다.
 */
class CreditServiceTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final CreditTransactionRepository creditTransactionRepository = mock(CreditTransactionRepository.class);
    private final CreditService service = new CreditService(accountRepository, creditTransactionRepository);

    private Account account(UUID id, int balance) {
        Account a = new Account();
        a.setId(id);
        a.setCreditBalance(balance);
        return a;
    }

    @Test
    void consumeInsufficientBalanceThrows402WithoutMutating() {
        UUID accountId = UUID.randomUUID();
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, 5)));

        assertThatThrownBy(() -> service.consume(accountId, 10, UUID.randomUUID(), "시안 생성"))
                .isInstanceOf(ApiException.class);

        verify(accountRepository, never()).save(any());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    void refundByJobLooksUpOriginalConsumeAndCreditsBack() {
        UUID accountId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        CreditTransaction consumeTx = new CreditTransaction();
        consumeTx.setAccountId(accountId);
        consumeTx.setAmount(-30);
        consumeTx.setJobId(jobId);
        consumeTx.setType("consume");

        when(creditTransactionRepository.findByJobIdAndType(jobId, "consume")).thenReturn(Optional.of(consumeTx));
        when(creditTransactionRepository.existsByJobIdAndType(jobId, "consume")).thenReturn(true);
        when(creditTransactionRepository.existsByJobIdAndType(jobId, "refund")).thenReturn(false);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account(accountId, 470)));

        service.refundByJob(jobId, "draft_generate 실패 환불");

        ArgumentCaptor<Account> savedAccount = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(savedAccount.capture());
        assertThat(savedAccount.getValue().getCreditBalance()).isEqualTo(500); // 470 + 30

        ArgumentCaptor<CreditTransaction> savedTx = ArgumentCaptor.forClass(CreditTransaction.class);
        verify(creditTransactionRepository).save(savedTx.capture());
        assertThat(savedTx.getValue().getType()).isEqualTo("refund");
        assertThat(savedTx.getValue().getAmount()).isEqualTo(30);
    }

    @Test
    void refundByJobIsNoOpWhenAlreadyRefunded() {
        UUID accountId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();

        CreditTransaction consumeTx = new CreditTransaction();
        consumeTx.setAccountId(accountId);
        consumeTx.setAmount(-30);
        consumeTx.setJobId(jobId);

        when(creditTransactionRepository.findByJobIdAndType(jobId, "consume")).thenReturn(Optional.of(consumeTx));
        when(creditTransactionRepository.existsByJobIdAndType(jobId, "consume")).thenReturn(true);
        when(creditTransactionRepository.existsByJobIdAndType(jobId, "refund")).thenReturn(true); // 이미 환불됨

        service.refundByJob(jobId, "재시도 후 재실패 환불");

        verify(accountRepository, never()).save(any());
        verify(creditTransactionRepository, never()).save(any());
    }

    @Test
    void refundByJobIsNoOpWhenNeverConsumed() {
        UUID jobId = UUID.randomUUID();
        when(creditTransactionRepository.findByJobIdAndType(jobId, "consume")).thenReturn(Optional.empty());

        service.refundByJob(jobId, "무과금 작업");

        verifyNoInteractions(accountRepository);
    }
}
