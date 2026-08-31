package com.actset.service;

import com.actset.domain.Account;
import com.actset.repository.AccountRepository;
import com.actset.repository.CreditTransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-3 크레딧 트랜잭션 테스트 — 실제 로컬 PostgreSQL에 붙어 동시성을 검증한다
 * (docs/13 "동시 요청 잔액 음수 불가, 부분 실패 환불, 중복 차감 방지").
 * Mockito로는 DB의 CHECK 제약·유니크 인덱스가 실제로 막아주는지 확인할 수 없어
 * @SpringBootTest로 진짜 트랜잭션 경합을 재현한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CreditConcurrencyIT {

    @Autowired
    private CreditService creditService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    private Account createTestAccount(int initialBalance) {
        Account account = new Account();
        account.setEmail("credit-it-" + UUID.randomUUID() + "@actset.dev");
        account.setCreditBalance(initialBalance);
        return accountRepository.save(account);
    }

    @Test
    void concurrentConsumeNeverDrivesBalanceNegative() throws InterruptedException {
        Account account = createTestAccount(100);
        int threads = 20;
        int amountEach = 10; // 20 * 10 = 200 > 100 잔액 — 최대 10건만 성공해야 한다

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    creditService.consume(account.getId(), amountEach, UUID.randomUUID(), "동시성 테스트");
                    succeeded.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    // no-op
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        Account after = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getCreditBalance()).isGreaterThanOrEqualTo(0);
        assertThat(succeeded.get()).isEqualTo(10); // 정확히 잔액만큼만 성공해야 한다
        assertThat(rejected.get()).isEqualTo(10);
        assertThat(after.getCreditBalance()).isEqualTo(0);
    }

    @Test
    void duplicateConsumeForSameJobIsRejectedByUniqueConstraint() {
        Account account = createTestAccount(100);
        UUID jobId = UUID.randomUUID();

        creditService.consume(account.getId(), 10, jobId, "최초 차감");

        // 같은 job_id로 두 번째 차감 시도 — uq_credit_consume_job이 막아야 한다(중복 차감 방지).
        assertThatThrownBy(() -> creditService.consume(account.getId(), 10, jobId, "중복 차감 시도"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Account after = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getCreditBalance()).isEqualTo(90); // 한 번만 차감됨
    }

    @Test
    void refundRestoresExactlyConsumedAmountAndIsIdempotent() {
        Account account = createTestAccount(100);
        UUID jobId = UUID.randomUUID();

        creditService.consume(account.getId(), 30, jobId, "부분 실패 대상 작업");
        creditService.refund(account.getId(), 30, jobId, "작업 실패 환불");
        creditService.refund(account.getId(), 30, jobId, "중복 환불 시도"); // 이미 환불됨 — 무시되어야 한다

        Account after = accountRepository.findById(account.getId()).orElseThrow();
        assertThat(after.getCreditBalance()).isEqualTo(100); // 정확히 원복, 두 번 복구되지 않음
    }
}
