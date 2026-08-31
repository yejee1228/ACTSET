package com.actset.config;

import com.actset.security.AccountPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 생성 엔드포인트 중심 레이트리밋(1-22). 계정당 분당 요청 수를 제한해 남용을 막는다.
 * 비용 상한(시간당 생성 횟수·일일 예산)은 1-25가 별도 정책으로 더 좁게 건다 — 이 필터는
 * 그 앞단의 일반적인 어뷰징 방지선이다.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int LIMIT = 20;
    private static final int WINDOW_SECONDS = 60;

    private static final PathPatternParser PARSER = new PathPatternParser();
    private static final List<PathPattern> GENERATION_PATTERNS = List.of(
            PARSER.parse("/api/v1/projects/{id}/drafts"),
            PARSER.parse("/api/v1/projects/{id}/confirm"),
            PARSER.parse("/api/v1/projects/{id}/recompose"),
            PARSER.parse("/api/v1/projects/{id}/resync"),
            PARSER.parse("/api/v1/projects/{id}/print-renders")
    );

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !matchesGenerationEndpoint(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = resolveKey(request);
        if (!rateLimiter.tryAcquire(key, LIMIT, WINDOW_SECONDS)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), Map.of("error", Map.of(
                    "code", "RATE_LIMITED", "message", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.")));
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean matchesGenerationEndpoint(HttpServletRequest request) {
        org.springframework.http.server.PathContainer path = org.springframework.http.server.PathContainer.parsePath(request.getRequestURI());
        return GENERATION_PATTERNS.stream().anyMatch(p -> p.matches(path));
    }

    private String resolveKey(HttpServletRequest request) {
        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal() : null;
        if (principal instanceof AccountPrincipal ap) {
            return "account:" + ap.getAccountId();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
