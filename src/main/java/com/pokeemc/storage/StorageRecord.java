package com.pokeemc.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 单个逻辑仓储的持久化归属与设置记录。
 *
 * <p>不可变：任何变更通过 {@code withXxx} 返回新实例。revision 由
 * {@link StorageSavedData} 的统一变更入口通过 {@link #touch(long)} 递增，
 * 客户端管理请求必须携带 {@code expectedRevision} 以防旧状态覆盖新状态。</p>
 *
 * <p>模板绑定：{@code COPY} 在应用时复制模板权限后独立修改；{@code FOLLOW}
 * 保存模板引用并在模板更新时动态叠加，仓储本地显式 deny 可覆盖模板 allow。</p>
 */
public record StorageRecord(
        UUID ownerId,
        String ownerName,
        String displayName,
        Map<StoragePrincipal, StorageGrant> grants,
        String templateBinding,
        TemplateMode templateMode,
        boolean automationInsertEnabled,
        boolean automationExtractEnabled,
        boolean listedInBrowser,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long revision) {

    /** 显示名称最大长度（字符）。 */
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;

    /** 模板绑定模式。 */
    public enum TemplateMode {
        /** 应用时复制模板权限，之后独立修改。 */
        COPY,
        /** 保存模板引用，模板更新时动态叠加。 */
        FOLLOW
    }

    public StorageRecord {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(ownerName, "ownerName");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName too long: " + displayName.length());
        }
        for (int i = 0; i < displayName.length(); i++) {
            if (displayName.charAt(i) < 0x20) {
                throw new IllegalArgumentException(
                        "displayName contains control characters");
            }
        }
        Objects.requireNonNull(grants, "grants");
        grants = Collections.unmodifiableMap(new LinkedHashMap<>(grants));
        Objects.requireNonNull(templateMode, "templateMode");
        if (templateBinding == null && templateMode != TemplateMode.COPY) {
            throw new IllegalArgumentException(
                    "FOLLOW template mode requires a template binding");
        }
        if (templateBinding != null && templateBinding.isEmpty()) {
            throw new IllegalArgumentException("templateBinding must not be empty");
        }
        if (createdAtEpochMillis < 0 || updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
    }

    /**
     * 新建仓储记录：revision 从 1 开始，默认显示名取所有者名。
     */
    public static StorageRecord create(UUID ownerId, String ownerName, long nowEpochMillis) {
        return new StorageRecord(
                ownerId, ownerName, ownerName,
                Map.of(),
                null, TemplateMode.COPY,
                false, false, true,
                nowEpochMillis, nowEpochMillis, 1);
    }

    /** 重命名。 */
    public StorageRecord renamed(String newDisplayName) {
        return new StorageRecord(
                ownerId, ownerName, newDisplayName, grants,
                templateBinding, templateMode,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /** 修正所有者名（惰性修复旧记录：如末影箱旧的"末影箱"哨兵 ownerName）。 */
    public StorageRecord withOwnerName(String newOwnerName) {
        return new StorageRecord(
                ownerId, newOwnerName, displayName, grants,
                templateBinding, templateMode,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /** 写入或覆盖某个主体的授权。 */
    public StorageRecord withGrant(StoragePrincipal principal, StorageGrant grant) {
        Map<StoragePrincipal, StorageGrant> next = new LinkedHashMap<>(grants);
        next.put(principal, grant);
        return copyWithGrants(next);
    }

    /** 移除某个主体的授权。 */
    public StorageRecord withoutGrant(StoragePrincipal principal) {
        Map<StoragePrincipal, StorageGrant> next = new LinkedHashMap<>(grants);
        next.remove(principal);
        return copyWithGrants(next);
    }

    public StorageRecord withAutomationInsert(boolean enabled) {
        return new StorageRecord(
                ownerId, ownerName, displayName, grants,
                templateBinding, templateMode,
                enabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    public StorageRecord withAutomationExtract(boolean enabled) {
        return new StorageRecord(
                ownerId, ownerName, displayName, grants,
                templateBinding, templateMode,
                automationInsertEnabled, enabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    public StorageRecord withBrowserListed(boolean listed) {
        return new StorageRecord(
                ownerId, ownerName, displayName, grants,
                templateBinding, templateMode,
                automationInsertEnabled, automationExtractEnabled, listed,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /** 绑定模板。 */
    public StorageRecord withTemplate(String templateId, TemplateMode mode) {
        return new StorageRecord(
                ownerId, ownerName, displayName, grants,
                templateId, mode,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /** 解除模板绑定（独立修改，权限保留）。 */
    public StorageRecord withoutTemplate() {
        return new StorageRecord(
                ownerId, ownerName, displayName, grants,
                null, TemplateMode.COPY,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /**
     * 冻结 FOLLOW 绑定为独立 COPY：写入合并后的权限、清除模板引用并转为 COPY。
     * 删除模板或引用修复时使用，不得突然公开或清空权限。
     */
    public StorageRecord withFrozenCopy(Map<StoragePrincipal, StorageGrant> frozenGrants) {
        return new StorageRecord(
                ownerId, ownerName, displayName, frozenGrants,
                null, TemplateMode.COPY,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /**
     * 标记一次变更：更新 {@code updatedAt} 并递增 revision。
     */
    public StorageRecord touch(long nowEpochMillis) {
        return new StorageRecord(
                ownerId, ownerName, displayName, grants,
                templateBinding, templateMode,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, nowEpochMillis, revision + 1);
    }

    private StorageRecord copyWithGrants(Map<StoragePrincipal, StorageGrant> nextGrants) {
        return new StorageRecord(
                ownerId, ownerName, displayName, nextGrants,
                templateBinding, templateMode,
                automationInsertEnabled, automationExtractEnabled, listedInBrowser,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }
}
