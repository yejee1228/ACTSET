package com.actset;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 단일 jar를 web·worker 프로필로 나눠 실행한다(docs/09).
 * web  : application-web.yml — HTTP 요청 처리
 * worker: application-worker.yml — jobs 테이블 폴링, 서버 비활성화
 */
@SpringBootApplication
@EnableScheduling
public class ActsetApplication {
    public static void main(String[] args) {
        SpringApplication.run(ActsetApplication.class, args);
    }
}
