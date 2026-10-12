package com.actset.worker;

import com.actset.repository.PerformanceSeedRepository;
import com.actset.service.PerformanceSeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Gallery KOPIS 시드 자동 갱신 — worker 프로필에서 매일 한 번 돈다(기본 04:00 KST). 서버가 처음 뜰 때 시드가 비어 있으면
 * (목업 공연만 있어도) 바로 한 번 받는다(빈 DB로 배포해도 Gallery가 다음 날 새벽까지 비어 있지 않게). 실제 키가 없으면(목업) 돌지 않는다.
 */
@Component
@Profile("worker")
public class KopisSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(KopisSyncScheduler.class);

    private final PerformanceSeedService seeds;
    private final PerformanceSeedRepository repository;
    private final int maxItems;

    public KopisSyncScheduler(PerformanceSeedService seeds, PerformanceSeedRepository repository,
                              @Value("${actset.kopis.sync-max:500}") int maxItems) {
        this.seeds = seeds;
        this.repository = repository;
        this.maxItems = maxItems;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncIfEmpty() {
        // 실제 공연이 하나도 없을 때만(목업 공연만 있는 경우 포함)
        if (!seeds.live() || repository.countBySource("kopis")
                > repository.countByExternalIdStartingWith(PerformanceSeedService.MOCK_PREFIX)) return;
        Thread t = new Thread(this::run, "kopis-initial-sync");
        t.setDaemon(true);
        t.start();
    }

    @Scheduled(cron = "${actset.kopis.sync-cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void daily() {
        if (seeds.live()) run();
    }

    private void run() {
        try {
            seeds.sync(maxItems);
        } catch (Exception e) {
            log.warn("KOPIS 동기화 실패: {}", e.toString());
        }
    }
}
