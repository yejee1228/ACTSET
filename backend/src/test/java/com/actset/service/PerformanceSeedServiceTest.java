package com.actset.service;

import com.actset.domain.PerformanceSeed;
import com.actset.external.kopis.KopisAdapter;
import com.actset.external.kopis.KopisBlockedException;
import com.actset.external.kopis.KopisDetail;
import com.actset.external.kopis.KopisPerformance;
import com.actset.repository.PerformanceSeedRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** KOPIS 동기화 — 차단되면 바로 멈추고, 받은 목록 항목은 남긴다. 실제 KOPIS는 부르지 않는다(P-5). */
class PerformanceSeedServiceTest {

    static KopisPerformance row(String id) {
        return new KopisPerformance(id, "공연 " + id, "연극", LocalDate.now(), LocalDate.now().plusDays(3),
                "극장", null, "서울특별시", "공연중", false);
    }

    static KopisDetail detail(String id) {
        return new KopisDetail(id, null, null, null, null, null, null, null, null, null, null, false, List.of(), List.of());
    }

    @Test
    void stops_whole_sync_when_kopis_blocks_but_keeps_listed_rows() throws Exception {
        KopisAdapter adapter = mock(KopisAdapter.class);
        when(adapter.live()).thenReturn(true);
        when(adapter.fetchPage(any(), any(), eq(1), anyInt())).thenReturn(List.of(row("A"), row("B"), row("C")));
        when(adapter.fetchDetail("A")).thenReturn(Optional.of(detail("A")));
        when(adapter.fetchDetail("B")).thenThrow(new KopisBlockedException(null));
        PerformanceSeedRepository repo = mock(PerformanceSeedRepository.class);
        when(repo.findBySourceAndExternalId(any(), any())).thenReturn(Optional.empty());

        PerformanceSeedService service = new PerformanceSeedService(adapter, repo, new ObjectMapper(), mock(PosterThumbnailer.class));
        service.detailPauseMs = 0;
        PerformanceSeedService.SyncResult r = service.sync(500);

        assertThat(r.blocked()).isTrue();
        assertThat(r.saved()).isEqualTo(1);
        assertThat(r.detailsFetched()).isEqualTo(1);
        verify(adapter, never()).fetchDetail("C"); // 차단 뒤에는 더 부르지 않는다
        verify(adapter, never()).fetchPage(any(), any(), eq(2), anyInt());
        verify(repo, times(2)).save(any(PerformanceSeed.class)); // A(상세 포함) + B(목록 항목만)
    }

    @Test
    void detail_failure_other_than_block_keeps_row_without_detail() throws Exception {
        KopisAdapter adapter = mock(KopisAdapter.class);
        when(adapter.live()).thenReturn(true);
        when(adapter.fetchPage(any(), any(), eq(1), anyInt())).thenReturn(List.of(row("A")));
        when(adapter.fetchDetail("A")).thenThrow(new IllegalStateException("KOPIS 응답 500"));
        PerformanceSeedRepository repo = mock(PerformanceSeedRepository.class);
        when(repo.findBySourceAndExternalId(any(), any())).thenReturn(Optional.empty());

        PerformanceSeedService service = new PerformanceSeedService(adapter, repo, new ObjectMapper(), mock(PosterThumbnailer.class));
        service.detailPauseMs = 0;
        PerformanceSeedService.SyncResult r = service.sync(500);

        assertThat(r.blocked()).isFalse();
        assertThat(r.saved()).isEqualTo(1);
        assertThat(r.detailsFetched()).isZero();
    }
}
