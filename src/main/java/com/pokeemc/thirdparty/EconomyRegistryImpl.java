package com.pokeemc.thirdparty;

import com.mojang.logging.LogUtils;
import com.poketrade.api.PokeTradeApi;
import com.poketrade.api.economy.EconomyBackend;
import com.poketrade.api.economy.EconomyRegistry;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link EconomyRegistry} 实现：首个注册的后端为 active；重复 backendId
 * 或 API 版本不一致时拒绝注册。
 */
public final class EconomyRegistryImpl implements EconomyRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, EconomyBackend> byId = new LinkedHashMap<>();

    @Override
    public synchronized void register(EconomyBackend backend) {
        Objects.requireNonNull(backend, "backend");
        String backendId = backend.backendId();
        if (backendId == null || backendId.isBlank()) {
            throw new IllegalArgumentException("backend backendId must be non-blank");
        }
        if (backend.apiVersion() != PokeTradeApi.API_VERSION) {
            LOGGER.error("[thirdparty] reject economy backend {}: apiVersion {} != {}",
                    backendId, backend.apiVersion(), PokeTradeApi.API_VERSION);
            throw new IllegalArgumentException(
                    "economy backend " + backendId + " apiVersion mismatch");
        }
        if (byId.containsKey(backendId)) {
            LOGGER.error("[thirdparty] duplicate economy backend id: {}", backendId);
            throw new IllegalArgumentException("Duplicate economy backend id: " + backendId);
        }
        byId.put(backendId, backend);
        LOGGER.info("[thirdparty] registered economy backend {} ({})",
                backendId, backend.getClass().getName());
    }

    @Override
    public synchronized Optional<EconomyBackend> activeBackend() {
        return byId.values().stream().findFirst();
    }

    /** 按注册顺序返回全部后端（探测聚合与测试用）。 */
    public synchronized List<EconomyBackend> backends() {
        return new ArrayList<>(byId.values());
    }
}
