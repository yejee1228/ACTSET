package com.actset.config;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 단일 인스턴스 메모리 슬라이딩 윈도우 레이트리밋(1-22 · 1-25가 공유하는 유틸리티).
 * 여러 워커/웹 인스턴스로 수평 확장하면 Redis 등 공유 저장소로 옮겨야 한다 — MVP는
 * 단일 인스턴스 배포를 전제한다(docs/09).
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    /** key(계정id·IP 등)가 windowSeconds 안에 limit회를 초과했으면 false. */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        Instant now = Instant.now();
        Instant windowStart = now.minus(Duration.ofSeconds(windowSeconds));
        Deque<Instant> deque = hits.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());

        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst().isBefore(windowStart)) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /** 현재 윈도우 안에서 이미 소비한 횟수 — 1-25의 "상한 초과 알림" 등에 재사용. */
    public int currentCount(String key, int windowSeconds) {
        Deque<Instant> deque = hits.get(key);
        if (deque == null) return 0;
        Instant windowStart = Instant.now().minus(Duration.ofSeconds(windowSeconds));
        synchronized (deque) {
            return (int) deque.stream().filter(t -> !t.isBefore(windowStart)).count();
        }
    }
}
