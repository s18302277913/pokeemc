package com.pokeemc.thirdparty;

import com.mojang.logging.LogUtils;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 启动探测：已知第三方模组清单 + 注册适配判定 + 服务端启动告警。
 *
 * <p>纯判定（{@link #isAdapted} / {@link #unadaptedMods}）不依赖 FML，可在 JVM
 * 单测驱动；{@link #onServerStarted} 为生产路径，从 {@link ModList} 探测已加载
 * 模组并输出 warn/info。</p>
 */
public final class ThirdPartyProbe {

    /** 已知第三方模组（可配置）；顺序即告警/报告顺序。 */
    private static final List<String> KNOWN_ORDER = List.of(
            "griefdefense", "worldguard", "lockettepro", "vault", "ironchest");

    public static final Set<String> KNOWN_MOD_IDS = new LinkedHashSet<>(KNOWN_ORDER);

    private static final Set<String> PROTECTION_MOD_IDS =
            Set.of("griefdefense", "worldguard", "lockettepro");
    private static final Set<String> ECONOMY_MOD_IDS = Set.of("vault");
    private static final Set<String> CONTAINER_MOD_IDS = Set.of("ironchest");

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ProtectionRegistryImpl protection;
    private final EconomyRegistryImpl economy;
    private final StorageAdapterRegistryImpl storage;

    public ThirdPartyProbe(ProtectionRegistryImpl protection,
                           EconomyRegistryImpl economy,
                           StorageAdapterRegistryImpl storage) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.economy = Objects.requireNonNull(economy, "economy");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** 该 modId 是否已有对应适配（protection → provider；economy → backend；container → adapter）。 */
    public boolean isAdapted(String modId) {
        if (PROTECTION_MOD_IDS.contains(modId)) {
            return protection.byModId(modId).isPresent();
        }
        if (ECONOMY_MOD_IDS.contains(modId)) {
            return economy.backends().stream().anyMatch(b -> b.backendId().equals(modId));
        }
        if (CONTAINER_MOD_IDS.contains(modId)) {
            return storage.isRegistered(modId);
        }
        return false;
    }

    /** 已加载但未适配的已知第三方（按 KNOWN_ORDER 稳定顺序）。 */
    public List<String> unadaptedMods(Set<String> loadedModIds) {
        List<String> result = new ArrayList<>();
        for (String modId : KNOWN_ORDER) {
            if (loadedModIds.contains(modId) && !isAdapted(modId)) {
                result.add(modId);
            }
        }
        return result;
    }

    /** 服务端启动完成后告警：未适配 warn、已适配 info。 */
    public void onServerStarted() {
        List<String> loaded = loadedModIds();
        for (String modId : unadaptedMods(Set.copyOf(loaded))) {
            LOGGER.warn("[thirdparty] {} 已加载但未注册适配器，相关功能降级为内置行为", modId);
        }
        for (String modId : loaded) {
            if (isAdapted(modId)) {
                LOGGER.info("[thirdparty] {} 已注册适配器", modId);
            }
        }
    }

    private static List<String> loadedModIds() {
        ModList modList = ModList.get();
        if (modList == null) {
            return List.of();
        }
        return modList.getMods().stream().map(m -> m.getModId()).toList();
    }
}
