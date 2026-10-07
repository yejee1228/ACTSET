package com.actset.conversion;

import com.actset.conversion.layout.ElementRole;
import com.actset.conversion.layout.LayoutPlanner;
import com.actset.domain.DesignElement;
import com.actset.repository.DesignElementRepository;
import com.actset.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 규격변환 ⑤ 저장 — 요소 파일은 accounts/{고객ID}/projects/{프로젝트ID}/elements/ 에, 파일 정보는 design_elements에.
 * 프로젝트당 요소 세트는 하나다(분해 1회 — docs/05). 다시 추출하면 이전 세트를 지우고 새로 쓴다.
 */
@Service
public class DesignElementStore {

    private final DesignElementRepository repository;
    private final StorageService storage;
    private final ObjectMapper objectMapper;

    public DesignElementStore(DesignElementRepository repository, StorageService storage, ObjectMapper objectMapper) {
        this.repository = repository;
        this.storage = storage;
        this.objectMapper = objectMapper;
    }

    public static String projectFolder(UUID accountId, UUID projectId) {
        return "accounts/" + accountId + "/projects/" + projectId;
    }

    @Transactional
    public List<DesignElement> replaceAll(UUID accountId, UUID projectId, ElementExtractionService.Result result) {
        for (DesignElement old : repository.findByProjectIdOrderByLayerOrderAsc(projectId)) {
            storage.delete(old.getStoragePath());
        }
        repository.deleteByProjectId(projectId);
        repository.flush();

        List<DesignElement> saved = new ArrayList<>();
        for (ElementExtractionService.Extracted e : result.elements()) {
            UUID id = UUID.randomUUID();
            String path = projectFolder(accountId, projectId) + "/elements/" + e.role().name().toLowerCase()
                    + "_" + e.key() + "_" + id + ".png";
            storage.store(png(e.image()), path, "image/png");

            DesignElement row = new DesignElement();
            row.setId(id);
            row.setAccountId(accountId);
            row.setProjectId(projectId);
            row.setRole(e.role().name());
            row.setOrigin(e.origin());
            row.setLabel(e.label());
            row.setStoragePath(path);
            row.setX(e.bounds().x);
            row.setY(e.bounds().y);
            row.setWidth(e.bounds().width);
            row.setHeight(e.bounds().height);
            row.setSourceWidth(result.width());
            row.setSourceHeight(result.height());
            row.setLayerOrder(e.z());
            row.setMeta(e.meta() != null ? e.meta() : objectMapper.createObjectNode());
            saved.add(repository.save(row));
        }
        return saved;
    }

    /** 저장된 요소를 배치 엔진 입력으로 되살린다(규격 변환은 분해 없이 이것만 쓴다). NOISE는 뺀다. */
    @Transactional(readOnly = true)
    public List<LayoutPlanner.SourceElement> load(UUID projectId) {
        List<LayoutPlanner.SourceElement> out = new ArrayList<>();
        for (DesignElement d : repository.findByProjectIdOrderByLayerOrderAsc(projectId)) {
            ElementRole role = ElementRole.parse(d.getRole());
            if (role == ElementRole.NOISE) continue;
            out.add(new LayoutPlanner.SourceElement(d.getId().toString(), role, read(d.getStoragePath()),
                    new Rectangle(d.getX(), d.getY(), d.getWidth(), d.getHeight()), d.getLayerOrder()));
        }
        return out;
    }

    private BufferedImage read(String path) {
        try {
            return ImageIO.read(new ByteArrayInputStream(storage.read(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
