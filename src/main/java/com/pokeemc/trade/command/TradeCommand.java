package com.pokeemc.trade.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.pokeemc.trade.asset.PokemonSummaryReader;
import com.pokeemc.trade.menu.PlayerTradeMenu;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.service.PokemonLocator;
import com.pokeemc.trade.service.TradeResult;
import com.pokeemc.trade.service.TradeRuntime;
import com.pokeemc.trade.service.TradeService;
import com.pokeemc.trade.service.TradeSnapshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * 玩家交易命令（计划 2.1 / Task 10）。
 * <p>
 * 命令前缀 {@code /poketrade}（与 StorageCommands 一致），所有写操作直接调用
 * {@link TradeService}，不复制任何验证与资产操作；结果通过
 * {@link TradeError#translationKey()} 的翻译键展示，成功提示通过
 * {@code command.poketrade.trade.<verb>.ok} 翻译键展示。
 * <p>
 * {@code admin} 子命令要求权限等级 3。{@code open} 打开 Task 9 图形界面；
 * 其余子命令服务端主线程执行，等价于图形界面的 C2S 操作。
 */
public final class TradeCommand {

    private TradeCommand() {
    }

    /** 注册 {@code /poketrade trade ...} 命令树（在 PokeEMC 构造中挂到 NeoForge.EVENT_BUS）。 */
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("poketrade")
                        .then(Commands.literal("trade")
                                // /poketrade trade open —— 打开玩家交易界面（Task 9）
                                .then(Commands.literal("open")
                                        .executes(ctx -> cmdOpen(ctx.getSource())))
                                // /poketrade trade status —— 查看当前交易状态（双方报价/revision/确认/超时）
                                .then(Commands.literal("status")
                                        .executes(ctx -> cmdStatus(ctx.getSource())))
                                // /poketrade trade invite <player>
                                .then(Commands.literal("invite")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .executes(ctx -> cmdInvite(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player")))))
                                // /poketrade trade accept
                                .then(Commands.literal("accept")
                                        .executes(ctx -> cmdAccept(ctx.getSource())))
                                // /poketrade trade cancel
                                .then(Commands.literal("cancel")
                                        .executes(ctx -> cmdCancel(ctx.getSource())))
                                // /poketrade trade confirm
                                .then(Commands.literal("confirm")
                                        .executes(ctx -> cmdConfirm(ctx.getSource())))
                                // /poketrade trade claim —— 领取收件箱
                                .then(Commands.literal("claim")
                                        .executes(ctx -> cmdClaim(ctx.getSource())))
                                // /poketrade trade pref <itemDest> <pkmDest>
                                //   itemDest: auto|inventory|inbox
                                //   pkmDest : auto|party|pc|inbox
                                .then(Commands.literal("pref")
                                        .then(Commands.argument("itemDest", StringArgumentType.word())
                                                .then(Commands.argument("pkmDest", StringArgumentType.word())
                                                        .executes(ctx -> cmdPref(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "itemDest"),
                                                                StringArgumentType.getString(ctx, "pkmDest"))))))
                                // /poketrade trade offer item <slot> <count>
                                .then(Commands.literal("offer")
                                        .then(Commands.literal("item")
                                                .then(Commands.argument("slot",
                                                                IntegerArgumentType.integer(0, com.pokeemc.trade.network.TradePacketLimits.MAX_INVENTORY_SLOT))
                                                        .then(Commands.argument("count",
                                                                        IntegerArgumentType.integer(1, com.pokeemc.trade.network.TradePacketLimits.MAX_ITEM_COUNT))
                                                                .executes(ctx -> cmdOfferItem(ctx.getSource(),
                                                                        IntegerArgumentType.getInteger(ctx, "slot"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"))))))
                                        // /poketrade trade offer pkm <amount>
                                        .then(Commands.literal("pkm")
                                                .then(Commands.argument("amount",
                                                                LongArgumentType.longArg(1, com.pokeemc.trade.network.TradePacketLimits.MAX_PKM_AMOUNT))
                                                        .executes(ctx -> cmdOfferPkm(ctx.getSource(),
                                                                LongArgumentType.getLong(ctx, "amount")))))
                                        // /poketrade trade offer pokemon <party|pc> <box> <slot>
                                        .then(Commands.literal("pokemon")
                                                .then(Commands.argument("kind", StringArgumentType.word())
                                                        .then(Commands.argument("box",
                                                                                IntegerArgumentType.integer(-1, com.pokeemc.trade.network.TradePacketLimits.MAX_PC_BOX))
                                                                .then(Commands.argument("slot",
                                                                                IntegerArgumentType.integer(0, com.pokeemc.trade.network.TradePacketLimits.MAX_PC_SLOT))
                                                                        .executes(ctx -> cmdOfferPokemon(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "kind"),
                                                                                IntegerArgumentType.getInteger(ctx, "box"),
                                                                                IntegerArgumentType.getInteger(ctx, "slot")))))))
                                        // /poketrade trade offer remove <assetId>
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("assetId", StringArgumentType.string())
                                                        .executes(ctx -> cmdOfferRemove(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "assetId"))))))
                                // /poketrade trade admin status <player>   (权限 3)
                                .then(Commands.literal("admin")
                                        .requires(s -> s.hasPermission(3))
                                        .then(Commands.literal("status")
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(ctx -> cmdAdminStatus(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "player")))))
                                        // /poketrade trade admin cancel <player>   (权限 3)
                                        .then(Commands.literal("cancel")
                                                .then(Commands.argument("player", StringArgumentType.word())
                                                        .executes(ctx -> cmdAdminCancel(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "player"))))))));
    }

    // ------------------------------------------------------------------ 命令体

    private static int cmdOpen(CommandSourceStack source) {
        try {
            ServerPlayer player = source.getPlayerOrException();
            PlayerTradeMenu.open(player);
            sendOk(source, "command.poketrade.trade.open");
            return 1;
        } catch (Exception e) {
            sendFail(source, e.getMessage());
            return 0;
        }
    }

    private static int cmdStatus(CommandSourceStack source) {
        return withPlayerAndService(source, (player, svc) -> {
            Optional<TradeSnapshot> snap = svc.snapshot(player.getUUID());
            if (snap.isEmpty()) {
                sendFailKey(source, "command.poketrade.trade.no_active");
                return 0;
            }
            source.sendSuccess(() -> Component.literal(formatStatus(snap.get(), player.getUUID())), false);
            return 1;
        });
    }

    private static int cmdInvite(CommandSourceStack source, String targetName) {
        return withPlayerAndService(source, (player, svc) -> {
            ServerPlayer target = player.getServer().getPlayerList().getPlayerByName(targetName);
            if (target == null) {
                sendFailKey(source, TradeError.TARGET_OFFLINE.translationKey());
                return 0;
            }
            TradeResult result = svc.invite(player.getUUID(), target.getUUID());
            if (result.success()) {
                sendOk(source, "command.poketrade.trade.invite.ok", targetName);
                return 1;
            }
            sendFailKey(source, result.error().translationKey());
            return 0;
        });
    }

    private static int cmdAccept(CommandSourceStack source) {
        return withPlayerAndService(source, (player, svc) ->
                actOnCurrent(source, player, svc, "command.poketrade.trade.accept.ok",
                        (s, t, rev) -> svc.accept(player.getUUID(), t, rev)));
    }

    private static int cmdCancel(CommandSourceStack source) {
        return withPlayerAndService(source, (player, svc) ->
                actOnCurrent(source, player, svc, "command.poketrade.trade.cancel.ok",
                        (s, t, rev) -> svc.cancel(player.getUUID(), t, rev)));
    }

    private static int cmdConfirm(CommandSourceStack source) {
        return withPlayerAndService(source, (player, svc) ->
                actOnCurrent(source, player, svc, "command.poketrade.trade.confirm.ok",
                        (s, t, rev) -> svc.confirm(player.getUUID(), t, rev)));
    }

    private static int cmdClaim(CommandSourceStack source) {
        return withPlayerAndService(source, (player, svc) -> {
            TradeResult result = svc.claim(player.getUUID());
            if (result.success()) {
                sendOk(source, "command.poketrade.trade.claim.ok");
                return 1;
            }
            sendFailKey(source, result.error().translationKey());
            return 0;
        });
    }

    private static int cmdPref(CommandSourceStack source, String itemDest, String pkmDest) {
        return withPlayerAndService(source, (player, svc) -> {
            DeliveryPreference pref = parsePreference(itemDest, pkmDest);
            if (pref == null) {
                sendFailKey(source, TradeError.INVALID_INPUT.translationKey());
                return 0;
            }
            return actOnCurrent(source, player, svc, "command.poketrade.trade.pref.ok",
                    (s, t, rev) -> svc.setDeliveryPreference(player.getUUID(), t, rev, pref));
        });
    }

    private static int cmdOfferItem(CommandSourceStack source, int slot, int count) {
        return withPlayerAndService(source, (player, svc) ->
                actOnCurrent(source, player, svc, "command.poketrade.trade.offer.ok",
                        (s, t, rev) -> svc.offerItem(player.getUUID(), t, rev, slot, count)));
    }

    private static int cmdOfferPkm(CommandSourceStack source, long amount) {
        return withPlayerAndService(source, (player, svc) ->
                actOnCurrent(source, player, svc, "command.poketrade.trade.offer.ok",
                        (s, t, rev) -> svc.offerPkm(player.getUUID(), t, rev, amount)));
    }

    private static int cmdOfferPokemon(CommandSourceStack source, String kind, int box, int slot) {
        return withPlayerAndService(source, (player, svc) -> {
            PokemonLocator locator;
            try {
                if (kind.equals("party")) {
                    if (box != -1 || slot > 5) {
                        sendFailKey(source, TradeError.INVALID_INPUT.translationKey());
                        return 0;
                    }
                    locator = PokemonLocator.party(slot);
                } else if (kind.equals("pc")) {
                    locator = PokemonLocator.pc(box, slot);
                } else {
                    sendFailKey(source, TradeError.INVALID_INPUT.translationKey());
                    return 0;
                }
            } catch (IllegalArgumentException e) {
                sendFailKey(source, TradeError.INVALID_INPUT.translationKey());
                return 0;
            }
            return actOnCurrent(source, player, svc, "command.poketrade.trade.offer.ok",
                    (s, t, rev) -> svc.offerPokemon(player.getUUID(), t, rev, locator));
        });
    }

    private static int cmdOfferRemove(CommandSourceStack source, String assetIdText) {
        return withPlayerAndService(source, (player, svc) -> {
            UUID assetId;
            try {
                assetId = UUID.fromString(assetIdText);
            } catch (IllegalArgumentException e) {
                sendFailKey(source, TradeError.INVALID_INPUT.translationKey());
                return 0;
            }
            return actOnCurrent(source, player, svc, "command.poketrade.trade.remove.ok",
                    (s, t, rev) -> svc.removeAsset(player.getUUID(), t, rev, assetId));
        });
    }

    private static int cmdAdminStatus(CommandSourceStack source, String targetName) {
        return withPlayerAndService(source, (player, svc) -> {
            ServerPlayer target = player.getServer().getPlayerList().getPlayerByName(targetName);
            if (target == null) {
                sendFailKey(source, TradeError.TARGET_OFFLINE.translationKey());
                return 0;
            }
            Optional<TradeSnapshot> snap = svc.snapshot(target.getUUID());
            if (snap.isEmpty()) {
                sendFailKey(source, "command.poketrade.trade.no_active");
                return 0;
            }
            source.sendSuccess(() -> Component.literal(formatStatus(snap.get(), target.getUUID())), false);
            return 1;
        });
    }

    private static int cmdAdminCancel(CommandSourceStack source, String targetName) {
        return withPlayerAndService(source, (player, svc) -> {
            ServerPlayer target = player.getServer().getPlayerList().getPlayerByName(targetName);
            if (target == null) {
                sendFailKey(source, TradeError.TARGET_OFFLINE.translationKey());
                return 0;
            }
            Optional<TradeSnapshot> snap = svc.snapshot(target.getUUID());
            if (snap.isEmpty()) {
                sendFailKey(source, "command.poketrade.trade.no_active");
                return 0;
            }
            TradeResult result = svc.cancel(target.getUUID(), snap.get().tradeId(), snap.get().revision());
            if (result.success()) {
                sendOk(source, "command.poketrade.trade.cancel.ok");
                return 1;
            }
            sendFailKey(source, result.error().translationKey());
            return 0;
        });
    }

    // ------------------------------------------------------------------ 辅助

    /** 对当前玩家的活动交易执行一个写操作；无活动交易时报错返回。 */
    private interface TradeAction {
        TradeResult run(TradeSnapshot snap, TradeId tradeId, long revision);
    }

    private static int actOnCurrent(CommandSourceStack source, ServerPlayer player, TradeService svc,
                                    String okKey, TradeAction action) {
        Optional<TradeSnapshot> snap = svc.snapshot(player.getUUID());
        if (snap.isEmpty()) {
            sendFailKey(source, "command.poketrade.trade.no_active");
            return 0;
        }
        TradeResult result = action.run(snap.get(), snap.get().tradeId(), snap.get().revision());
        if (result.success()) {
            sendOk(source, okKey);
            return 1;
        }
        sendFailKey(source, result.error().translationKey());
        return 0;
    }

    /** 取执行者与交易服务；服务未装配（Task 11 之前）时给出可读提示。 */
    private interface PlayerServiceAction {
        int run(ServerPlayer player, TradeService svc);
    }

    private static int withPlayerAndService(CommandSourceStack source, PlayerServiceAction action) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception e) {
            sendFail(source, e.getMessage());
            return 0;
        }
        TradeService svc = TradeRuntime.service();
        if (svc == null) {
            sendFailKey(source, "command.poketrade.trade.unavailable");
            return 0;
        }
        return action.run(player, svc);
    }

    /** 解析收货偏好参数；非法返回 null。 */
    private static DeliveryPreference parsePreference(String itemDest, String pkmDest) {
        DeliveryPreference.ItemDestination id;
        try {
            id = DeliveryPreference.ItemDestination.valueOf(itemDest.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        DeliveryPreference.PokemonDestination pd;
        try {
            pd = DeliveryPreference.PokemonDestination.valueOf(pkmDest.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new DeliveryPreference(id, pd);
    }

    /** 格式化当前交易状态：双方报价、revision、确认状态、超时、偏好与费用。 */
    private static String formatStatus(TradeSnapshot s, UUID selfId) {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        sb.append("=== 玩家交易状态 ===");
        sb.append("\n交易ID: ").append(s.tradeId().value());
        sb.append("  revision: ").append(s.revision());
        sb.append("  状态: ").append(s.status().name());
        if (s.status().cancellable()) {
            long remain = s.expiresAtEpochMillis() - now;
            sb.append("  剩余时间: ").append(Math.max(0, remain / 1000)).append(" 秒");
        }
        sb.append("\n我方确认: ").append(s.selfConfirmed() ? "是" : "否")
                .append("  对方确认: ").append(s.otherConfirmed() ? "是" : "否");
        sb.append("\n—— 我方报价 ——\n").append(formatOffer(s.selfOffer()));
        sb.append("—— 对方报价 ——\n").append(formatOffer(s.otherOffer()));
        if (s.selfPreference() != null) {
            sb.append("收货偏好: 物品=").append(s.selfPreference().itemDestination().name())
                    .append(" 宝可梦=").append(s.selfPreference().pokemonDestination().name());
        }
        if (s.feeQuote() != null) {
            sb.append("\n手续费: PKM ").append(s.feeQuote().leftPkmFee()).append(" / ")
                    .append(s.feeQuote().rightPkmFee())
                    .append(" (策略 ").append(s.feeQuote().policyId()).append(" v")
                    .append(s.feeQuote().policyVersion()).append(")");
            if (s.feeQuote().expired(now)) {
                sb.append(" [已过期]");
            }
        }
        sb.append("\n提示: 报价变化会撤销双方确认；宝可梦交易不会触发交换进化。");
        return sb.toString();
    }

    /** 报价展示行：物品(itemId x count)、PKM 总额、宝可梦摘要（species/等级/闪光/昵称）。 */
    private static String formatOffer(TradeOffer offer) {
        StringBuilder sb = new StringBuilder();
        if (offer == null) {
            return "  (无报价)\n";
        }
        for (ItemAsset ia : offer.items()) {
            String id = ia.stackNbt() == null ? "" : ia.stackNbt().getString("id");
            int count = ia.stackNbt() != null && ia.stackNbt().contains("Count", net.minecraft.nbt.Tag.TAG_BYTE)
                    ? ia.stackNbt().getByte("Count") : 1;
            sb.append("  物品: ").append(id.isBlank() ? ia.assetId() : id).append(" x ").append(Math.max(1, count)).append('\n');
        }
        if (offer.totalPkm() > 0) {
            sb.append("  PKM: ").append(offer.totalPkm()).append('\n');
        }
        for (PokemonAsset pa : offer.pokemon()) {
            String species = PokemonSummaryReader.species(pa.pokemonNbt());
            int level = PokemonSummaryReader.level(pa.pokemonNbt());
            boolean shiny = PokemonSummaryReader.shiny(pa.pokemonNbt());
            String nickname = PokemonSummaryReader.nickname(pa.pokemonNbt());
            sb.append("  宝可梦: ").append(species).append(" Lv.").append(level)
                    .append(shiny ? " [闪光]" : "")
                    .append(nickname.isBlank() ? "" : " \"" + nickname + "\"")
                    .append('\n');
        }
        if (offer.items().isEmpty() && offer.totalPkm() == 0 && offer.pokemon().isEmpty()) {
            sb.append("  (空)\n");
        }
        return sb.toString();
    }

    private static void sendOk(CommandSourceStack source, String key, Object... args) {
        source.sendSuccess(() -> Component.translatable(key, args), false);
    }

    private static void sendFailKey(CommandSourceStack source, String key) {
        source.sendFailure(Component.translatable(key));
    }

    private static void sendFail(CommandSourceStack source, String text) {
        source.sendFailure(Component.literal(text));
    }
}
