package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.market.TradeMarketService;
import com.pokeemc.menu.ExchangeMenu;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeResult;
import com.poketrade.api.market.MarketTradeService.CartLine;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 客户端 -> 服务器：购物车批量买入（服务端重新报价、全成或全败）。 */
public record ExchangeBuyPacket(String sessionId, String operationId, List<CartLineWire> lines)
        implements CustomPacketPayload {

    /** 购物车行数上限，与服务端 {@link TradeMarketService#MAX_LINES} 对齐。 */
    private static final int MAX_LINES = 27;

    public static final Type<ExchangeBuyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "exchange_buy"));

    public record CartLineWire(String itemId, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, CartLineWire> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, CartLineWire::itemId,
                        ByteBufCodecs.VAR_INT, CartLineWire::count,
                        CartLineWire::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<CartLineWire>> LINES_CODEC =
            new StreamCodec<>() {
                @Override
                public List<CartLineWire> decode(RegistryFriendlyByteBuf buf) {
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    if (size < 0 || size > MAX_LINES) {
                        throw new IllegalArgumentException("bad buy lines size: " + size);
                    }
                    List<CartLineWire> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(CartLineWire.STREAM_CODEC.decode(buf));
                    }
                    return list;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, List<CartLineWire> list) {
                    ByteBufCodecs.VAR_INT.encode(buf, list.size());
                    for (CartLineWire line : list) {
                        CartLineWire.STREAM_CODEC.encode(buf, line);
                    }
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeBuyPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ExchangeBuyPacket::sessionId,
                    ByteBufCodecs.STRING_UTF8, ExchangeBuyPacket::operationId,
                    LINES_CODEC, ExchangeBuyPacket::lines,
                    ExchangeBuyPacket::new);

    @Override
    public Type<ExchangeBuyPacket> type() {
        return TYPE;
    }

    /**
     * 服务端执行：校验玩家处于交易所菜单会话 → 服务端重新报价批量买入 →
     * 结果写入菜单 DataSlot（客户端轮询展示）。服务端不信任客户端价格/余额。
     */
    public static void handle(ExchangeBuyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (!(player.containerMenu instanceof ExchangeMenu menu)) {
                    return; // 必须处于交易所菜单会话
                }
                List<CartLine> lines = new ArrayList<>(packet.lines().size());
                for (CartLineWire w : packet.lines()) {
                    TradeItemId id;
                    try {
                        id = TradeItemId.parse(w.itemId());
                    } catch (IllegalArgumentException e) {
                        menu.reportTradeResult(ExchangeMenu.Operation.BUY, TradeResult.INVALID_QUANTITY);
                        return;
                    }
                    lines.add(new CartLine(id, w.count()));
                }
                TradeResult result = TradeMarketService.forServer().buyBatch(
                        player.getUUID(), lines, packet.operationId());
                menu.reportTradeResult(ExchangeMenu.Operation.BUY, result);
            }
        });
    }
}
