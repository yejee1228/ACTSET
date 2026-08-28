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
        // P-8 개인정보 마스킹: 로그에는 이메일 원문 대신 마스킹된 형태만 남긴다.
        log.info("[MOCK MAIL] to={} subject={}\n{}", mask(to), subject, body);
    }

    private String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }
}
