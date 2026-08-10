package com.pokeemc.exchange.history;

import com.poketrade.api.TradeItemId;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.Set;
import java.util.UUID;

/**
 * [NEW] 会话 #21-H 修订：交易所「学习模式」个性化出售历史的服务端门面。
 *
 * <p>从 overworld {@code DataStorage} 取/建 {@link SalesHistorySavedData}。纯 JVM 单测/
 * 服务端未就绪（{@link ServerLifecycleHooks#getCurrentServer()} 为 null）时全部 no-op，
 * 不抛异常——目录过滤回退为「无出售历史 → 学习模式空列表」。</p>
 */
public final class SalesHistory {

    private SalesHistory() {
    }

    /** 记录一次成功出售（出售点调用；无服务端时静默忽略）。 */
    public static void record(UUID playerId, TradeItemId id) {
        SalesHistorySavedData data = data();
        if (data != null) {
            data.record(playerId, id);
        }
    }

    /** 该玩家是否出售过该物品（供目录过滤与测试）。 */
    public static boolean hasSold(UUID playerId, TradeItemId id) {
        SalesHistorySavedData data = data();
        return data != null && data.hasSold(playerId, id);
    }

    /** 该玩家已出售物品集合（不可变；无服务端返回空集）。 */
    public static Set<TradeItemId> soldItems(UUID playerId) {
        SalesHistorySavedData data = data();
        return data == null ? Set.of() : data.soldItems(playerId);
    }

    private static SalesHistorySavedData data() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        var overworld = server.overworld();
        if (overworld == null) {
            return null;
        }
        return overworld.getDataStorage()
                .computeIfAbsent(SalesHistorySavedData.factory(), SalesHistorySavedData.DATA_NAME);
    }
}
