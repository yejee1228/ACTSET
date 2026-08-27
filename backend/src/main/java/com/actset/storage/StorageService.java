package com.actset.storage;

import java.time.Duration;

/**
 * 파일 저장 추상화. MVP는 로컬 파일시스템(LocalStorageService) 구현체를 쓰고,
 * 운영 전환 시 S3 호환 구현체로 교체한다(docs/09) — 이 인터페이스만 지키면 된다.
 * 결과물 URL은 공개 URL이 아니라 만료되는 서명 URL로 제공한다(CLAUDE.md 규칙 7).
 */
public interface StorageService {

    /** relativePath(예: projects/{id}/uploads/{fileId}.jpg)에 데이터를 저장한다. */
    void store(byte[] data, String relativePath, String contentType);

    byte[] read(String relativePath);

    boolean exists(String relativePath);

    void delete(String relativePath);

    /** ttl 이후 만료되는 서명 URL을 발급한다. */
    String signedUrl(String relativePath, Duration ttl);

    /** signedUrl로 발급된 서명을 검증한다. FileServingController가 사용한다. */
    boolean verifySignature(String relativePath, long expiresEpochSeconds, String signature);
}
