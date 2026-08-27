package com.actset.web;

import com.actset.storage.StorageService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * StorageService가 발급한 서명 URL을 실제로 서빙한다.
 * 만료·서명 불일치는 403으로 막는다 — 결과물은 공개 URL이 아니다(CLAUDE.md 규칙 7).
 */
@RestController
public class FileServingController {

    private final StorageService storageService;

    public FileServingController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/api/v1/files/{encodedPath}")
    public ResponseEntity<byte[]> serve(@PathVariable String encodedPath,
                                          @RequestParam long exp,
                                          @RequestParam String sig) {
        String relativePath = new String(Base64.getUrlDecoder().decode(encodedPath), StandardCharsets.UTF_8);
        if (!storageService.verifySignature(relativePath, exp, sig) || !storageService.exists(relativePath)) {
            return ResponseEntity.status(403).build();
        }
        byte[] data = storageService.read(relativePath);
        String contentType = URLConnection.guessContentTypeFromName(relativePath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=60")
                .contentType(contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM)
                .body(data);
    }
}
