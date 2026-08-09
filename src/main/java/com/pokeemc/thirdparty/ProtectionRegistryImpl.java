package com.pokeemc.thirdparty;

import com.mojang.logging.LogUtils;
import com.poketrade.api.PokeTradeApi;
import com.poketrade.api.permission.ProtectionProvider;
import com.poketrade.api.permission.ProtectionRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ProtectionRegistry} 实现：保持注册顺序；重复 modId 或 API 版本
 * 不一致时拒绝注册（记录 error）。
 */
public final class ProtectionRegistryImpl implements ProtectionRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, ProtectionProvider> byModId = new LinkedHashMap<>();

    @Override
    public synchronized void register(ProtectionProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String modId = provider.modId();
        if (modId == null || modId.isBlank()) {
            throw new IllegalArgumentException("provider modId must be non-blank");
        }
        if (provider.apiVersion() != PokeTradeApi.API_VERSION) {
            LOGGER.error("[thirdparty] reject protection provider {}: apiVersion {} != {}",
                    modId, provider.apiVersion(), PokeTradeApi.API_VERSION);
            throw new IllegalArgumentException(
                    "protection provider " + modId + " apiVersion mismatch");
        }
        if (byModId.containsKey(modId)) {
            LOGGER.error("[thirdparty] duplicate protection provider modId: {}", modId);
            throw new IllegalArgumentException("Duplicate protection provider modId: " + modId);
        }
        byModId.put(modId, provider);
        LOGGER.info("[thirdparty] registered protection provider {} ({})",
                modId, provider.getClass().getName());
    }

    @Override
    public synchronized List<ProtectionProvider> providers() {
        return new ArrayList<>(byModId.values());
    }

    @Override
    public synchronized Optional<ProtectionProvider> byModId(String modId) {
        return Optional.ofNullable(byModId.get(modId));
    }

    /** 注册数量（测试与探测用）。 */
    public synchronized int size() {
        return byModId.size();
    }
}
