package com.pokeemc.storage;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 64 种权限子集参数化测试：证明任一权限都不隐含其他权限。
 *
 * <p>对 2^6 = 64 种 allow 组合逐一验证 {@link StoragePermissionSet#allows}
 * 恰好只对集合内权限返回 true，不存在隐式层级。</p>
 */
class StoragePermissionMatrixTest {

    static List<EnumSet<StoragePermission>> allSubsets() {
        StoragePermission[] values = StoragePermission.values();
        return IntStream.range(0, 1 << values.length)
                .mapToObj(mask -> {
                    EnumSet<StoragePermission> set = EnumSet.noneOf(StoragePermission.class);
                    for (int i = 0; i < values.length; i++) {
                        if ((mask & (1 << i)) != 0) {
                            set.add(values[i]);
                        }
                    }
                    return set;
                })
                .toList();
    }

    @ParameterizedTest(name = "subset {index}")
    @MethodSource("allSubsets")
    void permissionSetAllowsExactlyItsOwnMembers(EnumSet<StoragePermission> subset) {
        StoragePermissionSet set = StoragePermissionSet.from(subset);
        for (StoragePermission p : StoragePermission.values()) {
            assertEquals(subset.contains(p), set.allows(p),
                    "allows(" + p + ") must be " + subset.contains(p) + " for subset " + subset);
        }
    }

    @ParameterizedTest(name = "single {index}")
    @MethodSource("allSubsets")
    void noPermissionImpliesAnother(EnumSet<StoragePermission> subset) {
        StoragePermissionSet set = StoragePermissionSet.from(subset);
        // 若只授予一个权限，则其余五个必须全部拒绝。
        for (StoragePermission granted : subset) {
            EnumSet<StoragePermission> only = EnumSet.of(granted);
            StoragePermissionSet single = StoragePermissionSet.from(only);
            for (StoragePermission p : StoragePermission.values()) {
                if (p == granted) {
                    assertEquals(true, single.allows(p), granted + " must be allowed");
                } else {
                    assertEquals(false, single.allows(p),
                            granted + " must not imply " + p);
                }
            }
        }
    }
}
