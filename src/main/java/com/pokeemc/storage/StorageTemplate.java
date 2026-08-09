package com.pokeemc.storage;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 权限模板：一组可复用、可应用到仓储的授权集合。
 *
 * <p>玩家模板仅所有者可见、可改、可应用到自己的仓储；服务器模板由管理员
 * 管理，可被所有玩家查看并应用。</p>
 *
 * <p>{@link #mergeGrants(Map, Map)} 提供 FOLLOW 冻结语义：本地显式 deny
 * 覆盖模板 allow，合并结果绝不静默扩大权限。</p>
 */
public record StorageTemplate(
        String id,
        Scope scope,
        UUID ownerId,
        String name,
        Map<StoragePrincipal, StorageGrant> grants,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long revision) {

    public static final int MAX_NAME_LENGTH = 32;

    private static final Pattern ID = Pattern.compile("[a-zA-Z0-9_.:-]+");

    /** 模板归属范围。 */
    public enum Scope {
        /** 玩家模板：仅所有者可见、可改、可应用。 */
        PLAYER,
        /** 服务器模板：管理员管理，全服可见可应用。 */
        SERVER
    }

    public StorageTemplate {
        Objects.requireNonNull(id, "id");
        if (!ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid template id: " + id);
        }
        Objects.requireNonNull(scope, "scope");
        if (scope == Scope.PLAYER) {
            Objects.requireNonNull(ownerId, "ownerId is required for PLAYER templates");
        }
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "name must be 1.." + MAX_NAME_LENGTH + " chars: " + name);
        }
        Objects.requireNonNull(grants, "grants");
        grants = Collections.unmodifiableMap(new LinkedHashMap<>(grants));
        if (createdAtEpochMillis < 0 || updatedAtEpochMillis < 0) {
            throw new IllegalArgumentException("timestamps must be non-negative");
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
    }

    /**
     * 新建模板：revision 从 1 开始。
     */
    public static StorageTemplate create(
            String id, Scope scope, UUID ownerId, String name,
            Map<StoragePrincipal, StorageGrant> grants, long nowEpochMillis) {
        return new StorageTemplate(
                id, scope, ownerId, name, grants,
                nowEpochMillis, nowEpochMillis, 1);
    }

    /** 替换模板权限（revision 由 {@link StorageSavedData} 统一递增）。 */
    public StorageTemplate withGrants(Map<StoragePrincipal, StorageGrant> newGrants) {
        return new StorageTemplate(
                id, scope, ownerId, name, newGrants,
                createdAtEpochMillis, updatedAtEpochMillis, revision);
    }

    /** 标记一次变更：更新 {@code updatedAt} 并递增 revision。 */
    public StorageTemplate touch(long nowEpochMillis) {
        return new StorageTemplate(
                id, scope, ownerId, name, grants,
                createdAtEpochMillis, nowEpochMillis, revision + 1);
    }

    /**
     * 合并模板与仓储本地授权（FOLLOW 冻结时使用）。
     *
     * <p>对每个主体：allow 取并集、deny 取并集。因此仓储本地显式 deny 会
     * 覆盖模板 allow，模板 deny 也保留；合并结果不会比模板或本地任一方
     * 授予更多权限。迭代顺序优先模板、后补本地独有主体。</p>
     */
    public static Map<StoragePrincipal, StorageGrant> mergeGrants(
            Map<StoragePrincipal, StorageGrant> templateGrants,
            Map<StoragePrincipal, StorageGrant> localGrants) {
        Objects.requireNonNull(templateGrants, "templateGrants");
        Objects.requireNonNull(localGrants, "localGrants");
        LinkedHashMap<StoragePrincipal, StorageGrant> merged = new LinkedHashMap<>();
        for (Map.Entry<StoragePrincipal, StorageGrant> entry : templateGrants.entrySet()) {
            merged.put(entry.getKey(),
                    mergeGrant(entry.getValue(), localGrants.get(entry.getKey())));
        }
        for (Map.Entry<StoragePrincipal, StorageGrant> entry : localGrants.entrySet()) {
            if (!merged.containsKey(entry.getKey())) {
                merged.put(entry.getKey(),
                        mergeGrant(templateGrants.get(entry.getKey()), entry.getValue()));
            }
        }
        return Collections.unmodifiableMap(merged);
    }

    private static StorageGrant mergeGrant(StorageGrant template, StorageGrant local) {
        if (template == null) {
            return local;
        }
        if (local == null) {
            return template;
        }
        return StorageGrant.of(
                union(template.allow(), local.allow()),
                union(template.deny(), local.deny()));
    }

    private static StoragePermissionSet union(StoragePermissionSet a, StoragePermissionSet b) {
        EnumSet<StoragePermission> set = EnumSet.noneOf(StoragePermission.class);
        set.addAll(a.values());
        set.addAll(b.values());
        return new StoragePermissionSet(set);
    }
}
