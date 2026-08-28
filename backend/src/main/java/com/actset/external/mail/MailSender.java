package com.actset.external.mail;

/**
 * 메일 발송 경계(docs/09 "AWS SES 등(미선정)"). 0-9(발신 도메인 인증)가 끝나면
 * 이 인터페이스를 구현하는 SesMailSender로 교체한다.
 */
public interface MailSender {
    /** 발송 실패는 예외로 던진다 — 호출부가 로깅·재시도 여부를 결정한다(1-16 완료기준). */
    void send(String to, String subject, String body) throws Exception;
}
