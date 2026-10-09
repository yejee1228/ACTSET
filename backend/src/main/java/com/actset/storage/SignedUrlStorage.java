package com.actset.storage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 서명 URL 공통부 — 결과물은 만료되는 서명 URL로만 내려준다(CLAUDE.md 규칙 7).
 * 저장소(로컬·S3)와 무관하게 앱이 HMAC으로 서명하고 FileServingController가 검증 후 내려준다.
 */
public abstract class SignedUrlStorage implements StorageService {

    private final String signingSecret;

    protected SignedUrlStorage(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    @Override
    public String signedUrl(String relativePath, Duration ttl) {
        long exp = Instant.now().plus(ttl).getEpochSecond();
        String sig = sign(relativePath, exp);
        String encodedPath = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(relativePath.getBytes(StandardCharsets.UTF_8));
        return "/api/v1/files/" + encodedPath + "?exp=" + exp + "&sig=" + sig;
    }

    @Override
    public boolean verifySignature(String relativePath, long expiresEpochSeconds, String signature) {
        if (Instant.now().getEpochSecond() > expiresEpochSeconds) {
            return false;
        }
        String expected = sign(relativePath, expiresEpochSeconds);
        return java.security.MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String relativePath, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal((relativePath + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
