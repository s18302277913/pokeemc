package com.poketrade.api;

import com.poketrade.api.capability.CapabilityProbe;
import com.poketrade.api.economy.EconomyRegistry;
import com.poketrade.api.permission.ProtectionRegistry;

/**
 * 服务端能力入口：第三方模组通过 {@link #get()} 取得注册表与探测入口。
 *
 * <p>{@link #get()} 在客户端或根模组尚未装配时返回 {@code null}，调用方必须
 * 自行判空。根模组在服务端装配完成后通过 {@link #set(PokeTradeApi)} 安装
 * 实现（传 {@code null} 表示卸载）。</p>
 */
public abstract class PokeTradeApi {

    public static final int API_VERSION = 1;

    private static volatile PokeTradeApi instance;

    protected PokeTradeApi() {
    }

    /** 服务端可用时返回实现；客户端/未初始化返回 null。 */
    public static PokeTradeApi get() {
        return instance;
    }

    /** 安装服务端实现（仅根模组调用）；传 null 表示卸载。 */
    public static void set(PokeTradeApi api) {
        instance = api;
    }

    /** 保护 Provider 注册表。 */
    public abstract ProtectionRegistry protectionRegistry();

    /** 经济后端注册表。 */
    public abstract EconomyRegistry economyRegistry();

    /** 能力探测入口。 */
    public abstract CapabilityProbe capabilityProbe();
}
