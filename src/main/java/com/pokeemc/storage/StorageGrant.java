package com.pokeemc.storage;

import java.util.Objects;

/**
 * 单个主体的授权记录，同时保存 allow 与 deny 集合。
 *
 * <p>deny 用于模板叠加与显式收回单项权限：模板 allow 与本地 deny 冲突时，
 * deny 优先。持久化格式必须保留 deny 能力，即使 UI 首版只暴露“允许/未授予”。</p>
 */
public record StorageGrant(StoragePermissionSet allow, StoragePermissionSet deny) {

    public static final StorageGrant NONE =
            new StorageGrant(StoragePermissionSet.EMPTY, StoragePermissionSet.EMPTY);

    public StorageGrant {
        Objects.requireNonNull(allow, "allow");
        Objects.requireNonNull(deny, "deny");
    }

    public static StorageGrant allow(StoragePermission... permissions) {
        return new StorageGrant(StoragePermissionSet.of(permissions), StoragePermissionSet.EMPTY);
    }

    public static StorageGrant deny(StoragePermission... permissions) {
        return new StorageGrant(StoragePermissionSet.EMPTY, StoragePermissionSet.of(permissions));
    }

    public static StorageGrant of(StoragePermissionSet allow, StoragePermissionSet deny) {
        return new StorageGrant(allow, deny);
    }

    /**
     * 判定是否授予权限：allow 包含且 deny 不含。
     */
    public boolean allows(StoragePermission permission) {
        return allow.allows(permission) && !deny.allows(permission);
    }
}
