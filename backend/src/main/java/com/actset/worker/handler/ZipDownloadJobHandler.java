package com.actset.worker.handler;

import com.actset.common.ApiException;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Job;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.storage.StorageService;
import com.actset.worker.JobHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** jobs.kind = 'zip_download' — ⑦ 선택·일괄 다운로드(4-2). 파일이 여러 개라 비동기로 압축한다. */
@Component
public class ZipDownloadJobHandler implements JobHandler {

    private final GeneratedAssetRepository generatedAssetRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public ZipDownloadJobHandler(GeneratedAssetRepository generatedAssetRepository, StorageService storageService,
                                  ObjectMapper objectMapper) {
        this.generatedAssetRepository = generatedAssetRepository;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String kind() {
        return "zip_download";
    }

    @Override
    public ObjectNode handle(Job job) throws Exception {
        JsonNode assetIdsNode = job.getPayload().path("asset_ids");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            for (JsonNode idNode : assetIdsNode) {
                UUID assetId = UUID.fromString(idNode.asText());
                GeneratedAsset asset = generatedAssetRepository.findById(assetId).orElseThrow(ApiException::notFound);
                if (asset.getImageUrl() == null) continue; // 만료된 대용량 원본은 건너뛴다
                byte[] data = storageService.read(asset.getImageUrl());
                String entryName = asset.getFormatCode() + "_" + asset.getId() + ".jpg";
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(data);
                zip.closeEntry();
            }
        }

        String zipPath = "projects/" + job.getProjectId() + "/downloads/" + job.getId() + ".zip";
        storageService.store(buffer.toByteArray(), zipPath, "application/zip");

        ObjectNode result = objectMapper.createObjectNode();
        result.put("zip_url", storageService.signedUrl(zipPath, Duration.ofHours(1)));
        return result;
    }
}
