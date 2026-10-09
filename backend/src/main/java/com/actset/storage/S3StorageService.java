package com.actset.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * S3 기반 StorageService(운영, docs/09). 버킷은 비공개 — 사용자는 앱이 서명한 URL로만 받는다(규칙 7).
 * 자격 증명은 코드에 없다(P-2): EC2 인스턴스 역할 또는 기본 자격 증명 체인을 쓴다.
 */
@Service
@ConditionalOnProperty(name = "actset.storage.type", havingValue = "s3")
public class S3StorageService extends SignedUrlStorage {

    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    public S3StorageService(@Value("${actset.storage.s3.bucket}") String bucket,
                            @Value("${actset.storage.s3.region:ap-northeast-2}") String region,
                            @Value("${actset.storage.s3.prefix:}") String prefix,
                            @Value("${actset.storage.signing-secret}") String signingSecret) {
        super(signingSecret);
        this.bucket = bucket;
        this.prefix = prefix == null || prefix.isBlank() ? "" : prefix.replaceAll("/+$", "") + "/";
        this.s3 = S3Client.builder().region(Region.of(region))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private String key(String relativePath) {
        if (relativePath.contains("..")) {
            throw new SecurityException("허용되지 않는 저장 경로: " + relativePath);
        }
        return prefix + relativePath;
    }

    @Override
    public void store(byte[] data, String relativePath, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(relativePath)).contentType(contentType).build(),
                RequestBody.fromBytes(data));
    }

    @Override
    public byte[] read(String relativePath) {
        return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key(relativePath)).build()).asByteArray();
    }

    @Override
    public boolean exists(String relativePath) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key(relativePath)).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw e;
        }
    }

    @Override
    public void delete(String relativePath) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key(relativePath)).build());
    }
}
