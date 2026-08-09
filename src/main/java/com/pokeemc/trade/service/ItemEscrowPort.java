package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.ItemEscrowGateway;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PlayerInventoryStore;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;

import java.util.UUID;

/**
 * 物品托管端口（Task 6）：TradeService 依赖的托管抽象，测试注入 fake。
 */
public interface ItemEscrowPort {

    Outcome<ItemEscrowGateway.PreparedItem> prepare(PlayerInventoryStore store, int slot, int count, UUID owner);

    Outcome<ItemEscrowGateway.EscrowedItem> remove(PlayerInventoryStore store, ItemEscrowGateway.PreparedItem prepared, UUID owner);

    Outcome<Void> cancel(PlayerInventoryStore store, ItemEscrowGateway.PreparedItem prepared);

    DeliveryResult deliver(PlayerInventoryStore store, ItemAsset asset, DeliveryPreference.ItemDestination destination);
}
