package com.pokeemc.trade.client;

import com.pokeemc.trade.network.TradeAssetPagePacket;
import com.pokeemc.trade.network.TradeDirectoryPacket;
import com.pokeemc.trade.network.TradeResultPacket;
import com.pokeemc.trade.network.TradeSnapshotPacket;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 S2C 统一入口（计划 5.4，Task 9）。
 * <p>
 * 全部在客户端主线程更新 {@link TradeClientState#INSTANCE}；
 * 打开的 {@link PlayerTradeScreen} 每帧读取该状态渲染，无需回调引用。
 * 结果包仅用于日志/轻提示（业务反馈以快照为准）。
 */
public final class TradePacketClientHandler {

    private TradePacketClientHandler() {
    }

    public static void onResult(TradeResultPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            // 失败提示由 Screen 读取最近结果展示；此处保持状态纯净
            if (!packet.success()) {
                com.pokeemc.PokeEMC.LOGGER.debug("PokeEMC: trade op failed {} err={}",
                        packet.requestId(), packet.error());
            }
        });
    }

    public static void onSnapshot(TradeSnapshotPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TradeClientState.INSTANCE.onSnapshot(packet));
    }

    public static void onDirectory(TradeDirectoryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TradeClientState.INSTANCE.onDirectory(packet));
    }

    public static void onAssetPage(TradeAssetPagePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> TradeClientState.INSTANCE.onAssetPage(packet));
    }
}
