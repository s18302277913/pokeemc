package com.pokeemc.trade.network;

import com.pokeemc.trade.model.TradeError;

/**
 * C2S 包边界校验（计划 5.1）：handler 在解码后、进入 TradeService 前调用。
 * 返回 {@link TradeError#NONE} 表示通过；越界输入返回稳定错误码，绝不抛未捕获异常。
 */
public final class TradePacketBoundaries {

    private TradePacketBoundaries() {
    }

    public static TradeError checkCreate(CreateTradePacket packet) {
        return TradeError.NONE; // requestId/targetPlayerId 非 null 由 codec 保证
    }

    public static TradeError checkOfferItem(OfferItemPacket packet) {
        if (packet.inventorySlot() < 0 || packet.inventorySlot() > TradePacketLimits.MAX_INVENTORY_SLOT) {
            return TradeError.INVALID_ITEM_SLOT;
        }
        if (packet.count() < 1 || packet.count() > TradePacketLimits.MAX_ITEM_COUNT) {
            return TradeError.INVALID_COUNT;
        }
        return TradeError.NONE;
    }

    public static TradeError checkOfferPkm(OfferPkmPacket packet) {
        if (packet.amount() < 1 || packet.amount() > TradePacketLimits.MAX_PKM_AMOUNT) {
            return TradeError.PKM_INVALID_AMOUNT;
        }
        return TradeError.NONE;
    }

    public static TradeError checkOfferPokemon(OfferPokemonPacket packet) {
        String kind = packet.storageKind();
        if ("party".equals(kind)) {
            if (packet.box() != -1 || packet.slot() < 0 || packet.slot() > TradePacketLimits.MAX_PARTY_SLOT) {
                return TradeError.INVALID_INPUT;
            }
        } else if ("pc".equals(kind)) {
            if (packet.box() < 0 || packet.box() > TradePacketLimits.MAX_PC_BOX
                    || packet.slot() < 0 || packet.slot() > TradePacketLimits.MAX_PC_SLOT) {
                return TradeError.INVALID_INPUT;
            }
        } else {
            return TradeError.INVALID_INPUT;
        }
        return TradeError.NONE;
    }

    public static TradeError checkRemoveAsset(RemoveOfferAssetPacket packet) {
        return TradeError.NONE; // assetId 非 null 由 codec 保证
    }

    public static TradeError checkConfirm(ConfirmTradePacket packet) {
        return TradeError.NONE;
    }

    public static TradeError checkCancel(CancelTradePacket packet) {
        return TradeError.NONE;
    }

    public static TradeError checkDirectory(RequestTradeDirectoryPacket packet) {
        if (packet.query() != null && packet.query().length() > TradePacketLimits.MAX_SEARCH_LENGTH) {
            return TradeError.INVALID_INPUT;
        }
        if (packet.page() < 0 || packet.page() > TradePacketLimits.MAX_PAGE_NUMBER) {
            return TradeError.INVALID_INPUT;
        }
        if (packet.pageSize() < 1 || packet.pageSize() > TradePacketLimits.MAX_DIRECTORY_PAGE_SIZE) {
            return TradeError.INVALID_INPUT;
        }
        return TradeError.NONE;
    }

    public static TradeError checkAssetPage(RequestTradeAssetPagePacket packet) {
        if (packet.kind() == null) {
            return TradeError.INVALID_INPUT;
        }
        if (packet.page() < 0 || packet.page() > TradePacketLimits.MAX_PAGE_NUMBER) {
            return TradeError.INVALID_INPUT;
        }
        if (packet.pageSize() < 1 || packet.pageSize() > TradePacketLimits.MAX_ASSET_PAGE_SIZE) {
            return TradeError.INVALID_INPUT;
        }
        return TradeError.NONE;
    }

    public static TradeError checkSetPreference(SetDeliveryPreferencePacket packet) {
        if (packet.preference() == null) {
            return TradeError.INVALID_INPUT;
        }
        return TradeError.NONE;
    }
}
