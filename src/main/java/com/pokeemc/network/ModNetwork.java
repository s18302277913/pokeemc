package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.trade.client.TradePacketClientHandler;
import com.pokeemc.trade.network.AcceptTradePacket;
import com.pokeemc.trade.network.CancelTradePacket;
import com.pokeemc.trade.network.ConfirmTradePacket;
import com.pokeemc.trade.network.CreateTradePacket;
import com.pokeemc.trade.network.OfferItemPacket;
import com.pokeemc.trade.network.OfferPkmPacket;
import com.pokeemc.trade.network.OfferPokemonPacket;
import com.pokeemc.trade.network.RemoveOfferAssetPacket;
import com.pokeemc.trade.network.RequestTradeAssetPagePacket;
import com.pokeemc.trade.network.RequestTradeDirectoryPacket;
import com.pokeemc.trade.network.SetDeliveryPreferencePacket;
import com.pokeemc.trade.network.TradeAssetPagePacket;
import com.pokeemc.trade.network.TradeDirectoryPacket;
import com.pokeemc.trade.network.TradeResultPacket;
import com.pokeemc.trade.network.TradeSnapshotPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = PokeEMC.MODID) // [CHANGED] 官方 API：NeoForge 依 IModBusEvent 自动路由 mod bus，bus 属性已 [removal]
public class ModNetwork {

