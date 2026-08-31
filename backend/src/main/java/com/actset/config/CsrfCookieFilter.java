package com.actset.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * CsrfToken은 기본적으로 지연 생성된다 — 누군가 .getToken()을 호출해야 실제로 쿠키에
 * 저장된다. SPA는 첫 GET 응답에서부터 XSRF-TOKEN 쿠키를 받아야 하므로 매 요청마다
 * 강제로 접근해 저장을 트리거한다(Spring Security 공식 SPA 가이드 패턴).
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        filterChain.doFilter(request, response);
    }
}
