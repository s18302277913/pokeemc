package com.pokeemc.trade.service;

import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 生产线程检查器（Task 11）：交易写操作必须发生在服务端主线程，
 * 防止玩家线程/异步恢复线程并发修改持久化状态。
 */
public final class MinecraftServerThreadChecker implements ThreadChecker {

    @Override
    public void check() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("trade service used outside a server lifecycle");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("trade service used off the server thread");
        }
    }
}
