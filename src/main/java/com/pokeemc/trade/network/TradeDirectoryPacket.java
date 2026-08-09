package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.model.TradeCapability;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * S2C：在线玩家目录分页响应（计划 5.2）。目录条目不含任何资产统计，
 * 能力状态为短期提示；客户端按 2 秒 TTL 缓存。
 */
public record TradeDirectoryPacket(
        UUID requestId,
        List<PlayerDirectoryEntry> entries,
        int total,
        int page,
        int pageSize
) implements CustomPacketPayload {

    public static final Type<TradeDirectoryPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_directory_response"));

    /** 目录条目：UUID、当前公开名称、能力枚举 */
    public record PlayerDirectoryEntry(UUID playerId, String displayName, TradeCapability capability) {

        public PlayerDirectoryEntry {
            if (playerId == null || displayName == null || capability == null) {
                throw new IllegalArgumentException("playerId/displayName/capability cannot be null");
            }
        }
    }

    private static final StreamCodec<ByteBuf, PlayerDirectoryEntry> ENTRY = StreamCodec.of(
            (buf, e) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, e.playerId());
                TradePayloadCodecs.STRING_UTF8.encode(buf, e.displayName());
                TradePayloadCodecs.TRADE_CAPABILITY.encode(buf, e.capability());
            },
            buf -> new PlayerDirectoryEntry(
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    TradePayloadCodecs.STRING_UTF8.decode(buf),
                    TradePayloadCodecs.TRADE_CAPABILITY.decode(buf)));

    public static final StreamCodec<ByteBuf, TradeDirectoryPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, p.requestId());
                TradePayloadCodecs.boundedList(ENTRY, 50).encode(buf, p.entries());
                VarInt.write(buf, p.total());
                VarInt.write(buf, p.page());
                VarInt.write(buf, p.pageSize());
            },
            buf -> new TradeDirectoryPacket(
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    TradePayloadCodecs.boundedList(ENTRY, 50).decode(buf),
                    VarInt.read(buf),
                    VarInt.read(buf),
                    VarInt.read(buf)));

    @Override
    public Type<TradeDirectoryPacket> type() {
        return TYPE;
    }
}
