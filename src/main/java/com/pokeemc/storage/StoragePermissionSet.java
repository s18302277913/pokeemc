package com.pokeemc.storage;

import java.util.EnumSet;
import java.util.Set;

/**
 * 权限的不可变集合。仅作“包含”判定，不引入任何权限层级。
 */
public record StoragePermissionSet(EnumSet<StoragePermission> values) {

    public static final StoragePermissionSet EMPTY =
            new StoragePermissionSet(EnumSet.noneOf(StoragePermission.class));

    public StoragePermissionSet {
        values = values.isEmpty()
                ? EnumSet.noneOf(StoragePermission.class)
                : EnumSet.copyOf(values);
    }

    /**
     * 检查是否授予指定权限。
     */
    public boolean allows(StoragePermission permission) {
        return values.contains(permission);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public static StoragePermissionSet of(StoragePermission... permissions) {
        return new StoragePermissionSet(EnumSet.copyOf(java.util.Arrays.asList(permissions)));
    }

    /** 从任意 Set 构造；null 视为空。 */
    public static StoragePermissionSet from(Set<StoragePermission> source) {
        if (source == null || source.isEmpty()) {
            return EMPTY;
        }
        return new StoragePermissionSet(EnumSet.copyOf(source));
    }

    /** 两个权限集的交集（保守合并用：任何一方未授予的权限都会被移除）。 */
    public StoragePermissionSet intersect(StoragePermissionSet other) {
        EnumSet<StoragePermission> copy = EnumSet.copyOf(values);
        copy.retainAll(other.values);
        return new StoragePermissionSet(copy);
    }

    /** 两个权限集的并集（保守合并用：任何一方拒绝的权限都会被保留）。 */
    public StoragePermissionSet union(StoragePermissionSet other) {
        EnumSet<StoragePermission> copy = EnumSet.copyOf(values);
        copy.addAll(other.values);
        return new StoragePermissionSet(copy);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
