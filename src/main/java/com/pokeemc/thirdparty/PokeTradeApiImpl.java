package com.pokeemc.thirdparty;

import com.poketrade.api.PokeTradeApi;
import com.poketrade.api.capability.CapabilityProbe;
import com.poketrade.api.economy.EconomyRegistry;
import com.poketrade.api.permission.ProtectionRegistry;

import java.util.Objects;

/**
 * {@link PokeTradeApi} 根模组实现：由 {@link ThirdPartyServices#init()} 装配后
 * 通过 {@link #INSTANCE} 安装为服务定位器实现。
 */
public final class PokeTradeApiImpl extends PokeTradeApi {

    public static final PokeTradeApiImpl INSTANCE = new PokeTradeApiImpl();

    private volatile ProtectionRegistry protectionRegistry;
    private volatile EconomyRegistry economyRegistry;
    private volatile CapabilityProbe capabilityProbe;

    private PokeTradeApiImpl() {
    }

    /** 由 ThirdPartyServices 装配时调用并安装到 PokeTradeApi。 */
    void install(ProtectionRegistry protection, EconomyRegistry economy, CapabilityProbe probe) {
        this.protectionRegistry = Objects.requireNonNull(protection, "protection");
        this.economyRegistry = Objects.requireNonNull(economy, "economy");
        this.capabilityProbe = Objects.requireNonNull(probe, "probe");
        PokeTradeApi.set(this);
    }

    @Override
    public ProtectionRegistry protectionRegistry() {
        return require(protectionRegistry);
    }

    @Override
    public EconomyRegistry economyRegistry() {
        return require(economyRegistry);
    }

    @Override
    public CapabilityProbe capabilityProbe() {
        return require(capabilityProbe);
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new IllegalStateException(
                    "PokeTradeApi not installed; call ThirdPartyServices.init() first");
        }
        return value;
    }
}
