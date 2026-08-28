package com.actset.external.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 0-9(메일 발송 서비스 선정·발신 도메인 인증)가 아직 없어 실제 발송 대신
 * 로그로만 남기는 껍데기(OVERNIGHT-LOG 참고). 실제 서비스가 정해지면
 * SesMailSender로 교체 — 호출부(AuthController·PasswordResetService)는 그대로 재사용.
 */
@Service
public class MockMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(MockMailSender.class);

    @Override
    public void send(String to, String subject, String body) {
        log.info("[MOCK MAIL] to={} subject={}\n{}", to, subject, body);
    }
}
