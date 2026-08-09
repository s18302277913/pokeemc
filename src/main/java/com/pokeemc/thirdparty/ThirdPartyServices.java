package com.pokeemc.thirdparty;

import com.pokeemc.PokeEMC;
import com.pokeemc.economy.PixelmonWallet;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.thirdparty.integration.StorageProtectionHook;
import com.pokeemc.thirdparty.integration.WalletBridge;
import com.poketrade.api.PokeTradeApi;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 阶段 6 进程级装配器：第三方适配 SPI（保护/经济/容器）注册表、保护链钩子、
 * 经济桥与能力探测的单一入口。
 *
 * <p>生产路径 {@link #init()} 在 {@link PokeEMC} 构造时调用（幂等）；测试与宿主
 * 注入使用 {@link #init(ProtectionRegistryImpl, EconomyRegistryImpl,
 * StorageAdapterRegistryImpl)} 直接给定注册表。装配完成后通过
 * {@link PokeTradeApiImpl#INSTANCE} 安装 {@link PokeTradeApi}。</p>
 */
public final class ThirdPartyServices {

    private static volatile ProtectionRegistryImpl protectionRegistry;
    private static volatile EconomyRegistryImpl economyRegistry;
    private static volatile StorageAdapterRegistryImpl storageRegistry;
    private static volatile ThirdPartyProbe probe;
    private static volatile CapabilityProbeImpl capabilityProbe;
    private static volatile StorageProtectionHook protectionHook;
    private static volatile WalletBridge walletBridge;

    private ThirdPartyServices() {
    }

    /** 生产装配：注册内置 Pixelmon 经济兜底并聚合 {@link StorageServices#registry()}。幂等。 */
    public static synchronized void init() {
        if (protectionRegistry != null) {
            return;
        }
        ProtectionRegistryImpl protection = new ProtectionRegistryImpl();
        EconomyRegistryImpl economy = new EconomyRegistryImpl();
        DefaultEconomyBackend pixelmon = new DefaultEconomyBackend(PixelmonWallet.port());
        economy.register(pixelmon);
        install(protection, economy, StorageServices.registry(), loadedThirdPartyModIds());
    }

    /** 注入装配（测试/宿主）：使用给定注册表，不探测 ModList。 */
    public static synchronized void init(ProtectionRegistryImpl protection,
                                         EconomyRegistryImpl economy,
                                         StorageAdapterRegistryImpl storage) {
        install(protection, economy, storage, Set.of());
    }

    /** 保护链接入点：未装配时恒放行（纯 JVM 测试与极端时序不抛异常）。 */
    public static StorageProtectionHook protectionHook() {
        StorageProtectionHook hook = protectionHook;
        return hook != null ? hook : StorageProtectionHook.unloaded();
    }

    public static ProtectionRegistryImpl protectionRegistry() {
        ensureInitialized();
        return protectionRegistry;
    }

    public static EconomyRegistryImpl economyRegistry() {
        ensureInitialized();
        return economyRegistry;
    }

    public static StorageAdapterRegistryImpl storageRegistry() {
        ensureInitialized();
        return storageRegistry;
    }

    public static CapabilityProbeImpl capabilityProbe() {
        ensureInitialized();
        return capabilityProbe;
    }

    /** 经济桥：生产装配后供交易链路获取 {@code WalletPort}。 */
    public static WalletBridge walletBridge() {
        ensureInitialized();
        return walletBridge;
    }

    /** 服务端启动完成后：探测已加载第三方并输出 warn/info 告警。 */
    public static void onServerStarted(MinecraftServer server) {
        ensureInitialized();
        probe.onServerStarted();
    }

    /** 测试用：清空装配并卸载 {@link PokeTradeApi}。 */
    public static synchronized void reset() {
        protectionRegistry = null;
        economyRegistry = null;
        storageRegistry = null;
        probe = null;
        capabilityProbe = null;
        protectionHook = null;
        walletBridge = null;
        PokeTradeApi.set(null);
    }

    private static void install(ProtectionRegistryImpl protection,
                                EconomyRegistryImpl economy,
                                StorageAdapterRegistryImpl storage,
                                Set<String> loadedModIds) {
        protectionRegistry = protection;
        economyRegistry = economy;
        storageRegistry = storage;
        probe = new ThirdPartyProbe(protection, economy, storage);
        capabilityProbe = new CapabilityProbeImpl(protection, economy, storage, loadedModIds);
        protectionHook = new StorageProtectionHook(protection);
        walletBridge = new WalletBridge(economy, new DefaultEconomyBackend(PixelmonWallet.port()));
        PokeTradeApiImpl.INSTANCE.install(protection, economy, capabilityProbe);
        PokeEMC.LOGGER.info("[thirdparty] assembled: {} providers, {} backends, {} adapters",
                protection.size(), economy.backends().size(), storage.size());
    }

    /** 已加载模组 id 集合（FML 环境）；纯 JVM 测试返回空集。 */
    private static Set<String> loadedThirdPartyModIds() {
        ModList modList = ModList.get();
        if (modList == null) {
            return Set.of();
        }
        return modList.getMods().stream()
                .map(modInfo -> modInfo.getModId())
                .collect(Collectors.toUnmodifiableSet());
    }

    private static void ensureInitialized() {
        if (protectionRegistry == null) {
            throw new IllegalStateException(
                    "third-party services not initialized; call ThirdPartyServices.init()");
        }
    }
}
