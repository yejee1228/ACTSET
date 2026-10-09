package com.actset.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 로컬 파일시스템 기반 StorageService (개발용, 기본값). 운영은 actset.storage.type=s3 → S3StorageService.
 * 저장 위치는 actset.storage.root — .gitignore의 storage/ 규칙과 일치한다(docs/09 파일 저장 구조).
 */
@Service
@ConditionalOnProperty(name = "actset.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService extends SignedUrlStorage {

    private final Path root;

    public LocalStorageService(@Value("${actset.storage.root:../storage}") String root,
                                @Value("${actset.storage.signing-secret:dev-only-signing-secret-change-in-prod}") String signingSecret) {
        super(signingSecret);
        this.root = Path.of(root).toAbsolutePath().normalize();
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
}
