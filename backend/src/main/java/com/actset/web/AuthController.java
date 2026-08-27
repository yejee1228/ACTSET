package com.actset.web;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.repository.AccountRepository;
import com.actset.security.AccountPrincipal;
import com.actset.security.CurrentUser;
import com.actset.service.CreditService;
import com.actset.web.dto.AuthDtos.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/** docs/11 Stage 0 인증·계정. */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CreditService creditService;

    @Value("${actset.credit.signup-grant:500}")
    private int signupGrant;

    public AuthController(AccountRepository accountRepository, PasswordEncoder passwordEncoder,
                           AuthenticationManager authenticationManager,
                           SecurityContextRepository securityContextRepository,
                           CreditService creditService) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.creditService = creditService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AccountResponse> signup(@Valid @RequestBody SignupRequest req,
                                                    HttpServletRequest request, HttpServletResponse response) {
        if (req.agreements() == null || !req.agreements().terms() || !req.agreements().privacy()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AGREEMENT_REQUIRED", "필수 약관에 동의해야 합니다.");
        }
        if (accountRepository.existsByEmail(req.email())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN", "이미 가입된 이메일입니다.");
        }

        Account account = new Account();
        account.setEmail(req.email());
        account.setPasswordHash(passwordEncoder.encode(req.password()));
        account.setTermsAgreedAt(Instant.now());
        account.setPrivacyAgreedAt(Instant.now());
        account.setTermsVersion(req.terms_version());
        account.setPrivacyVersion(req.terms_version());
        if (req.agreements().marketing()) {
            account.setMarketingAgreedAt(Instant.now());
        }
        account = accountRepository.save(account);
        creditService.grant(account.getId(), signupGrant, "signup_grant", "가입 초기 지급", null);
        account = accountRepository.findById(account.getId()).orElseThrow();

        authenticateAndBindSession(req.email(), req.password(), request, response);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AccountResponse(account.getId().toString(), account.getEmail(), account.getRole(), account.getCreditBalance()));
    }

    @PostMapping("/login")
    public ResponseEntity<AccountResponse> login(@Valid @RequestBody LoginRequest req,
                                                   HttpServletRequest request, HttpServletResponse response) {
        Authentication auth;
        try {
            auth = authenticateAndBindSession(req.email(), req.password(), request, response);
        } catch (org.springframework.security.core.AuthenticationException e) {
            // 계정 존재 여부를 노출하지 않도록 동일한 401로 응답한다(docs/11)
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        AccountPrincipal principal = (AccountPrincipal) auth.getPrincipal();
        Account account = principal.getAccount();
        account.setLastLoginAt(Instant.now());
        accountRepository.save(account);
        return ResponseEntity.ok(new AccountResponse(account.getId().toString(), account.getEmail(), account.getRole(), account.getCreditBalance()));
    }

    @GetMapping("/me")
    public AccountResponse me() {
        Account account = accountRepository.findById(CurrentUser.id()).orElseThrow(ApiException::notFound);
        return new AccountResponse(account.getId().toString(), account.getEmail(), account.getRole(), account.getCreditBalance());
    }

    private Authentication authenticateAndBindSession(String email, String password,
                                                        HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return auth;
    }
}
