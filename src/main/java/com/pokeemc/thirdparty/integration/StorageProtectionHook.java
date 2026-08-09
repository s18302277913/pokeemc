package com.pokeemc.thirdparty.integration;

import com.poketrade.api.permission.ProtectionAction;
import com.poketrade.api.permission.ProtectionContext;
import com.poketrade.api.storage.StorageId;
import com.pokeemc.thirdparty.ProtectionRegistryImpl;
import com.pokeemc.thirdparty.protection.LoggingAudit;
import com.pokeemc.thirdparty.protection.ProtectionChain;

import java.util.Objects;
import java.util.UUID;

/**
 * 保护链进程级接入点：持有 {@link ProtectionRegistryImpl} 活动引用（每次
 * allows 现读注册表），未装配时恒放行。
 */
public final class StorageProtectionHook {

    private static final StorageProtectionHook UNLOADED =
            new StorageProtectionHook(new ProtectionRegistryImpl());

    private final ProtectionRegistryImpl registry;

    public StorageProtectionHook(ProtectionRegistryImpl registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** 未装配时使用：空注册表 → 恒放行。 */
    public static StorageProtectionHook unloaded() {
        return UNLOADED;
    }

    /** AND 语义：false 表示被第三方保护拒绝。 */
    public boolean allows(UUID actorId, StorageId storageId, ProtectionAction action) {
        return new ProtectionChain(registry.providers(), LoggingAudit.INSTANCE)
                .allows(new ProtectionContext(actorId, storageId, action));
    }
}
