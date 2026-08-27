package com.actset.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/** 컨트롤러에서 반복되는 "현재 로그인 계정 id" 취득을 감싼다. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null
                ? null
                : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AccountPrincipal ap) {
            return ap.getAccountId();
        }
        throw new IllegalStateException("인증되지 않은 컨텍스트에서 CurrentUser.id()를 호출했습니다.");
    }

    public static AccountPrincipal principal() {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return (AccountPrincipal) principal;
    }
}
