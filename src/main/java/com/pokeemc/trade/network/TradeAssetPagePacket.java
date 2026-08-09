package com.pokeemc.trade.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.service.TradeAssetPage;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * S2C：本人资产页响应（计划 5.2）。只回复请求者本人（ownerId 必须等于请求者，
 * 客户端校验，服务端也校验），不存在"查看对手资产页"的协议分支。
 */
public record TradeAssetPagePacket(
        UUID requestId,
        UUID ownerId,
        long assetRevision,
        AssetPageKind kind,
        int page,
        int pageSize,
        int total,
        List<TradeAssetPage.TradeAssetEntry> entries
) implements CustomPacketPayload {

    public static final Type<TradeAssetPagePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "trade_asset_page_response"));

    /** 条目判别 codec：0=ItemEntry、1=PkmEntry、2=PokemonEntry */
    private static final StreamCodec<ByteBuf, TradeAssetPage.TradeAssetEntry> ENTRY = StreamCodec.of(
            (buf, e) -> {
                if (e instanceof TradeAssetPage.ItemEntry item) {
                    buf.writeByte(0);
                    TradePayloadCodecs.UUID_CODEC.encode(buf, item.assetId());
                    TradePayloadCodecs.STRING_UTF8.encode(buf, item.itemId());
                    VarInt.write(buf, item.count());
                    VarInt.write(buf, item.inventorySlot());
                } else if (e instanceof TradeAssetPage.PkmEntry pkm) {
                    buf.writeByte(1);
                    VarLong.write(buf, pkm.amount());
                } else if (e instanceof TradeAssetPage.PokemonEntry mon) {
                    buf.writeByte(2);
                    TradePayloadCodecs.UUID_CODEC.encode(buf, mon.assetId());
                    TradePayloadCodecs.UUID_CODEC.encode(buf, mon.pokemonId());
                    TradePayloadCodecs.STRING_UTF8.encode(buf, mon.species());
                    TradePayloadCodecs.STRING_UTF8.encode(buf, mon.form());
                    VarInt.write(buf, mon.level());
                    buf.writeBoolean(mon.shiny());
                    TradePayloadCodecs.STRING_UTF8.encode(buf, mon.nickname());
                    TradePayloadCodecs.STRING_UTF8.encode(buf, mon.sourceStorage());
                    VarInt.write(buf, mon.sourceBox());
                    VarInt.write(buf, mon.sourceSlot());
                } else {
                    throw new IllegalArgumentException("unknown asset entry " + e);
                }
            },
            buf -> {
                int kind = buf.readUnsignedByte();
                return switch (kind) {
                    case 0 -> new TradeAssetPage.ItemEntry(
                            TradePayloadCodecs.UUID_CODEC.decode(buf),
                            TradePayloadCodecs.STRING_UTF8.decode(buf),
                            VarInt.read(buf),
                            VarInt.read(buf));
                    case 1 -> new TradeAssetPage.PkmEntry(VarLong.read(buf));
                    case 2 -> new TradeAssetPage.PokemonEntry(
                            TradePayloadCodecs.UUID_CODEC.decode(buf),
                            TradePayloadCodecs.UUID_CODEC.decode(buf),
                            TradePayloadCodecs.STRING_UTF8.decode(buf),
                            TradePayloadCodecs.STRING_UTF8.decode(buf),
                            VarInt.read(buf),
                            buf.readBoolean(),
                            TradePayloadCodecs.STRING_UTF8.decode(buf),
                            TradePayloadCodecs.STRING_UTF8.decode(buf),
                            VarInt.read(buf),
                            VarInt.read(buf));
                    default -> throw new CorruptedFrameException("unknown asset entry kind " + kind);
                };
            });

    public static final StreamCodec<ByteBuf, TradeAssetPagePacket> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                TradePayloadCodecs.UUID_CODEC.encode(buf, p.requestId());
                TradePayloadCodecs.UUID_CODEC.encode(buf, p.ownerId());
                VarLong.write(buf, p.assetRevision());
                TradePayloadCodecs.ASSET_PAGE_KIND.encode(buf, p.kind());
                VarInt.write(buf, p.page());
                VarInt.write(buf, p.pageSize());
                VarInt.write(buf, p.total());
                TradePayloadCodecs.boundedList(ENTRY, 54).encode(buf, p.entries());
            },
            buf -> new TradeAssetPagePacket(
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    TradePayloadCodecs.UUID_CODEC.decode(buf),
                    VarLong.read(buf),
                    TradePayloadCodecs.ASSET_PAGE_KIND.decode(buf),
                    VarInt.read(buf),
                    VarInt.read(buf),
                    VarInt.read(buf),
                    TradePayloadCodecs.boundedList(ENTRY, 54).decode(buf)));

    @Override
    public Type<TradeAssetPagePacket> type() {
        return TYPE;
    }
}
