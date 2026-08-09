package com.pokeemc.thirdparty;

import com.poketrade.api.PokeTradeApi;
import com.poketrade.api.capability.CapabilityEntry;
import com.poketrade.api.capability.CapabilityProbe;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link CapabilityProbe} 实现：聚合保护/经济/容器三注册表，输出能力清单；
 * 未适配模组判定委托 {@link ThirdPartyProbe}（按注入的已加载模组集合）。
 */
public final class CapabilityProbeImpl implements CapabilityProbe {

    private final ProtectionRegistryImpl protection;
    private final EconomyRegistryImpl economy;
    private final StorageAdapterRegistryImpl storage;
    private final ThirdPartyProbe probe;
    private final Set<String> loadedModIds;

    public CapabilityProbeImpl(ProtectionRegistryImpl protection,
                               EconomyRegistryImpl economy,
                               StorageAdapterRegistryImpl storage,
                               Set<String> loadedModIds) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.probe = new ThirdPartyProbe(protection, economy, storage);
        this.loadedModIds = Set.copyOf(loadedModIds);
    }

    @Override
    public int apiVersion() {
        return PokeTradeApi.API_VERSION;
    }

    @Override
    public List<CapabilityEntry> protectionProviders() {
        return protection.providers().stream()
                .map(p -> new CapabilityEntry(p.modId(), p.getClass().getName(), true))
                .toList();
    }

    @Override
    public List<CapabilityEntry> economyBackends() {
        return economy.backends().stream()
                .map(b -> new CapabilityEntry(b.backendId(), b.getClass().getName(),
                        economy.activeBackend().filter(active -> active == b).isPresent()))
                .toList();
    }

    @Override
    public List<CapabilityEntry> storageAdapters() {
        return storage.typeIds().stream().sorted()
                .map(typeId -> new CapabilityEntry(typeId,
                        storage.byTypeId(typeId).orElseThrow().getClass().getName(), true))
                .toList();
    }

    @Override
    public List<String> unadaptedMods() {
        return probe.unadaptedMods(loadedModIds);
    }
}
