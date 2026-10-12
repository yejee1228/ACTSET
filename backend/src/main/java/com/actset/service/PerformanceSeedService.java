package com.actset.service;

import com.actset.domain.PerformanceSeed;
import com.actset.external.kopis.KopisAdapter;
import com.actset.external.kopis.KopisBlockedException;
import com.actset.external.kopis.KopisDetail;
import com.actset.external.kopis.KopisGenres;
import com.actset.external.kopis.KopisPerformance;
import com.actset.repository.PerformanceSeedRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * KOPIS 시드 데이터를 Gallery 부트스트래핑용으로 적재한다(7-7, Stage 17). 같은 external_id가 있으면 갱신만 한다
 * (source+external_id UNIQUE — V7). 오늘부터 WINDOW_DAYS 안에 공연 기간이 걸친 공연(공연중·공연예정)을 받는다.
 *
 * <p>외부 호출이 길어서 전체를 한 트랜잭션으로 묶지 않는다 — 건별 저장(repository.save)이다. 한 건 실패는 건너뛴다.
 */
@Service
public class PerformanceSeedService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceSeedService.class);
    /** 오늘부터 며칠 뒤까지 시작하는 공연을 받을지. */
    static final int WINDOW_DAYS = 90;
    static final int PAGE_ROWS = 100;
    /** 상세는 이 기간이 지나면 다시 받는다(출연진·가격 변경 반영). */
    static final Duration DETAIL_REFRESH = Duration.ofDays(7);
    /** 상세 호출 사이에 쉰다. KOPIS는 호출 한도를 공개하지 않았고 0.1초 간격 약 50건 뒤 차단됐다(2026-10-11) — 임시값. */
    static final long DETAIL_PAUSE_MS = 1000;
    /** MockKopisAdapter의 external_id 접두어. */
    public static final String MOCK_PREFIX = "mock-";

    public record SyncResult(int listed, int saved, int detailsFetched, int thumbnails, int failures, boolean blocked, long ms) {
    }

    private final KopisAdapter kopisAdapter;
    private final PerformanceSeedRepository repository;
    private final ObjectMapper objectMapper;
    private final PosterThumbnailer thumbnailer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    /** 테스트에서 줄인다. */
    long detailPauseMs = DETAIL_PAUSE_MS;

    public PerformanceSeedService(KopisAdapter kopisAdapter, PerformanceSeedRepository repository, ObjectMapper objectMapper,
                                  PosterThumbnailer thumbnailer) {
        this.kopisAdapter = kopisAdapter;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.thumbnailer = thumbnailer;
    }

    public boolean live() {
        return kopisAdapter.live();
    }

    /** @param maxItems 이번에 받을 최대 공연 수 */
    public SyncResult sync(int maxItems) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("KOPIS 동기화가 이미 진행 중입니다");
        }
        long t0 = System.currentTimeMillis();
        int listed = 0, saved = 0, details = 0, thumbs = 0, failures = 0;
        boolean blocked = false;
        try {
            if (kopisAdapter.live()) {
                long mocks = repository.deleteByExternalIdStartingWith(MOCK_PREFIX);
                if (mocks > 0) log.info("KOPIS 목업 공연 {}건 삭제(실제 키로 동기화)", mocks);
            }
            LocalDate from = LocalDate.now(), to = from.plusDays(WINDOW_DAYS);
            pages:
            for (int page = 1; listed < maxItems; page++) {
                List<KopisPerformance> rows;
                try {
                    rows = kopisAdapter.fetchPage(from, to, page, Math.min(PAGE_ROWS, maxItems - listed));
                } catch (KopisBlockedException e) {
                    blocked = true;
                    break;
                }
                if (rows.isEmpty()) break;
                listed += rows.size();
                for (KopisPerformance p : rows) {
                    try {
                        Upserted u = upsert(p);
                        if (u.detail()) details++;
                        if (u.thumbnail()) thumbs++;
                        saved++;
                    } catch (KopisBlockedException e) {
                        blocked = true;
                        break pages;
                    } catch (Exception e) {
                        failures++;
                        log.warn("KOPIS 공연 {} 저장 실패: {}", p.externalId(), e.toString());
                    }
                }
                if (rows.size() < PAGE_ROWS) break;
            }
            SyncResult r = new SyncResult(listed, saved, details, thumbs, failures, blocked, System.currentTimeMillis() - t0);
            if (blocked) log.warn("KOPIS가 요청을 차단해 동기화를 멈춤(다음 예약 때 이어서): {}", r);
            else log.info("KOPIS 동기화 완료: {}", r);
            return r;
        } finally {
            running.set(false);
        }
    }

    private record Upserted(boolean detail, boolean thumbnail) {
    }

    private Upserted upsert(KopisPerformance p) throws Exception {
        PerformanceSeed seed = repository.findBySourceAndExternalId("kopis", p.externalId()).orElseGet(PerformanceSeed::new);
        seed.setSource("kopis");
        seed.setExternalId(p.externalId());
        seed.setMainTitle(p.title());
        seed.setKopisGenre(p.genre());
        seed.setGenre(KopisGenres.toApp(p.genre()));
        seed.setPrimaryDate(p.startDate());
        seed.setEndDate(p.endDate());
        seed.setVenueName(p.venueName());
        seed.setPosterUrl(p.posterUrl());
        seed.setArea(p.area());
        seed.setState(p.state());
        seed.setFetchedAt(Instant.now());

        boolean fetched = false;
        if (seed.getDetailFetchedAt() == null || seed.getDetailFetchedAt().isBefore(Instant.now().minus(DETAIL_REFRESH))) {
            Thread.sleep(detailPauseMs);
            Optional<KopisDetail> detail;
            try {
                detail = kopisAdapter.fetchDetail(p.externalId());
            } catch (KopisBlockedException e) {
                repository.save(seed); // 목록 항목은 받아 둔다 — 상세는 다음 동기화 때
                throw e;
            } catch (Exception e) {
                log.warn("KOPIS 상세 {} 실패(목록 항목만 저장): {}", p.externalId(), e.toString());
                detail = Optional.empty();
            }
            if (detail.isPresent()) {
                seed.setDetail(objectMapper.valueToTree(detail.get()));
                seed.setKidFriendly(detail.get().childFriendly());
                seed.setDetailFetchedAt(Instant.now());
                fetched = true;
            }
        }
        boolean thumb = false;
        if (p.posterUrl() != null && (seed.getThumbnailPath() == null || !p.posterUrl().equals(seed.getThumbnailSource()))) {
            try {
                Optional<String> path = thumbnailer.create(p.posterUrl(), p.externalId());
                if (path.isPresent()) {
                    seed.setThumbnailPath(path.get());
                    seed.setThumbnailSource(p.posterUrl());
                    thumb = true;
                }
            } catch (Exception e) {
                // 축소본이 없어도 목록은 원본 URL로 보인다 — 다음 동기화 때 다시 시도
                log.warn("KOPIS 포스터 축소본 실패 {}: {}", p.externalId(), e.toString());
            }
        }
        repository.save(seed);
        return new Upserted(fetched, thumb);
    }
}
