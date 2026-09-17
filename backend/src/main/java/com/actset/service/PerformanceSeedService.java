package com.actset.service;

import com.actset.domain.PerformanceSeed;
import com.actset.external.kopis.KopisAdapter;
import com.actset.external.kopis.KopisPerformance;
import com.actset.repository.PerformanceSeedRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * KOPIS 시드 데이터를 Gallery 부트스트래핑용으로 적재한다(7-7, Stage 17). 같은
 * external_id가 이미 있으면 갱신만 한다(source+external_id UNIQUE — V7 마이그레이션).
 */
@Service
public class PerformanceSeedService {

    private final KopisAdapter kopisAdapter;
    private final PerformanceSeedRepository repository;

    public PerformanceSeedService(KopisAdapter kopisAdapter, PerformanceSeedRepository repository) {
        this.kopisAdapter = kopisAdapter;
        this.repository = repository;
    }

    @Transactional
    public int syncLatest(int count) throws Exception {
        List<KopisPerformance> fetched = kopisAdapter.fetchLatest(count);
        for (KopisPerformance p : fetched) {
            PerformanceSeed seed = repository.findBySourceAndExternalId("kopis", p.externalId())
                    .orElseGet(PerformanceSeed::new);
            seed.setSource("kopis");
            seed.setExternalId(p.externalId());
            seed.setMainTitle(p.title());
            seed.setGenre(p.genre());
            seed.setPrimaryDate(p.startDate());
            seed.setVenueName(p.venueName());
            seed.setPosterUrl(p.posterUrl());
            seed.setFetchedAt(Instant.now());
            repository.save(seed);
        }
        return fetched.size();
    }
}
