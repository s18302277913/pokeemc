package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.model.TradeError;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * S2C：操作结果回执（计划 5.2/5.3）。错误只回复请求者；
 * 成功时附带最新 revision（客户端以此推进乐观锁）。tradeId 在创建失败时为空。
 */
public record TradeResultPacket(
        UUID requestId,
        UUID tradeId,
        long revision,
        boolean success,
        TradeError error
) implements CustomPacketPayload {

    public static final Type<TradeResultPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_result"));

    public static final StreamCodec<ByteBuf, TradeResultPacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, p.requestId());
                buf.writeBoolean(p.success());
                buf.writeBoolean(p.tradeId() != null);
                if (p.tradeId() != null) {
                    TradePayloadCodecs.UUID_CODEC.encode(buf, p.tradeId());
                }
                VarLong.write(buf, p.revision());
                TradePayloadCodecs.TRADE_ERROR.encode(buf, p.error());
            },
            buf -> {
                UUID requestId = TradePayloadCodecs.UUID_CODEC.decode(buf);
                boolean success = buf.readBoolean();
                UUID tradeId = buf.readBoolean() ? TradePayloadCodecs.UUID_CODEC.decode(buf) : null;
                long revision = VarLong.read(buf);
                TradeError error = TradePayloadCodecs.TRADE_ERROR.decode(buf);
                return new TradeResultPacket(requestId, tradeId, revision, success, error);
            });

    /** 成功回执（tradeId 可能为空，如 claim 后交易已移除） */
    public static TradeResultPacket ok(UUID requestId, UUID tradeId, long revision) {
        return new TradeResultPacket(requestId, tradeId, revision, true, TradeError.NONE);
    }

    /** 失败回执（不携带 tradeId，避免向非参与者泄露交易标识） */
    public static TradeResultPacket fail(UUID requestId, TradeError error) {
        return new TradeResultPacket(requestId, null, 0, false, error);
    }

    @Override
    public Type<TradeResultPacket> type() {
        return TYPE;
    }
}
