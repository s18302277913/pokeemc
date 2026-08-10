package com.pokeemc.exchange.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.config.PokeTradeConfig.ExchangeMode;
import com.pokeemc.exchange.price.ExchangePriceService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Locale;

/**
 * [NEW] 会话 #21-H：交易所目录模式切换指令（{@code /poketrade exchange mode [<learning|full>]}）。
 *
 * <p>学习模式（默认）= 目录仅显示可出售物品（有卖价且通过出售规则黑/白名单）；全高亮模式 =
 * 显示目录全部有价条目（含仅可买入）。全局服务端生效：写 {@code PokeTradeConfig} 的
 * {@code exchange.exchangeMode}（持久化到 serverconfig），并通知开着的交易所屏重新拉取。
 * 全局设置故需 operator 2 权限。</p>
 */
public final class ExchangeModeCommand {

    private ExchangeModeCommand() {
    }

    /** 注册 {@code /poketrade exchange mode ...} 命令树（在 PokeEMC 构造中挂到 NeoForge.EVENT_BUS）。 */
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("poketrade")
                        .then(Commands.literal("exchange")
                                .requires(s -> s.hasPermission(2)) // 全局服务端设置 → operator 2
                                .then(Commands.literal("mode")
                                        // /poketrade exchange mode —— 查询当前模式
                                        .executes(ctx -> cmdQuery(ctx.getSource()))
                                        // /poketrade exchange mode <learning|full> —— 切换模式
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> cmdSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "mode")))))));
    }

    /** 解析模式名（learning/full，大小写不敏感）；非法抛 {@link IllegalArgumentException}。 */
    static ExchangeMode parseMode(String raw) throws IllegalArgumentException {
        return switch (raw == null ? "" : raw.toLowerCase(Locale.ROOT)) {
            case "learning" -> ExchangeMode.LEARNING;
            case "full" -> ExchangeMode.FULL;
            default -> throw new IllegalArgumentException("invalid mode: " + raw);
        };
    }

    /**
     * 可测核心：解析 → 写配置 → 仅当模式真正变化时通知所有在线玩家重拉目录。
     * 返回解析后的模式（供测试与反馈文案复用）。
     */
    static ExchangeMode applyMode(String raw, ExchangePriceService service) throws IllegalArgumentException {
        ExchangeMode mode = parseMode(raw);
        ExchangeMode before = PokeTradeConfig.exchangeMode();
        PokeTradeConfig.setExchangeMode(mode);
        if (before != mode) {
            service.notifyCatalogChangedToPlayers();
        }
        return mode;
    }

    private static int cmdQuery(CommandSourceStack source) {
        ExchangeMode cur = PokeTradeConfig.exchangeMode();
        source.sendSuccess(() -> Component.translatable("command.poketrade.exchange.mode.current",
                Component.translatable(modeKey(cur))), false);
        return 1;
    }

    private static int cmdSet(CommandSourceStack source, String raw) {
        try {
            ExchangeMode mode = applyMode(raw, ExchangePriceService.forServer());
            source.sendSuccess(() -> Component.translatable("command.poketrade.exchange.mode.set",
                    Component.translatable(modeKey(mode))), true);
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.translatable(
                    "command.poketrade.exchange.mode.invalid", raw));
            return 0;
        }
    }

    private static String modeKey(ExchangeMode m) {
        return "poketrade.exchange.mode." + m.name().toLowerCase(Locale.ROOT);
    }
}