    /** 协议版本：增加不兼容 C2S/S2C 包或语义变更时递增（Task 8 从笼统 "2" 整理为常量） */
    public static final String PROTOCOL_VERSION = "3";

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(TradePacket.TYPE, TradePacket.STREAM_CODEC, TradePacket::handle);
        registrar.playToServer(SetCondenserTargetPacket.TYPE, SetCondenserTargetPacket.STREAM_CODEC, SetCondenserTargetPacket::handle);
        registrar.playToServer(StorageMovePacket.TYPE, StorageMovePacket.STREAM_CODEC, StorageMovePacket::handle);
        registrar.playToServer(StorageSellPacket.TYPE, StorageSellPacket.STREAM_CODEC, StorageSellPacket::handle);
        registrar.playToServer(StorageDepositPacket.TYPE, StorageDepositPacket.STREAM_CODEC, StorageDepositPacket::handle);
        registrar.playToServer(StorageDepositCarriedPacket.TYPE,
                StorageDepositCarriedPacket.STREAM_CODEC, StorageDepositCarriedPacket::handle);
        registrar.playToServer(StorageTransferPacket.TYPE,
                StorageTransferPacket.STREAM_CODEC, StorageTransferPacket::handle);
        registrar.playToServer(StorageWithdrawCarriedPacket.TYPE,
                StorageWithdrawCarriedPacket.STREAM_CODEC, StorageWithdrawCarriedPacket::handle);
        // Task 9：仓储浏览/管理
        registrar.playToServer(OpenStorageBrowserPacket.TYPE, OpenStorageBrowserPacket.STREAM_CODEC, OpenStorageBrowserPacket::handle);
        registrar.playToServer(QueryStoragesPacket.TYPE, QueryStoragesPacket.STREAM_CODEC, QueryStoragesPacket::handle);
        registrar.playToServer(StorageSnapshotPacket.TYPE, StorageSnapshotPacket.STREAM_CODEC, StorageSnapshotPacket::handle);
        registrar.playToServer(StorageManagePacket.TYPE, StorageManagePacket.STREAM_CODEC, StorageManagePacket::handle);
        registrar.playToClient(QueryStoragesPacket.Response.RESPONSE_TYPE, QueryStoragesPacket.Response.RESPONSE_CODEC, QueryStoragesPacket.Response::handleResponse);
        registrar.playToClient(StorageSnapshotPacket.Response.RESPONSE_TYPE, StorageSnapshotPacket.Response.RESPONSE_CODEC, StorageSnapshotPacket.Response::handleResponse);
        registrar.playToClient(StorageManagePacket.Response.RESPONSE_TYPE, StorageManagePacket.Response.RESPONSE_CODEC, StorageManagePacket.Response::handleResponse);
        registrar.playToClient(StorageDepositPacket.Response.RESPONSE_TYPE,
                StorageDepositPacket.Response.RESPONSE_CODEC, StorageDepositPacket.Response::handleResponse);
        registrar.playToClient(StorageMovePacket.Response.RESPONSE_TYPE,
                StorageMovePacket.Response.RESPONSE_CODEC, StorageMovePacket.Response::handleResponse);
        // 阶段 2：交易所目录 / 批量买入 / 背包出售
        registrar.playToServer(ExchangeCatalogPacket.Request.TYPE,
                ExchangeCatalogPacket.Request.STREAM_CODEC, ExchangeCatalogPacket::handle);
        registrar.playToClient(ExchangeCatalogPacket.Response.TYPE,
                ExchangeCatalogPacket.Response.STREAM_CODEC, ExchangeCatalogPacket::handleResponse);
        registrar.playToServer(ExchangeBuyPacket.TYPE, ExchangeBuyPacket.STREAM_CODEC, ExchangeBuyPacket::handle);
        registrar.playToServer(ExchangeSellPacket.TYPE, ExchangeSellPacket.STREAM_CODEC, ExchangeSellPacket::handle);
        // 阶段 4 Task 8：玩家交易 C2S（每操作一个 payload）
        registrar.playToServer(CreateTradePacket.TYPE, CreateTradePacket.STREAM_CODEC, CreateTradePacket::handle);
        registrar.playToServer(AcceptTradePacket.TYPE, AcceptTradePacket.STREAM_CODEC, AcceptTradePacket::handle);
        registrar.playToServer(OfferItemPacket.TYPE, OfferItemPacket.STREAM_CODEC, OfferItemPacket::handle);
        registrar.playToServer(OfferPkmPacket.TYPE, OfferPkmPacket.STREAM_CODEC, OfferPkmPacket::handle);
        registrar.playToServer(OfferPokemonPacket.TYPE, OfferPokemonPacket.STREAM_CODEC, OfferPokemonPacket::handle);
        registrar.playToServer(RemoveOfferAssetPacket.TYPE, RemoveOfferAssetPacket.STREAM_CODEC, RemoveOfferAssetPacket::handle);
        registrar.playToServer(ConfirmTradePacket.TYPE, ConfirmTradePacket.STREAM_CODEC, ConfirmTradePacket::handle);
        registrar.playToServer(CancelTradePacket.TYPE, CancelTradePacket.STREAM_CODEC, CancelTradePacket::handle);
        registrar.playToServer(SetDeliveryPreferencePacket.TYPE, SetDeliveryPreferencePacket.STREAM_CODEC, SetDeliveryPreferencePacket::handle);
        registrar.playToServer(RequestTradeDirectoryPacket.TYPE, RequestTradeDirectoryPacket.STREAM_CODEC, RequestTradeDirectoryPacket::handle);
        registrar.playToServer(RequestTradeAssetPagePacket.TYPE, RequestTradeAssetPagePacket.STREAM_CODEC, RequestTradeAssetPagePacket::handle);
        // 阶段 4 Task 8：玩家交易 S2C（统一回执 + 快照 + 目录/资产页响应）
        registrar.playToClient(TradeResultPacket.TYPE, TradeResultPacket.STREAM_CODEC, TradePacketClientHandler::onResult);
        registrar.playToClient(TradeSnapshotPacket.TYPE, TradeSnapshotPacket.STREAM_CODEC, TradePacketClientHandler::onSnapshot);
        registrar.playToClient(TradeDirectoryPacket.TYPE, TradeDirectoryPacket.STREAM_CODEC, TradePacketClientHandler::onDirectory);
        registrar.playToClient(TradeAssetPagePacket.TYPE, TradeAssetPagePacket.STREAM_CODEC, TradePacketClientHandler::onAssetPage);
        PokeEMC.LOGGER.info("PokeEMC: registered network payloads");
    }
}
