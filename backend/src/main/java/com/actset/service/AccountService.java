package com.actset.service;

import com.actset.common.ApiException;
import com.actset.domain.Account;
import com.actset.domain.GeneratedAsset;
import com.actset.domain.Project;
import com.actset.domain.UploadedFile;
import com.actset.repository.AccountRepository;
import com.actset.repository.GeneratedAssetRepository;
import com.actset.repository.ProjectRepository;
import com.actset.repository.UploadedFileRepository;
import com.actset.storage.StorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 회원 탈퇴 처리(docs/10 "회원 탈퇴 처리").
 *
 * MVP 가정: 유예기간을 두지 않는다(docs가 "정책 판단"으로 열어둔 부분 — OVERNIGHT-LOG 기록).
 * DELETE /account 호출 즉시 개인 데이터를 동기 삭제한다. 배치로 미루는 대신,
 * FK를 ON DELETE CASCADE / SET NULL로 걸어(V1 마이그레이션) accounts 1건 삭제로
 * projects·generated_assets·uploaded_files·jobs·credit_transactions·print_order_drafts가
 * 함께 정리되고, selection_events는 owner_id/project_id가 null로 남는다(익명화).
 * 스토리지의 실제 파일은 DB에서 지워지기 전에 경로를 모아 별도로 삭제한다.
 */
@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final ProjectRepository projectRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final GeneratedAssetRepository generatedAssetRepository;
    private final StorageService storageService;

    public AccountService(AccountRepository accountRepository, ProjectRepository projectRepository,
                           UploadedFileRepository uploadedFileRepository,
                           GeneratedAssetRepository generatedAssetRepository,
                           StorageService storageService) {
        this.accountRepository = accountRepository;
        this.projectRepository = projectRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.generatedAssetRepository = generatedAssetRepository;
        this.storageService = storageService;
    }

    @Transactional
    public void withdraw(UUID accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow(ApiException::notFound);

        List<Project> projects = projectRepository.findByOwnerId(accountId);

        List<String> storagePaths = new ArrayList<>();
        for (Project project : projects) {
            for (UploadedFile f : uploadedFileRepository.findByProjectId(project.getId())) {
                storagePaths.add(f.getStoragePath());
            }
            for (GeneratedAsset a : generatedAssetRepository.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(project.getId())) {
                addIfPresent(storagePaths, a.getBaseImageUrl());
                addIfPresent(storagePaths, a.getImageUrl());
                addIfPresent(storagePaths, a.getPreviewImageUrl());
            }
        }

        account.setStatus("withdrawn");
        account.setDeletedAt(Instant.now());
        // 계정을 즉시 삭제한다 — ON DELETE CASCADE/SET NULL이 하위 개인데이터 정리와
        // selection_events 익명화를 DB 제약으로 보장한다.
        accountRepository.delete(account);

        storagePaths.forEach(storageService::delete);
    }

    private void addIfPresent(List<String> list, String urlOrPath) {
        if (urlOrPath != null && !urlOrPath.isBlank()) {
            list.add(urlOrPath);
        }
    }
}
