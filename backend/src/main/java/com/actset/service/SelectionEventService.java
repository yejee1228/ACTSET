package com.actset.service;

import com.actset.domain.Project;
import com.actset.domain.SelectionEvent;
import com.actset.repository.SelectionEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * SelectionEvent 적재(1-13) — 핵심 데이터 자산이므로 미루지 않는다(CLAUDE.md 규칙 6).
 * docs/11: "로그 적재 실패가 사용자 동작을 막아서는 안 된다" — 저장 실패를 호출자에게
 * 전파하지 않고 별도 로그로만 남긴다(완전한 비동기 재시도 큐는 MVP 범위 밖).
 */
@Service
public class SelectionEventService {

    private static final Logger log = LoggerFactory.getLogger(SelectionEventService.class);

    private final SelectionEventRepository repository;

    public SelectionEventService(SelectionEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Project project, UUID ownerId, String screen, String action,
                        String formatCode, JsonNode shownCandidates, UUID selectedCandidateId) {
        try {
            SelectionEvent event = new SelectionEvent();
            event.setProjectId(project.getId());
            event.setOwnerId(ownerId);
            event.setScreen(screen);
            event.setAction(action);
            event.setFormatCode(formatCode);
            event.setGenre(project.getGenre());
            event.setShownCandidates(shownCandidates);
            event.setSelectedCandidateId(selectedCandidateId);
            repository.save(event);
        } catch (Exception e) {
            log.error("SelectionEvent 적재 실패 — project={}, screen={}, action={}", project.getId(), screen, action, e);
        }
    }
}
