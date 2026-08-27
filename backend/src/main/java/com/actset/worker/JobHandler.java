package com.actset.worker;

import com.actset.domain.Job;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * jobs.kind 하나를 처리하는 핸들러(docs/10 kind CHECK 값과 1:1 대응).
 * 실패 시 예외를 던지면 JobWorker가 status=failed·error 기록·크레딧 환불을 처리한다.
 */
public interface JobHandler {

    String kind();

    /** 처리 결과를 jobs.result에 담을 JSON으로 반환한다. */
    ObjectNode handle(Job job) throws Exception;
}
