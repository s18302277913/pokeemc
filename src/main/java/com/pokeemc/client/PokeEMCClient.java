package com.pokeemc.client;

import com.pokeemc.PokeEMC;
import com.pokeemc.emc.PkmDataLoader;
import com.pokeemc.emc.PkmRecipeCalculator;
import com.pokeemc.menu.TransmutationTableMenu;
import com.pokeemc.registry.ModMenuTypes;
import com.pokeemc.trade.client.PlayerTradeScreen;
import com.pokeemc.trade.client.TradeClientState;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = PokeEMC.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class PokeEMCClient {

    @SubscribeEvent
    public static void registerScreens(final RegisterMenuScreensEvent event) {
        event.register(ModMenuTypes.TRANSMUTATION_TABLE.get(), TransmutationTableScreen::new);
        event.register(ModMenuTypes.CONDENSER.get(), CondenserScreen::new);
        event.register(ModMenuTypes.STORAGE_BROWSER.get(), StorageBrowserScreen::new);
        event.register(ModMenuTypes.EXCHANGE.get(), ExchangeScreen::new);
        // Task 9：玩家交易界面（三态视图：目录 / 工作台 / 资产）
        event.register(ModMenuTypes.PLAYER_TRADE.get(), PlayerTradeScreen::new);
        PokeEMC.LOGGER.info("PokeEMC: registered transmutation table & condenser & storage browser & exchange & player trade screens");
    }

    @SubscribeEvent
    public static void registerReloadListeners(final RegisterClientReloadListenersEvent event) {
        // 客户端数据包同样加载 PKM 定价，保证 tooltip 与转化桌列表可显示
        event.registerReloadListener(PkmDataLoader.INSTANCE);
    }

    public static void init() {
        NeoForge.EVENT_BUS.register(new TooltipEvents());
        NeoForge.EVENT_BUS.addListener(PokeEMCClient::onClientJoin);
        // Task 9：退出世界/切换服务器时清空交易缓存，避免跨服务器泄漏目录、资产页与快照
        NeoForge.EVENT_BUS.addListener(PokeEMCClient::onClientLogout);
    }

    /** 客户端登录服务端后，基于同步的配方补算 PKM（保证 tooltip/列表显示完整） */
    private static void onClientJoin(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingIn event) {
        try {
            PkmRecipeCalculator.computeAll(Minecraft.getInstance().level);
        } catch (Exception e) {
            PokeEMC.LOGGER.error("PokeEMC: client recipe compute failed", e);
        }
    }

    /** 客户端退出世界/服务器：清空玩家交易客户端缓存（计划 5.2：退出世界清空目录、资产和交易快照缓存） */
    private static void onClientLogout(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        TradeClientState.INSTANCE.clear();
    }
}
