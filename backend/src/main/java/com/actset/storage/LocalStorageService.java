package com.actset.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 로컬 파일시스템 기반 StorageService (S3 대체, 개발용).
 * 저장 위치는 actset.storage.root — .gitignore의 storage/ 규칙과 일치한다(docs/09 파일 저장 구조).
 */
@Service
public class LocalStorageService implements StorageService {

    private final Path root;
    private final String signingSecret;

    public LocalStorageService(@Value("${actset.storage.root:../storage}") String root,
                                @Value("${actset.storage.signing-secret:dev-only-signing-secret-change-in-prod}") String signingSecret) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.signingSecret = signingSecret;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path resolve(String relativePath) {
        Path p = root.resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw new SecurityException("저장 경로가 storage root를 벗어났습니다: " + relativePath);
        }
        return p;
    }

    @Override
    public void store(byte[] data, String relativePath, String contentType) {
        try {
            Path target = resolve(relativePath);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    @Override
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
