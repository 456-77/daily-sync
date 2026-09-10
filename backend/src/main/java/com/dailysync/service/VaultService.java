package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.dto.VaultResponse;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.VaultMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仓库管理。M5.1 起：仓库只能由插件同步时按 Obsidian 仓库名自动创建
 * （{@link #findOrCreateVault}），Web 端不再提供手动创建入口；
 * 读取接口（列表/按日查询）仍按用户隔离。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultService {

    private final VaultMapper vaultMapper;
    private final AuditService auditService;

    /** 列出当前用户的所有仓库（按 id 升序），含各自最新 version。 */
    public List<VaultResponse> list(Long userId) {
        return vaultMapper.selectList(Wrappers.<Vault>lambdaQuery()
                        .eq(Vault::getUserId, userId).orderByAsc(Vault::getId)).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 按名取仓库，不存在则自动创建（插件首同步建仓的唯一入口）。
     *
     * <p>两台设备同时首同步同名仓库会先后撞 uk_user_name 唯一键，
     * 捕获 DuplicateKey 后重查即可拿到先插入的那行。新仓库 version=0（拉取游标起点），
     * 创建动作写审计（VAULT_CREATE，detail 注明插件自动创建）。
     *
     * @throws BizException 名称为空 / 超 64 字符
     */
    @Transactional
    public Vault findOrCreateVault(Long userId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            throw new BizException(HttpStatus.BAD_REQUEST, "vault: 仓库名长度需要 1-64");
        }
        Vault existing = selectByName(userId, trimmed);
        if (existing != null) {
            return existing;
        }
        Vault vault = new Vault();
        vault.setUserId(userId);
        vault.setName(trimmed);
        vault.setVersion(0L);
        vault.setCreatedAt(LocalDateTime.now());
        try {
            vaultMapper.insert(vault);
            auditService.record(userId, vault.getId(), AuditService.Action.VAULT_CREATE,
                    "插件同步自动创建仓库: " + trimmed, null);
            return vault;
        } catch (DuplicateKeyException e) {
            // 并发首同步：另一台设备先建了同名仓库，重查拿现成的
            return selectByName(userId, trimmed);
        }
    }

    private Vault selectByName(Long userId, String name) {
        return vaultMapper.selectOne(Wrappers.<Vault>lambdaQuery()
                .eq(Vault::getUserId, userId).eq(Vault::getName, name));
    }

    /** 取属于当前用户的仓库；不存在或不是本人的统一 404，不暴露仓库是否存在（供其他 Service 复用）。 */
    public Vault ownedVault(Long userId, Long vaultId) {
        Vault vault = vaultMapper.selectById(vaultId);
        if (vault == null || !vault.getUserId().equals(userId)) {
            throw new BizException(HttpStatus.NOT_FOUND, "仓库不存在");
        }
        return vault;
    }

    private VaultResponse toResponse(Vault vault) {
        return new VaultResponse(vault.getId(), vault.getName(), vault.getVersion(), vault.getCreatedAt());
    }
}
