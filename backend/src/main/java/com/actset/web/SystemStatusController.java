package com.actset.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 점검 배너(1-24). 운영 중 actset.maintenance.enabled=true로 켜면 프런트가 배너를 띄운다. */
@RestController
public class SystemStatusController {

    @Value("${actset.maintenance.enabled:false}")
    private boolean maintenanceEnabled;

    @Value("${actset.maintenance.message:서비스 점검 중입니다. 잠시 후 다시 시도해주세요.}")
    private String maintenanceMessage;

    @GetMapping("/api/v1/system/status")
    public Map<String, Object> status() {
        return Map.of("maintenance", maintenanceEnabled, "message", maintenanceMessage);
    }
}
