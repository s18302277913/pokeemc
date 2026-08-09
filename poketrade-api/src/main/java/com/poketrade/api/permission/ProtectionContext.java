package com.poketrade.api.permission;

import com.poketrade.api.storage.StorageId;

import java.util.Objects;
import java.util.UUID;

/**
 * 保护检查上下文（纯 Java，不暴露 Minecraft/NeoForge 类型）。
 *
 * @param actorId   行为者玩家 UUID
 * @param storageId 目标仓储的稳定标识
 * @param action    待判定的动作
 */
public record ProtectionContext(UUID actorId, StorageId storageId, ProtectionAction action) {

    public ProtectionContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(storageId, "storageId");
        Objects.requireNonNull(action, "action");
    }
}
