package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.repository.AccountRepository;
import com.actset.security.CurrentUser;
import com.actset.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

/** docs/11 계정 설정(1-19) · 회원 탈퇴(1-4b). */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;

    public AccountController(AccountRepository accountRepository, AccountService accountService,
                              PasswordEncoder passwordEncoder) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.passwordEncoder = passwordEncoder;
    }

    public record ProfileUpdateRequest(String display_name) {
    }

    public record PasswordChangeRequest(@NotBlank String current_password, @NotBlank String new_password) {
    }

    @PatchMapping
    public ResponseEntity<Void> updateProfile(@RequestBody ProfileUpdateRequest req) {
        Account account = accountRepository.findById(CurrentUser.id()).orElseThrow(ApiException::notFound);
        if (req.display_name() != null) {
            account.setDisplayName(req.display_name());
        }
        accountRepository.save(account);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody PasswordChangeRequest req) {
        Account account = accountRepository.findById(CurrentUser.id()).orElseThrow(ApiException::notFound);
        if (account.getPasswordHash() == null
                || !passwordEncoder.matches(req.current_password(), account.getPasswordHash())) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
        }
        account.setPasswordHash(passwordEncoder.encode(req.new_password()));
        accountRepository.save(account);
        return ResponseEntity.noContent().build();
    }

    /** 탈퇴. 삭제 범위는 화면(1-19)에서 사전 고지하고, 여기서는 즉시 처리한다(OVERNIGHT-LOG 참고). */
    @DeleteMapping
    public ResponseEntity<Void> withdraw(HttpServletRequest request) {
        accountService.withdraw(CurrentUser.id());
        request.getSession().invalidate();
        return ResponseEntity.noContent().build();
    }
}
