package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.exchange.market.TradeMarketService;
import com.pokeemc.menu.ExchangeMenu;
import com.poketrade.api.TradeItemId;
import com.poketrade.api.TradeResult;
import com.poketrade.api.market.MarketTradeService.CartLine;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 客户端 -> 服务器：背包批量出售（扣背包物品，钱包入账；服务端重新报价）。 */
public record ExchangeSellPacket(String sessionId, String operationId, List<LineWire> lines)
        implements CustomPacketPayload {

    /** 出售行数上限，与服务端 {@link TradeMarketService#MAX_LINES} 对齐。 */
    private static final int MAX_LINES = 27;

    public static final Type<ExchangeSellPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "exchange_sell"));

    public record LineWire(String itemId, int count) {
        public static final StreamCodec<RegistryFriendlyByteBuf, LineWire> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, LineWire::itemId,
                        ByteBufCodecs.VAR_INT, LineWire::count,
                        LineWire::new);
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<LineWire>> LINES_CODEC =
            new StreamCodec<>() {
                @Override
                public List<LineWire> decode(RegistryFriendlyByteBuf buf) {
                    int size = ByteBufCodecs.VAR_INT.decode(buf);
                    if (size < 0 || size > MAX_LINES) {
                        throw new IllegalArgumentException("bad sell lines size: " + size);
                    }
                    List<LineWire> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(LineWire.STREAM_CODEC.decode(buf));
                    }
                    return list;
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, List<LineWire> list) {
                    ByteBufCodecs.VAR_INT.encode(buf, list.size());
                    for (LineWire line : list) {
                        LineWire.STREAM_CODEC.encode(buf, line);
                    }
                }
            };

    public static final StreamCodec<RegistryFriendlyByteBuf, ExchangeSellPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, ExchangeSellPacket::sessionId,
                    ByteBufCodecs.STRING_UTF8, ExchangeSellPacket::operationId,
                    LINES_CODEC, ExchangeSellPacket::lines,
                    ExchangeSellPacket::new);

    @Override
    public Type<ExchangeSellPacket> type() {
        return TYPE;
    }

    /**
     * 服务端执行：校验玩家处于交易所菜单会话 → 服务端重新报价背包出售 →
     * 结果写入菜单 DataSlot（客户端轮询展示）。服务端不信任客户端价格/数量。
     */
    public static void handle(ExchangeSellPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                if (!(player.containerMenu instanceof ExchangeMenu menu)) {
                    return; // 必须处于交易所菜单会话
                }
                List<CartLine> lines = new ArrayList<>(packet.lines().size());
                for (LineWire w : packet.lines()) {
                    TradeItemId id;
                    try {
                        id = TradeItemId.parse(w.itemId());
                    } catch (IllegalArgumentException e) {
                        menu.reportTradeResult(ExchangeMenu.Operation.INVENTORY_SELL, TradeResult.INVALID_QUANTITY);
                        return;
                    }
                    lines.add(new CartLine(id, w.count()));
                }
                TradeResult result;
                // 单行且鼠标携带栈匹配（中栏单个卖出，物品可能从仓储拿起未入背包）：
                // 直接从携带栈出售，避免背包统计为 0 误报“数量无效”
                ItemStack carried = menu.getCarried();
                if (lines.size() == 1 && !carried.isEmpty()) {
                    CartLine line = lines.get(0);
                    ResourceLocation rl = BuiltInRegistries.ITEM.getKey(carried.getItem());
                    TradeItemId carriedId = rl == null ? null
                            : new TradeItemId(rl.getNamespace(), rl.getPath());
                    if (carriedId != null && carriedId.equals(line.itemId())
                            && line.count() > 0 && line.count() <= carried.getCount()) {
                        result = TradeMarketService.forServer().sellFromCarried(player, menu, line);
                    } else {
                        result = TradeMarketService.forServer().sellFromInventory(
                                player.getUUID(), lines, packet.operationId());
                    }
                } else {
                    result = TradeMarketService.forServer().sellFromInventory(
                            player.getUUID(), lines, packet.operationId());
                }
                menu.reportTradeResult(ExchangeMenu.Operation.INVENTORY_SELL, result);
            }
        });
    }
}
