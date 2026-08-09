package com.pokeemc.trade.model;

import java.util.UUID;

/**
 * 可交易资产（计划 2.3）。三类资产统一带稳定 {@code assetId} 与原所有者 UUID；
 * 同一资产在同一时刻只能归属玩家存储、一个交易托管或一个结算收件箱。
 */
public sealed interface TradeAsset permits ItemAsset, PkmAsset, PokemonAsset {

    /** 资产稳定标识（NBT 持久化后不变） */
    UUID assetId();

    /** 资产原所有者（报价方），用于取消/恢复时的归还 */
    UUID originalOwner();

    /** 资产种类名（ITEM/PKM/POKEMON），用于锁顺序与日志 */
    String kind();
}
