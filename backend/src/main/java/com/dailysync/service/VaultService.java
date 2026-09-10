package com.dailysync.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dailysync.common.BizException;
import com.dailysync.common.HashUtil;
import com.dailysync.dto.CreateSyncTokenRequest;
import com.dailysync.dto.CreateVaultRequest;
import com.dailysync.dto.SyncTokenCreatedResponse;
import com.dailysync.dto.SyncTokenInfoResponse;
import com.dailysync.dto.VaultResponse;
import com.dailysync.entity.SyncToken;
import com.dailysync.entity.Vault;
import com.dailysync.mapper.SyncTokenMapper;
import com.dailysync.mapper.VaultMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * 仓库与同步令牌管理。
 * 归属校验统一走 {@link #ownedVault}：不存在与非本人一律 404，不暴露仓库是否存在。
 */
@Service
@RequiredArgsConstructor
public class VaultService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final VaultMapper vaultMapper;
    private final SyncTokenMapper syncTokenMapper;

    /** 创建仓库（用户内名称唯一，冲突 409），新仓库 version=0。 */
    @Transactional
    public VaultResponse create(Long userId, CreateVaultRequest req) {
        Long count = vaultMapper.selectCount(Wrappers.<Vault>lambdaQuery()
                .eq(Vault::getUserId, userId).eq(Vault::getName, req.name()));
        if (count != null && count > 0) {
            throw new BizException(HttpStatus.CONFLICT, "同名仓库已存在");
        }
        Vault vault = new Vault();
        vault.setUserId(userId);
        vault.setName(req.name());
        vault.setVersion(0L);
        vault.setCreatedAt(LocalDateTime.now());
        vaultMapper.insert(vault);
        return toResponse(vault);
    }

    public List<VaultResponse> list(Long userId) {
        return vaultMapper.selectList(Wrappers.<Vault>lambdaQuery()
                        .eq(Vault::getUserId, userId).orderByAsc(Vault::getId)).stream()
                .map(this::toResponse)
                .toList();
    }

    /** 签发同步令牌（dst_ + 32 字节随机数的十六进制），一个仓库可签多枚（一台设备一枚）。 */
    @Transactional
    public SyncTokenCreatedResponse issueToken(Long userId, Long vaultId, CreateSyncTokenRequest req) {
        Vault vault = ownedVault(userId, vaultId);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = "dst_" + HexFormat.of().formatHex(bytes);

        SyncToken entity = new SyncToken();
        entity.setVaultId(vault.getId());
        entity.setName(req.name() == null ? "" : req.name().trim());
        entity.setTokenHash(HashUtil.sha256Hex(token));
        entity.setStatus(1);
        entity.setCreatedAt(LocalDateTime.now());
        syncTokenMapper.insert(entity);
        // 明文 token 只出现在这一次响应里，之后库里只有哈希
        return new SyncTokenCreatedResponse(entity.getId(), entity.getName(), token, entity.getCreatedAt());
    }

    /** 列出仓库的全部令牌（不含明文）。 */
    public List<SyncTokenInfoResponse> listTokens(Long userId, Long vaultId) {
        ownedVault(userId, vaultId);
        return syncTokenMapper.selectList(Wrappers.<SyncToken>lambdaQuery()
                        .eq(SyncToken::getVaultId, vaultId).orderByAsc(SyncToken::getId)).stream()
                .map(t -> new SyncTokenInfoResponse(t.getId(), t.getName(), t.getStatus(),
                        t.getLastUsedAt(), t.getCreatedAt()))
                .toList();
    }

    /** 撤销令牌（status=0），立即生效且不可恢复。 */
    @Transactional
    public void revokeToken(Long userId, Long vaultId, Long tokenId) {
        ownedVault(userId, vaultId);
        SyncToken token = syncTokenMapper.selectById(tokenId);
        if (token == null || !token.getVaultId().equals(vaultId)) {
            throw new BizException(HttpStatus.NOT_FOUND, "令牌不存在");
        }
        token.setStatus(0);
        syncTokenMapper.updateById(token);
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
