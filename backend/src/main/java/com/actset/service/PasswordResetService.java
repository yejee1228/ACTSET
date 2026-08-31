package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.domain.PasswordResetToken;
import com.actset.external.mail.MailSender;
import com.actset.repository.AccountRepository;
import com.actset.repository.PasswordResetTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 비밀번호 재설정(1-17). 토큰은 1회용·만료형이며, 요청 엔드포인트는 계정 존재 여부와
 * 무관하게 항상 성공으로 응답한다(docs/11 — 계정 존재 확인 수단이 되지 않도록).
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);

    private final AccountRepository accountRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final MailSender mailSender;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(AccountRepository accountRepository, PasswordResetTokenRepository tokenRepository,
                                 PasswordEncoder passwordEncoder, MailSender mailSender) {
        this.accountRepository = accountRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
    }

    @Transactional
    public void request(String email) {
        accountRepository.findByEmail(email).ifPresent(account -> {
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

            PasswordResetToken token = new PasswordResetToken();
            token.setAccountId(account.getId());
            token.setTokenHash(hash(rawToken));
            token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
            tokenRepository.save(token);

            String link = "http://localhost:5173/reset-password?token=" + rawToken;
            try {
                mailSender.send(account.getEmail(), "[ACTSET] 비밀번호 재설정",
                        "아래 링크에서 비밀번호를 재설정하세요(30분 이내 1회용):\n" + link);
            } catch (Exception e) {
                log.error("비밀번호 재설정 메일 발송 실패 — account={}", account.getId(), e);
            }
        });
        // 계정이 없어도 여기서 조용히 끝난다 — 호출자에게는 항상 202로 응답(컨트롤러 책임).
    }

    @Transactional
    public void confirm(String rawToken, String newPassword) {
        PasswordResetToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .filter(PasswordResetToken::isUsable)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "INVALID_TOKEN", "재설정 링크가 유효하지 않거나 만료되었습니다."));

        Account account = accountRepository.findById(token.getAccountId()).orElseThrow(ApiException::notFound);
        account.setPasswordHash(passwordEncoder.encode(newPassword));
        accountRepository.save(account);

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
