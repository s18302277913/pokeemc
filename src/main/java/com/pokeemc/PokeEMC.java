package com.pokeemc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.pokeemc.blockentity.TransmutationTableBlockEntity;
import com.pokeemc.client.PokeEMCClient;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.exchange.price.ExchangeConfigLoader;
import com.pokeemc.exchange.price.OfficialPriceLoader;
import com.pokeemc.exchange.price.PriceOverrides;
import com.pokeemc.emc.PKMManager;
import com.pokeemc.emc.PkmDataLoader;
import com.pokeemc.emc.PkmRecipeCalculator;
import com.pokeemc.registry.ModBlockEntities;
import com.pokeemc.registry.ModBlocks;
import com.pokeemc.registry.ModCreativeTabs;
import com.pokeemc.registry.ModItems;
import com.pokeemc.registry.ModMenuTypes;
import com.pokeemc.storage.StorageAutomationGuard;
import com.pokeemc.storage.StorageCommands;
import com.pokeemc.storage.StorageProtectionEvents;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.thirdparty.CapabilityCommand;
import com.pokeemc.thirdparty.ThirdPartyServices;
import com.pokeemc.trade.command.TradeCommand;
import com.pokeemc.trade.service.TradeProduction;
import com.pokeemc.trade.service.TradeRuntime;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(PokeEMC.MODID)
public class PokeEMC {
    public static final String MODID = "poketrade";
    public static final String LEGACY_MODID = "pokeemc";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 交易过期扫描间隔（tick），取自服务端配置 {@code trade.sweepIntervalTicks} */
    private static int tradeSweepIntervalTicks() {
        return PokeTradeConfig.sweepIntervalTicks();
    }

    private int tradeSweepTicks;

    public PokeEMC(IEventBus modEventBus) {
        // 服务端配置（config/poketrade-server.toml，随存档 serverconfig 持久化）
        ModLoadingContext.get().getActiveContainer()
                .registerConfig(ModConfig.Type.SERVER, PokeTradeConfig.SPEC);

        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModMenuTypes.register(modEventBus);
        ModCreativeTabs.register(modEventBus);

        // 仓储适配器注册表与服务装配（幂等，注册 5 个内置适配器）
        StorageServices.init();
        // 阶段 6：第三方适配 SPI 装配（保护/经济/容器注册表 + PokeTradeApi 安装）
        ThirdPartyServices.init();

        modEventBus.addListener(this::commonSetup);

        // 游戏事件（服务端）：数据包监听 + 配方计算
        NeoForge.EVENT_BUS.addListener(this::addReloadListeners);
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);

        // 仓储认领与容器变化（Task 6）：放置认领/双箱冲突/标脏 + 服务端 tick
        StorageProtectionEvents protection =
                new StorageProtectionEvents(StorageServices.registry(), StorageServices.discovery());
        NeoForge.EVENT_BUS.addListener(protection::onPlace);
        NeoForge.EVENT_BUS.addListener(protection::onBreak);
        NeoForge.EVENT_BUS.addListener(protection::onServerTick);

        // Task 11 保护：爆炸不移除有主仓储、活塞推/拉有主仓储默认拒绝
        NeoForge.EVENT_BUS.addListener(protection::onExplosionDetonate);
        NeoForge.EVENT_BUS.addListener(protection::onPistonPre);

        // Task 12 命令、审计与管理员恢复（/poketrade storage）
        NeoForge.EVENT_BUS.addListener(StorageCommands::register);

        // 阶段 6：能力探测命令（/poketrade capability）
        NeoForge.EVENT_BUS.addListener(CapabilityCommand::register);

        // Task 9/10：玩家交易命令入口（/poketrade trade ...）
        NeoForge.EVENT_BUS.addListener(TradeCommand::register);

        // Task 7 交易崩溃恢复：启动恢复 + 登录交付 + 定时过期扫描（实例由 Task 11 注入）
        NeoForge.EVENT_BUS.addListener(this::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(this::onServerTickTradeSweep);

        // Task 11 自动化守卫：mod bus HIGHEST 接管内置容器 capability，
        // 游戏 tick 冲刷自动化审计聚合计数
        modEventBus.addListener(EventPriority.HIGHEST, StorageAutomationGuard::registerCapabilities);
        NeoForge.EVENT_BUS.addListener(StorageAutomationGuard::onServerTick);

        // 客户端初始化
        if (FMLEnvironment.dist == Dist.CLIENT) {
            PokeEMCClient.init();
        }
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(PKMManager::init);

        // 交易所覆盖价：内置默认（大师球 500 万）在数据包重载前即可用
        try (var stream = PokeEMC.class.getResourceAsStream("/data/poketrade/exchange/prices.json")) {
            if (stream != null) {
                JsonElement el = JsonParser.parseReader(
                        new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
                PriceOverrides.applyBuiltIn(el);
            }
        } catch (Exception e) {
            PokeEMC.LOGGER.error("PokeEMC: failed to load exchange override prices", e);
        }

        LOGGER.info("PokeEMC loaded. Pixelmon mod present: {}",
                ModList.get().isLoaded("pixelmon"));
    }

    /** 服务端数据包重载监听：加载 data/pokeemc/pkm/*.json */
    private void addReloadListeners(final AddReloadListenerEvent event) {
        event.addListener(PkmDataLoader.INSTANCE);

        // 交易所官方双价同步（服务端数据包监听）
        event.addListener(OfficialPriceLoader.INSTANCE);

        // 交易所覆盖价与出售规则（服务端数据包监听）
        event.addListener(ExchangeConfigLoader.INSTANCE);
    }

    /** 服务端启动完成后，基于完整配方自动计算补充 PKM 值 + 交易崩溃恢复 */
    private void onServerStarted(final ServerStartedEvent event) {
        event.getServer().overworld().getServer().execute(() -> {
            try {
                // Task 11 步骤 3：生产装配（TradeRuntime.install），必须先于恢复器启动
                TradeProduction.install(event.getServer());
                // 阶段 6：探测已加载第三方模组并输出 warn/info（未适配降级告警）
                ThirdPartyServices.onServerStarted(event.getServer());
                PkmRecipeCalculator.computeAll(event.getServer().overworld());
            } catch (Exception e) {
                LOGGER.error("PokeEMC: trade production install failed", e);
            }
        });
        event.getServer().execute(TradeRuntime::recoverAllOnStartup);
    }

    /** 玩家登录：延后一 tick 尝试交付收件箱（交易恢复） */
    private void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        var player = event.getEntity();
        player.getServer().execute(() ->
                TradeRuntime.deliverOnLogin(player.getUUID()));
    }

    /** 服务端 tick：按配置间隔扫描一次过期交易（内部按批处理限流） */
    private void onServerTickTradeSweep(final ServerTickEvent.Post event) {
        int interval = tradeSweepIntervalTicks();
        if (interval <= 0) {
            return;
        }
        if (++tradeSweepTicks >= interval) {
            tradeSweepTicks = 0;
            TradeRuntime.sweepExpired();
        }
    }
}
