package com.actset.web;

import com.actset.domain.Project;
import com.actset.security.CurrentUser;
import com.actset.service.ProjectService;
import com.actset.service.SelectionEventService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** docs/11 "5. 선택 로그" — ③·⑥ 화면의 4개 액션을 모두 기록한다(1-13). */
@RestController
@RequestMapping("/api/v1/projects/{id}/selection-events")
public class SelectionEventController {

    private final ProjectService projectService;
    private final SelectionEventService selectionEventService;

    public SelectionEventController(ProjectService projectService, SelectionEventService selectionEventService) {
        this.projectService = projectService;
        this.selectionEventService = selectionEventService;
    }

    public record SelectionEventRequest(String screen, String action, JsonNode shown_candidates,
                                          UUID selected_candidate_id, String format_code) {
    }

    @PostMapping
    public ResponseEntity<Void> record(@PathVariable UUID id, @RequestBody SelectionEventRequest req) {
        Project project = projectService.getOwned(id, CurrentUser.id());
        selectionEventService.record(project, CurrentUser.id(), req.screen(), req.action(),
                req.format_code(), req.shown_candidates(), req.selected_candidate_id());
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
