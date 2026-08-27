package com.actset.common;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** docs/11 공통 오류 포맷 { "error": { "code", "message", "details" } }을 나르는 예외. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "요청한 리소스를 찾을 수 없습니다.");
    }

    public static ApiException insufficientCredits(int required, int balance) {
        return new ApiException(HttpStatus.PAYMENT_REQUIRED, "INSUFFICIENT_CREDITS", "크레딧이 부족합니다.",
                Map.of("required", required, "balance", balance));
    }

    public static ApiException infoIncomplete(java.util.List<String> missing) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INFO_INCOMPLETE", "필수 4항목이 채워지지 않았습니다.",
                Map.of("missing", missing));
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
