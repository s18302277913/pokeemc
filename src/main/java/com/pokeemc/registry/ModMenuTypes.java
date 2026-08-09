package com.pokeemc.registry;

import com.pokeemc.PokeEMC;
import com.pokeemc.id.ModIdAliases;
import com.pokeemc.menu.CondenserMenu;
import com.pokeemc.menu.ExchangeMenu;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.menu.TransmutationTableMenu;
import com.pokeemc.trade.menu.PlayerTradeMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, PokeEMC.MODID);

    public static final DeferredHolder<MenuType<?>, MenuType<TransmutationTableMenu>> TRANSMUTATION_TABLE =
            MENUS.register("transmutation_table",
                    () -> new MenuType<>(TransmutationTableMenu::new, FeatureFlags.VANILLA_SET));

    public static final DeferredHolder<MenuType<?>, MenuType<CondenserMenu>> CONDENSER =
            MENUS.register("condenser",
                    () -> new MenuType<>(CondenserMenu::new, FeatureFlags.VANILLA_SET));

    /** Task 9：独立仓储浏览器菜单（无绑定方块，纯远程视图）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<StorageBrowserMenu.Standalone>> STORAGE_BROWSER =
            MENUS.register("storage_browser",
                    () -> new MenuType<>(StorageBrowserMenu.Standalone::new, FeatureFlags.VANILLA_SET));

    /** Task 8：宝可梦交易所菜单（三栏 UI：仓储 / 目录 / 购物车）。旧存档无此菜单，无需别名。 */
    public static final DeferredHolder<MenuType<?>, MenuType<ExchangeMenu>> EXCHANGE =
            MENUS.register("exchange",
                    () -> new MenuType<>(ExchangeMenu::new, FeatureFlags.VANILLA_SET));

    /** Task 9：玩家交易菜单（无绑定方块、无业务槽位，纯远程视图）。 */
    public static final DeferredHolder<MenuType<?>, MenuType<PlayerTradeMenu>> PLAYER_TRADE =
            MENUS.register("player_trade",
                    () -> new MenuType<>(PlayerTradeMenu::new, FeatureFlags.VANILLA_SET));

    public static void register(IEventBus bus) {
        ModIdAliases.menuAliases().forEach((legacy, current) ->
                MENUS.addAlias(ResourceLocation.parse(legacy), ResourceLocation.parse(current)));
        MENUS.register(bus);
    }
}
