package com.pokeemc.storage;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import com.pokeemc.storage.adapter.VanillaEnderChestAdapter;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageQuery;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 12 命令、审计与管理员恢复（含 Task 10 的模板管理命令部分）。
 *
 * <p>命令根：{@code /poketrade storage}，子命令：info / claim / grant / deny /
 * revoke / transfer / unclaim / template / automation / audit / scan / repair。</p>
 *
 * <p><b>权限模型</b>：非管理员只能操作<b>已加载、同维度、水平 8 格内且拥有
 * MANAGE</b> 的仓储（owner 恒有全权限；管理员绕过距离/维度限制，但跨维度
 * 必须显式给出维度参数，不默认主世界）。transfer / unclaim 仅所有者或管理员。</p>
 *
 * <p><b>审计脱敏</b>：审计明细在服务端完整保存（UUID 用于追踪），命令输出时
 * 对 UUID 脱敏——主体 UUID 只显示前 8 位；detail 中嵌入的 UUID 模式同样替换；
 * 玩家名可完整显示。禁止在审计 detail 中写入物品 NBT / 聊天内容 / IP / 令牌
 * （{@link StorageAuditEntry} 只做 256 字符机械截断）。</p>
 *
 * <p>[REMOVED] <b>SavedData 热替换</b>（缺陷 #7）：旧实现通过
 * {@code encode → NBT 修改 → decode → DimensionDataStorage.set} 替换存档实例实现
 * unclaim / repair / rename，会替换实例并使其他服务持有的引用失效。重做后改调
 * {@link StorageSavedData#deleteStorage} / {@link StorageSavedData#rebuildChunkIndex} /
 * {@link StorageSavedData#renameTemplate} 等直接公开 API，绕路机制已删除。</p>
 */
public final class StorageCommands {

    public static final int MANAGE_RANGE_BLOCKS = 8;
    public static final int AUDIT_DEFAULT_COUNT = 20;
    public static final int AUDIT_MAX_COUNT = 100;
    public static final int SCAN_DEFAULT_RADIUS = 32;
    public static final int SCAN_MAX_RADIUS = 256;

    // [REMOVED] StorageSavedData 序列化键名（KEY_STORAGES 等）：缺陷 #7 移除
    // encode→decode 热替换后已无使用者，序列化格式由 StorageSavedData 独占维护。

    // 参数名
    private static final String ARG_POS = "pos";
    private static final String ARG_DIM = "dimension";
    private static final String ARG_PLAYER = "player";
    private static final String ARG_PERMS = "permissions";
    private static final String ARG_COUNT = "count";
    private static final String ARG_RADIUS = "radius";
    private static final String ARG_ID = "id";
    private static final String ARG_NAME = "name";
    private static final String ARG_SRC_ID = "srcId";
    private static final String ARG_NEW_ID = "newId";
    private static final String ARG_NEW_NAME = "newName";
    private static final String ARG_PRINCIPAL = "principal";
    private static final String ARG_SCOPE = "scope";
    private static final String ARG_ON = "on";

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static volatile StorageProtectionEvents protectionEvents;

    private StorageCommands() {
    }

    // ---------------------------------------------------------------- 注册

    /**
     * 注册 /poketrade storage 命令树（在 PokeEMC 构造中挂到 NeoForge.EVENT_BUS）。
     */
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(
                Commands.literal("poketrade")
                        .then(Commands.literal("storage")
                                .then(Commands.literal("info").then(posArgs(
                                        (ctx, c, key) -> executeInfo(c, key))))
                                .then(Commands.literal("claim").then(posArgs(
                                        (ctx, c, key) -> executeClaim(c, key, c.actorName()))))
                                .then(Commands.literal("grant")
                                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                                .then(Commands.argument(ARG_PERMS, StringArgumentType.string())
                                                        .then(posArgs((ctx, c, key) -> {
                                                            StoragePrincipal p =
                                                                    parsePrincipal(c.server(), arg(ctx, ARG_PLAYER));
                                                            return executeGrant(c, key, p,
                                                                    parsePermissions(arg(ctx, ARG_PERMS)));
                                                        })))))
                                .then(Commands.literal("deny")
                                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                                .then(Commands.argument(ARG_PERMS, StringArgumentType.string())
                                                        .then(posArgs((ctx, c, key) -> {
                                                            StoragePrincipal p =
                                                                    parsePrincipal(c.server(), arg(ctx, ARG_PLAYER));
                                                            return executeDeny(c, key, p,
                                                                    parsePermissions(arg(ctx, ARG_PERMS)));
                                                        })))))
                                .then(Commands.literal("revoke")
                                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                                .then(posArgs((ctx, c, key) -> executeRevoke(c, key,
                                                        parsePrincipal(c.server(), arg(ctx, ARG_PLAYER)))))))
                                .then(Commands.literal("transfer")
                                        .then(Commands.argument(ARG_PLAYER, StringArgumentType.word())
                                                .then(posArgs((ctx, c, key) -> {
                                                    UUID target = resolvePlayerUuid(c.server(), arg(ctx, ARG_PLAYER));
                                                    return executeTransfer(c, key, target,
                                                            resolvePlayerName(c.server(), target));
                                                }))))
                                .then(Commands.literal("unclaim").then(posArgs(
                                        (ctx, c, key) -> executeUnclaim(c, key))))
                                .then(Commands.literal("template")
                                        .then(Commands.literal("list")
                                                .executes(c -> runSimple(c, cc -> executeTemplateList(cc))))
                                        .then(Commands.literal("create")
                                                .then(Commands.argument(ARG_SCOPE, StringArgumentType.word())
                                                        .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                                .then(Commands.argument(ARG_NAME, StringArgumentType.word())
                                                                        .then(Commands.argument(ARG_PRINCIPAL, StringArgumentType.word())
                                                                                .then(Commands.argument(ARG_PERMS, StringArgumentType.string())
                                                                                        .executes(c -> runSimple(c, cc -> executeTemplateCreate(cc,
                                                                                                parseScope(arg(c, ARG_SCOPE)),
                                                                                                arg(c, ARG_ID), arg(c, ARG_NAME),
                                                                                                parsePrincipal(cc.server(), arg(c, ARG_PRINCIPAL)),
                                                                                                parsePermissions(arg(c, ARG_PERMS))))))))))
                                        .then(Commands.literal("grant")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .then(Commands.argument(ARG_PRINCIPAL, StringArgumentType.word())
                                                                .then(Commands.argument(ARG_PERMS, StringArgumentType.string())
                                                                        .executes(c -> runSimple(c, cc -> executeTemplateGrant(cc,
                                                                                arg(c, ARG_ID),
                                                                                parsePrincipal(cc.server(), arg(c, ARG_PRINCIPAL)),
                                                                                parsePermissions(arg(c, ARG_PERMS)))))))))
                                        .then(Commands.literal("deny")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .then(Commands.argument(ARG_PRINCIPAL, StringArgumentType.word())
                                                                .then(Commands.argument(ARG_PERMS, StringArgumentType.string())
                                                                        .executes(c -> runSimple(c, cc -> executeTemplateDeny(cc,
                                                                                arg(c, ARG_ID),
                                                                                parsePrincipal(cc.server(), arg(c, ARG_PRINCIPAL)),
                                                                                parsePermissions(arg(c, ARG_PERMS)))))))))
                                        .then(Commands.literal("unset")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .then(Commands.argument(ARG_PRINCIPAL, StringArgumentType.word())
                                                                .executes(c -> runSimple(c, cc -> executeTemplateUnset(cc,
                                                                        arg(c, ARG_ID),
                                                                        parsePrincipal(cc.server(), arg(c, ARG_PRINCIPAL))))))))
                                        .then(Commands.literal("rename")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .then(Commands.argument(ARG_NEW_NAME, StringArgumentType.word())
                                                                .executes(c -> runSimple(c, cc -> executeTemplateRename(cc,
                                                                        arg(c, ARG_ID), arg(c, ARG_NEW_NAME)))))))
                                        .then(Commands.literal("copy")
                                                .then(Commands.argument(ARG_SRC_ID, StringArgumentType.word())
                                                        .then(Commands.argument(ARG_NEW_ID, StringArgumentType.word())
                                                                .then(Commands.argument(ARG_NEW_NAME, StringArgumentType.word())
                                                                        .executes(c -> runSimple(c, cc -> executeTemplateCopy(cc,
                                                                                arg(c, ARG_SRC_ID),
                                                                                arg(c, ARG_NEW_ID), arg(c, ARG_NEW_NAME))))))))
                                        .then(Commands.literal("delete")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .executes(c -> runSimple(c, cc -> executeTemplateDelete(cc,
                                                                arg(c, ARG_ID))))))
                                        .then(Commands.literal("info")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .executes(c -> runSimple(c, cc -> executeTemplateInfo(cc,
                                                                arg(c, ARG_ID))))))
                                        .then(Commands.literal("apply")
                                                .then(Commands.argument(ARG_ID, StringArgumentType.word())
                                                        .then(Commands.literal("copy")
                                                                .then(posArgs((ctx, c, key) -> executeTemplateApply(c, arg(ctx, ARG_ID),
                                                                        StorageRecord.TemplateMode.COPY, key))))
                                                        .then(Commands.literal("follow")
                                                                .then(posArgs((ctx, c, key) -> executeTemplateApply(c, arg(ctx, ARG_ID),
                                                                        StorageRecord.TemplateMode.FOLLOW, key)))))))
                                .then(Commands.literal("automation")
                                        .then(Commands.literal("get").then(posArgs(
                                                (ctx, c, key) -> executeAutomationGet(c, key))))
                                        .then(Commands.literal("set")
                                                .then(Commands.literal("insert")
                                                        .then(Commands.argument(ARG_ON, BoolArgumentType.bool())
                                                                .then(posArgs((ctx, c, key) -> executeAutomationSet(c, key,
                                                                        true, bool(ctx))))))
                                                .then(Commands.literal("extract")
                                                        .then(Commands.argument(ARG_ON, BoolArgumentType.bool())
                                                                .then(posArgs((ctx, c, key) -> executeAutomationSet(c, key,
                                                                        false, bool(ctx))))))))
                                .then(Commands.literal("audit")
                                        .then(Commands.argument(ARG_COUNT, IntegerArgumentType.integer(1, AUDIT_MAX_COUNT))
                                                .executes(c -> runSimple(c, cc -> join(
                                                        executeAudit(cc, intArg(c, ARG_COUNT), null))))
                                                .then(posArgs((ctx, cc, key) -> join(
                                                        executeAudit(cc, intArg(ctx, ARG_COUNT), key))))
                                        )
                                        .executes(c -> runSimple(c, cc -> join(
                                                executeAudit(cc, AUDIT_DEFAULT_COUNT, null))))
                                        .then(posArgs((ctx, cc, key) -> join(
                                                executeAudit(cc, AUDIT_DEFAULT_COUNT, key)))))
                                .then(Commands.literal("scan")
                                        .then(Commands.argument(ARG_RADIUS, IntegerArgumentType.integer(1, SCAN_MAX_RADIUS))
                                                .executes(c -> runSimple(c, cc -> executeScan(cc, intArg(c, ARG_RADIUS)))))
                                        .executes(c -> runSimple(c, cc -> executeScan(cc, SCAN_DEFAULT_RADIUS))))
                                .then(Commands.literal("repair")
                                        .executes(c -> runSimple(c, StorageCommands::executeRepair))))));
    }

    // ---------------------------------------------------------------- 命令上下文

    /**
     * 命令执行上下文：把玩家身份/位置/权限显式传入，核心逻辑不依赖在线玩家，
     * GameTest 可以直接构造。
     *
     * @param server   服务器
     * @param level    目标维度（管理员显式指定维度时为目标维度；非管理员恒为玩家当前维度）
     * @param actorId  操作者 UUID
     * @param actorName 操作者显示名
     * @param admin    是否为管理员（命令层取 {@code source.hasPermission(2)}）
     * @param actorPos 操作者位置（用于 8 格距离判定；测试可传任意值）
     * @param data     仓储存档（overworld 数据存储）
     */
    public record CmdCtx(
            MinecraftServer server,
            ServerLevel level,
            UUID actorId,
            String actorName,
            boolean admin,
            BlockPos actorPos) {

        /**
         * 现取的 StorageSavedData 实例。
         *
         * <p>[CHANGED] 缺陷 #7 修复后不再有热替换，unclaim/rename/repair 均通过直接
         * API 原地变更同一实例；此处仍每次现取，保证与存档最新状态一致。</p>
         */
        public StorageSavedData data() {
            return savedData(server);
        }

        /** 从命令源构造（在线玩家）。 */
        public static CmdCtx online(CommandSourceStack source, ServerLevel level)
                throws CommandSyntaxException {
            ServerPlayer player = source.getPlayerOrException();
            return new CmdCtx(source.getServer(), level, player.getUUID(),
                    player.getName().getString(), source.hasPermission(2),
                    player.blockPosition());
        }

        /** 测试构造：显式身份/权限（GameTest 无在线玩家）。 */
        public static CmdCtx of(MinecraftServer server, ServerLevel level, UUID actorId,
                                String actorName, boolean admin, BlockPos actorPos) {
            return new CmdCtx(server, level, actorId, actorName, admin, actorPos);
        }
    }

    /** 命令/核心逻辑错误：中文反馈，由命令层转成失败消息，测试中直接抛出。 */
    public static final class CmdError extends RuntimeException {
        public CmdError(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------- 位置参数块

    /** 位置参数块：{@code <x> <y> <z> [dimension]}，解析后执行动作。 */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> posArgs(StorageAction action) {
        return Commands.argument(ARG_POS, BlockPosArgument.blockPos())
                .executes(c -> runPos(c, null, action))
                .then(Commands.argument(ARG_DIM, DimensionArgument.dimension())
                        .executes(c -> runPos(c, dimArg(c, ARG_DIM), action)));
    }

    @FunctionalInterface
    private interface StorageAction {
        String run(CommandContext<CommandSourceStack> ctx, CmdCtx c, StorageKey key) throws CmdError;
    }

    private static int runPos(CommandContext<CommandSourceStack> ctx, String dimensionArg,
                              StorageAction action) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            boolean admin = source.hasPermission(2);
            String actorDim = player.level().dimension().location().toString();
            String dimStr = dimensionArg != null ? dimensionArg : actorDim;
            // 管理员跨维度操作必须显式给出维度参数，不默认主世界
            if (dimensionArg != null && !admin && !dimStr.equals(actorDim)) {
                throw new CmdError("跨维度操作需要管理员权限（请进入目标维度，或作为管理员显式指定维度）");
            }
            ServerLevel level = resolveLevel(source.getServer(), dimStr);
            if (level == null) {
                throw new CmdError("维度不存在或未加载: " + dimStr);
            }
            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, level, ARG_POS);
            CmdCtx c = CmdCtx.online(source, level);
            String message = action.run(ctx, c, resolveKeyAt(level, pos));
            source.sendSuccess(() -> Component.literal(message), true);
            return 1;
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal(e.getRawMessage().getString()));
            return 0;
        } catch (CmdError e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("命令执行失败: " + e.getMessage()));
            return 0;
        }
    }

    /** 无位置参数的命令（template 管理 / audit / scan / repair）。 */
    private static int runSimple(CommandContext<CommandSourceStack> ctx,
                                 SimpleAction action) {
        CommandSourceStack source = ctx.getSource();
        try {
            ServerPlayer player = source.getPlayerOrException();
            CmdCtx c = new CmdCtx(source.getServer(), player.serverLevel(), player.getUUID(),
                    player.getName().getString(), source.hasPermission(2),
                    player.blockPosition());
            String message = action.run(c);
            source.sendSuccess(() -> Component.literal(message), true);
            return 1;
        } catch (CommandSyntaxException e) {
            source.sendFailure(Component.literal(e.getRawMessage().getString()));
            return 0;
        } catch (CmdError e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        } catch (RuntimeException e) {
            source.sendFailure(Component.literal("命令执行失败: " + e.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface SimpleAction {
        String run(CmdCtx ctx) throws CmdError;
    }

    // ---------------------------------------------------------------- 核心逻辑

    /**
     * 从方块位置解析规范仓储键：遍历注册的适配器，用 supports + canonicalize
     * 得到与认领/发现一致的 StorageKey（双箱自动归一为主半区）。
     */
    public static StorageKey resolveKeyAt(ServerLevel level, BlockPos pos) throws CmdError {
        String dim = level.dimension().location().toString();
        String location = AbstractContainerAdapter.toLocation(pos);
        for (String typeId : StorageServices.registry().typeIds()) {
            StorageAdapter adapter = StorageServices.registry().byTypeId(typeId).orElse(null);
            if (adapter == null) {
                continue;
            }
            try {
                StorageId candidate = new StorageId(dim, typeId, location);
                if (adapter.supports(new StorageAdapterContext(candidate))) {
                    return StorageServices.registry().canonicalize(
                            new StorageKey(dim, typeId, location));
                }
            } catch (RuntimeException ignored) {
                // 该适配器不匹配此位置（例如单箱适配器遇到双箱半区），尝试下一个
            }
        }
        throw new CmdError("该位置没有受支持的仓储容器（需要在已加载区块内指向容器方块）");
    }

    /** 解析逗号分隔的权限列表；不提供 read/write 等模糊别名。 */
    public static EnumSet<StoragePermission> parsePermissions(String csv) throws CmdError {
        EnumSet<StoragePermission> set = EnumSet.noneOf(StoragePermission.class);
        for (String token : csv.split(",")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            StoragePermission p = switch (t) {
                case "view" -> StoragePermission.VIEW;
                case "deposit" -> StoragePermission.DEPOSIT;
                case "withdraw" -> StoragePermission.WITHDRAW;
                case "sell" -> StoragePermission.SELL;
                case "break" -> StoragePermission.BREAK;
                case "manage" -> StoragePermission.MANAGE;
                default -> throw new CmdError("无效权限: " + token
                        + "（可选: view,deposit,withdraw,sell,break,manage，不接受 read/write 等别名）");
            };
            set.add(p);
        }
        if (set.isEmpty()) {
            throw new CmdError("至少需要一个权限");
        }
        return set;
    }

    /** 解析主体：玩家名（在线优先，离线走 profile 缓存）或 "public"。 */
    public static StoragePrincipal parsePrincipal(MinecraftServer server, String name)
            throws CmdError {
        if ("public".equalsIgnoreCase(name) || "*".equals(name)) {
            return new StoragePrincipal.Public();
        }
        return new StoragePrincipal.Player(resolvePlayerUuid(server, name));
    }

    /** UUID 脱敏：只保留前 8 位。 */
    public static String maskUuid(UUID uuid) {
        return uuid == null ? "?" : uuid.toString().substring(0, 8) + "…";
    }

    /** info：显示仓储信息（非管理员需 MANAGE）。 */
    public static String executeInfo(CmdCtx ctx, StorageKey key) throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        StringBuilder sb = new StringBuilder();
        sb.append("显示名: ").append(record.displayName())
                .append("\n所有者: ").append(record.ownerName())
                .append("\n维度: ").append(key.dimension())
                .append("\n适配器: ").append(key.adapterType())
                .append("\n位置: ").append(key.location());
        if (record.templateBinding() != null) {
            sb.append("\n模板: ").append(record.templateBinding())
                    .append(" (").append(record.templateMode().name().toLowerCase(Locale.ROOT)).append(")");
        } else {
            sb.append("\n模板: 无");
        }
        sb.append("\n自动化插入: ").append(record.automationInsertEnabled() ? "开" : "关")
                .append("；自动化抽取: ").append(record.automationExtractEnabled() ? "开" : "关")
                .append("\n浏览器可见: ").append(record.listedInBrowser() ? "是" : "否")
                .append("\nrevision: ").append(record.revision());
        if (record.grants().isEmpty()) {
            sb.append("\n授权: （空）");
        } else {
            sb.append("\n授权:");
            for (Map.Entry<StoragePrincipal, StorageGrant> e : record.grants().entrySet()) {
                sb.append("\n  ").append(principalLabel(ctx, e.getKey()))
                        .append(" 允许[").append(permNames(e.getValue().allow().values())).append("]")
                        .append(" 拒绝[").append(permNames(e.getValue().deny().values())).append("]");
            }
        }
        return sb.toString();
    }

    /** claim：认领无主仓储（非管理员需 8 格内同维度已加载；任何人不得越权认领他人仓储）。 */
    public static String executeClaim(CmdCtx ctx, StorageKey key, String ownerName)
            throws CmdError {
        BlockPos pos = AbstractContainerAdapter.parsePos(key.location());
        if (pos == null) {
            throw new CmdError("无法解析仓储位置: " + key.location());
        }
        requireLoadedNear(ctx, key, pos);
        if (ctx.data().getRecord(key).isPresent()) {
            throw new CmdError("该仓储已被认领，无需重复认领");
        }
        StorageProtectionEvents.ClaimResult result = protection().claim(ctx.level(), pos, ctx.actorId(), ownerName);
        return switch (result) {
            case CLAIMED -> "认领成功，现在你拥有该仓储（" + key.location() + "）";
            case MIGRATED -> "认领成功（双箱记录已迁移为规范键）";
            case ALREADY_CLAIMED -> "该仓储已被认领";
            case CONFLICT -> throw new CmdError("认领冲突：该容器与已认领仓储相邻/合并，不能认领");
            case NOT_SUPPORTED -> throw new CmdError("该容器不受支持，无法认领");
        };
    }

    /** grant：把权限加入目标仓储 ACL（allow 加入，同时移除对应显式拒绝）。 */
    public static String executeGrant(CmdCtx ctx, StorageKey key, StoragePrincipal principal,
                                      EnumSet<StoragePermission> perms) throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        StorageGrant base = record.grants().getOrDefault(principal, StorageGrant.NONE);
        StorageGrant next = StorageGrant.of(
                plus(base.allow(), perms),
                minus(base.deny(), perms));
        if (!ctx.data().applyGrant(key, record.revision(), principal, next)) {
            throw new CmdError("授权失败（revision 冲突，仓储可能已变更）");
        }
        auditHighRisk(ctx, key, principal, perms);
        audit(ctx, key, "grant", principalLabel(ctx, principal) + " += " + permNames(perms));
        return "已授权 " + permNames(perms) + " 给 " + principalLabel(ctx, principal);
    }

    /** deny：把权限从该主体的可用集合中移除（allow 移除 + deny 置位，覆盖模板/PUBLIC allow）。 */
    public static String executeDeny(CmdCtx ctx, StorageKey key, StoragePrincipal principal,
                                     EnumSet<StoragePermission> perms) throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        StorageGrant base = record.grants().getOrDefault(principal, StorageGrant.NONE);
        StorageGrant next = StorageGrant.of(
                minus(base.allow(), perms),
                plus(base.deny(), perms));
        if (!ctx.data().applyGrant(key, record.revision(), principal, next)) {
            throw new CmdError("拒绝授权失败（revision 冲突，仓储可能已变更）");
        }
        audit(ctx, key, "deny", principalLabel(ctx, principal) + " -= " + permNames(perms));
        return "已拒绝 " + principalLabel(ctx, principal) + " 的 " + permNames(perms)
                + "（显式拒绝将覆盖模板/PUBLIC 授权）";
    }

    /** revoke：移除某主体的全部授权条目。 */
    public static String executeRevoke(CmdCtx ctx, StorageKey key, StoragePrincipal principal)
            throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        if (!ctx.data().removeGrant(key, record.revision(), principal)) {
            throw new CmdError("撤销失败（revision 冲突，仓储可能已变更）");
        }
        audit(ctx, key, "revoke", "revoked " + principalLabel(ctx, principal));
        return "已撤销 " + principalLabel(ctx, principal) + " 的全部授权";
    }

    /** transfer：转移所有权（仅所有者或管理员）。 */
    public static String executeTransfer(CmdCtx ctx, StorageKey key, UUID newOwnerId,
                                         String newOwnerName) throws CmdError {
        StorageRecord record = requireOwnerOrAdmin(ctx, key);
        StorageRecord next = new StorageRecord(
                newOwnerId, newOwnerName, record.displayName(),
                record.grants(), record.templateBinding(), record.templateMode(),
                record.automationInsertEnabled(), record.automationExtractEnabled(),
                record.listedInBrowser(), record.createdAtEpochMillis(),
                record.updatedAtEpochMillis(), record.revision());
        if (!ctx.data().updateRecord(key, record.revision(), r -> next)) {
            throw new CmdError("转移失败（revision 冲突，仓储可能已变更）");
        }
        audit(ctx, key, "transfer",
                "from " + maskUuid(record.ownerId()) + " (" + record.ownerName() + ") to " + newOwnerName);
        return "已把仓储所有权转移给 " + newOwnerName;
    }

    /**
     * unclaim：取消认领（仅所有者或管理员）。
     *
     * <p>[CHANGED] 缺陷 #7 修复：改为直接调用 {@link StorageSavedData#deleteStorage}，
     * 不再经 encode→decode 热替换绕路（旧实现会替换 SavedData 实例并使其他服务
     * 持有的引用失效）。deleteStorage 同时从区块索引移除并置脏。</p>
     */
    public static String executeUnclaim(CmdCtx ctx, StorageKey key) throws CmdError {
        StorageRecord record = requireOwnerOrAdmin(ctx, key);
        if (!ctx.data().deleteStorage(key)) {
            throw new CmdError("仓储不存在或已被取消认领");
        }
        ctx.data().appendAudit(System.currentTimeMillis(), key.asString(), ctx.actorId(),
                "unclaim", "owner " + record.ownerName() + " (" + maskUuid(record.ownerId()) + ")");
        return "已取消认领 " + key.location() + "（原所有者 " + record.ownerName() + "）";
    }

    // ---------------------------------------------------------------- 模板命令

    /** 创建模板；server 作用域仅管理员，玩家作用域归创建者所有。 */
    public static String executeTemplateCreate(CmdCtx ctx, StorageTemplate.Scope scope,
                                               String id, String name, StoragePrincipal principal,
                                               EnumSet<StoragePermission> perms) throws CmdError {
        if (scope == StorageTemplate.Scope.SERVER && !ctx.admin()) {
            throw new CmdError("仅管理员可创建服务器模板");
        }
        validateTemplateName(name);
        Map<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        grants.put(principal, StorageGrant.allow(perms.toArray(new StoragePermission[0])));
        StorageTemplate template = StorageTemplate.create(id, scope,
                scope == StorageTemplate.Scope.PLAYER ? ctx.actorId() : null,
                name, grants, System.currentTimeMillis());
        try {
            ctx.data().createTemplate(template);
        } catch (IllegalArgumentException e) {
            throw new CmdError(e.getMessage());
        }
        auditHighRisk(ctx, null, principal, perms);
        audit(ctx, null, "template_create",
                (scope == StorageTemplate.Scope.SERVER ? "server " : "") + id + " (" + name + ")");
        return "已创建模板 " + id + "（" + name + "）";
    }

    /** 模板授权：为主体加入 allow 权限。 */
    public static String executeTemplateGrant(CmdCtx ctx, String id, StoragePrincipal principal,
                                              EnumSet<StoragePermission> perms) throws CmdError {
        StorageTemplate template = requireTemplate(ctx, id, true);
        Map<StoragePrincipal, StorageGrant> next = new LinkedHashMap<>(template.grants());
        StorageGrant base = next.getOrDefault(principal, StorageGrant.NONE);
        next.put(principal, StorageGrant.of(plus(base.allow(), perms), base.deny()));
        if (!ctx.data().updateTemplate(id, template.revision(), next)) {
            throw new CmdError("模板已变更，请重试");
        }
        auditHighRisk(ctx, null, principal, perms);
        audit(ctx, null, "template_grant", id + " " + principalLabel(ctx, principal)
                + " += " + permNames(perms));
        return "模板 " + id + " 已授权 " + permNames(perms) + " 给 "
                + principalLabel(ctx, principal);
    }

    /** 模板拒绝：为主体加入 deny 权限（覆盖模板的 PUBLIC/其他 allow）。 */
    public static String executeTemplateDeny(CmdCtx ctx, String id, StoragePrincipal principal,
                                             EnumSet<StoragePermission> perms) throws CmdError {
        StorageTemplate template = requireTemplate(ctx, id, true);
        Map<StoragePrincipal, StorageGrant> next = new LinkedHashMap<>(template.grants());
        StorageGrant base = next.getOrDefault(principal, StorageGrant.NONE);
        next.put(principal, StorageGrant.of(base.allow(), plus(base.deny(), perms)));
        if (!ctx.data().updateTemplate(id, template.revision(), next)) {
            throw new CmdError("模板已变更，请重试");
        }
        audit(ctx, null, "template_deny", id + " " + principalLabel(ctx, principal)
                + " -= " + permNames(perms));
        return "模板 " + id + " 已拒绝 " + permNames(perms) + " 给 "
                + principalLabel(ctx, principal);
    }

    /** 移除模板中某主体的授权条目。 */
    public static String executeTemplateUnset(CmdCtx ctx, String id, StoragePrincipal principal)
            throws CmdError {
        StorageTemplate template = requireTemplate(ctx, id, true);
        if (!template.grants().containsKey(principal)) {
            return "模板 " + id + " 中没有 " + principalLabel(ctx, principal) + " 的授权";
        }
        Map<StoragePrincipal, StorageGrant> next = new LinkedHashMap<>(template.grants());
        next.remove(principal);
        if (!ctx.data().updateTemplate(id, template.revision(), next)) {
            throw new CmdError("模板已变更，请重试");
        }
        audit(ctx, null, "template_unset", id + " remove " + principalLabel(ctx, principal));
        return "已移除模板 " + id + " 中 " + principalLabel(ctx, principal) + " 的授权";
    }

    /**
     * 模板重命名（仅改显示名，id 不变，FOLLOW 绑定不受影响）。
     * [CHANGED] 缺陷 #7 修复：改为直接调用 {@link StorageSavedData#renameTemplate}，
     * 不再经 encode→decode 热替换绕路。
     */
    public static String executeTemplateRename(CmdCtx ctx, String id, String newName)
            throws CmdError {
        StorageTemplate template = requireTemplate(ctx, id, true);
        validateTemplateName(newName);
        if (!ctx.data().renameTemplate(id, template.revision(), newName)) {
            throw new CmdError("模板已变更，请重试");
        }
        audit(ctx, null, "template_rename", id + " " + template.name() + " -> " + newName);
        return "模板已重命名: " + template.name() + " -> " + newName;
    }

    /** 复制模板：新建一个同授权的模板（玩家复制服务器模板得到自己的玩家模板）。 */
    public static String executeTemplateCopy(CmdCtx ctx, String srcId, String newId, String newName)
            throws CmdError {
        StorageTemplate src = requireTemplate(ctx, srcId, false);
        validateTemplateName(newName);
        StorageTemplate.Scope scope = src.scope() == StorageTemplate.Scope.SERVER && !ctx.admin()
                ? StorageTemplate.Scope.PLAYER : src.scope();
        UUID owner = scope == StorageTemplate.Scope.PLAYER ? ctx.actorId() : null;
        StorageTemplate copy = StorageTemplate.create(newId, scope, owner, newName,
                src.grants(), System.currentTimeMillis());
        try {
            ctx.data().createTemplate(copy);
        } catch (IllegalArgumentException e) {
            throw new CmdError(e.getMessage());
        }
        audit(ctx, null, "template_copy", srcId + " -> " + newId);
        return "已复制模板 " + srcId + " -> " + newId + "（" + newName + "）";
    }

    /** 删除模板：FOLLOW 仓储冻结当前有效权限（mergeGrants 保守合并）并审计。 */
    public static String executeTemplateDelete(CmdCtx ctx, String id) throws CmdError {
        StorageTemplate template = requireTemplate(ctx, id, true);
        int frozen = ctx.data().deleteTemplate(id);
        audit(ctx, null, "template_delete", "deleted " + id + ", frozen " + frozen + " storages");
        return "已删除模板 " + id + (frozen > 0
                ? "；已冻结 " + frozen + " 个 FOLLOW 仓储的当前有效权限（不扩大授权）" : "");
    }

    /** 模板详情。 */
    public static String executeTemplateInfo(CmdCtx ctx, String id) throws CmdError {
        StorageTemplate template = requireTemplate(ctx, id, false);
        StringBuilder sb = new StringBuilder();
        sb.append("模板: ").append(template.id())
                .append("（").append(template.name()).append("）")
                .append("\n作用域: ").append(template.scope().name().toLowerCase(Locale.ROOT))
                .append("\nrevision: ").append(template.revision());
        if (template.grants().isEmpty()) {
            sb.append("\n授权: （空）");
        } else {
            sb.append("\n授权:");
            for (Map.Entry<StoragePrincipal, StorageGrant> e : template.grants().entrySet()) {
                sb.append("\n  ").append(principalLabel(ctx, e.getKey()))
                        .append(" 允许[").append(permNames(e.getValue().allow().values())).append("]")
                        .append(" 拒绝[").append(permNames(e.getValue().deny().values())).append("]");
            }
        }
        return sb.toString();
    }

    /** 模板列表：服务器模板所有人可见；玩家模板仅所有者可见。 */
    public static String executeTemplateList(CmdCtx ctx) throws CmdError {
        List<String> lines = new ArrayList<>();
        for (StorageTemplate t : ctx.data().templatesView().values()) {
            if (t.scope() == StorageTemplate.Scope.SERVER || ctx.admin()
                    || (t.ownerId() != null && t.ownerId().equals(ctx.actorId()))) {
                lines.add("- " + t.id() + "（" + t.name() + "） "
                        + t.scope().name().toLowerCase(Locale.ROOT)
                        + " 主体 " + t.grants().size() + " 个");
            }
        }
        return lines.isEmpty() ? "（没有可用的模板）" : String.join("\n", lines);
    }

    /** 应用模板：copy 复制权限到仓储 ACL；follow 建立绑定（动态叠加待访问层接线）。 */
    public static String executeTemplateApply(CmdCtx ctx, String id,
                                              StorageRecord.TemplateMode mode, StorageKey key)
            throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        StorageTemplate template = requireTemplate(ctx, id, false);
        if (mode == StorageRecord.TemplateMode.FOLLOW) {
            if (!ctx.data().bindTemplate(key, record.revision(), id,
                    StorageRecord.TemplateMode.FOLLOW)) {
                throw new CmdError("绑定 FOLLOW 模板失败（revision 冲突或模板不可用）");
            }
            audit(ctx, key, "template_apply", "follow " + id);
            return "已绑定 FOLLOW 模板 " + id + "（模板更新将动态叠加到本仓储，本地 deny 优先）";
        }
        Map<StoragePrincipal, StorageGrant> merged =
                StorageTemplate.mergeGrants(template.grants(), record.grants());
        boolean ok = ctx.data().updateRecord(key, record.revision(), r ->
                new StorageRecord(r.ownerId(), r.ownerName(), r.displayName(), merged,
                        null, StorageRecord.TemplateMode.COPY,
                        r.automationInsertEnabled(), r.automationExtractEnabled(),
                        r.listedInBrowser(), r.createdAtEpochMillis(),
                        r.updatedAtEpochMillis(), r.revision()));
        if (!ok) {
            throw new CmdError("应用 COPY 模板失败（revision 冲突，仓储可能已变更）");
        }
        audit(ctx, key, "template_apply", "copy " + id
                + " (" + template.grants().size() + " principals)");
        return "已应用 COPY 模板 " + id + "（权限已复制到仓储，之后可独立修改）";
    }

    // ---------------------------------------------------------------- 自动化开关

    /** 查看自动化插入/抽取开关（非管理员需 MANAGE）。 */
    public static String executeAutomationGet(CmdCtx ctx, StorageKey key) throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        return "自动化插入: " + (record.automationInsertEnabled() ? "开" : "关")
                + "；自动化抽取: " + (record.automationExtractEnabled() ? "开" : "关");
    }

    /** 设置自动化插入/抽取开关。 */
    public static String executeAutomationSet(CmdCtx ctx, StorageKey key, boolean insert,
                                              boolean on) throws CmdError {
        StorageRecord record = requireManageable(ctx, key);
        boolean ok = insert
                ? ctx.data().setAutomationInsert(key, record.revision(), on)
                : ctx.data().setAutomationExtract(key, record.revision(), on);
        if (!ok) {
            throw new CmdError("设置失败（revision 冲突，仓储可能已变更）");
        }
        audit(ctx, key, insert ? "automation_insert" : "automation_extract", on ? "on" : "off");
        return "已" + (on ? "开启" : "关闭") + "自动化" + (insert ? "插入" : "抽取");
    }

    // ---------------------------------------------------------------- 审计

    /**
     * 审计查询：默认 20 条、最大 100 条，按 sequence(id) 倒序。
     * scope 为空 = 全局审计（仅管理员）；scope 非空 = 该仓储的审计（非管理员需 MANAGE）。
     * 输出对 UUID 脱敏（前 8 位 + …），玩家名可完整显示。
     */
    public static List<String> executeAudit(CmdCtx ctx, int count, StorageKey scope)
            throws CmdError {
        if (scope == null && !ctx.admin()) {
            throw new CmdError("全局审计仅管理员可用（可指定仓储位置查看该仓储审计）");
        }
        if (scope != null) {
            requireManageable(ctx, scope);
        }
        int limit = Math.max(1, Math.min(count, AUDIT_MAX_COUNT));
        List<StorageAuditEntry> entries = new ArrayList<>(ctx.data().auditView());
        if (scope != null) {
            entries.removeIf(e -> !scope.asString().equals(e.storageKey()));
        }
        entries.sort(Comparator.comparingLong(StorageAuditEntry::id).reversed());
        if (entries.size() > limit) {
            entries = new ArrayList<>(entries.subList(0, limit));
        }
        if (entries.isEmpty()) {
            return List.of("（无审计记录）");
        }
        List<String> lines = new ArrayList<>();
        for (StorageAuditEntry e : entries) {
            lines.add("#" + e.id() + " " + fmtTime(e.timestampEpochMillis())
                    + " [" + e.action() + "] 主体 " + maskUuid(e.actorId())
                    + " 仓储 " + e.storageKey() + " :: " + maskUuidsInText(e.detail()));
        }
        return lines;
    }

    // ---------------------------------------------------------------- 扫描与修复

    /** scan：以操作者为中心触发仓储发现扫描（限频由发现服务控制）。 */
    public static String executeScan(CmdCtx ctx, int radius) throws CmdError {
        StorageQuery query = new StorageQuery(ctx.actorId(),
                ctx.level().dimension().location().toString(),
                ctx.actorPos().getX(), ctx.actorPos().getZ(), radius, null,
                StorageQuery.Sort.DISTANCE, StorageQuery.Filter.VIEWABLE,
                StorageQuery.DEFAULT_MAX_RESULTS);
        List<StorageDescriptor> results = StorageServices.discovery().querySync(query);
        if (results.isEmpty()) {
            return "扫描完成：未发现已登记仓储（半径 " + radius + " 格）";
        }
        StringBuilder sb = new StringBuilder("扫描完成：发现 " + results.size()
                + " 个仓储（半径 " + radius + " 格）：");
        int shown = Math.min(8, results.size());
        for (int i = 0; i < shown; i++) {
            StorageDescriptor d = results.get(i);
            sb.append('\n').append(i + 1).append(". ").append(d.displayName())
                    .append(" [").append(d.storageId().adapterType()).append("] ")
                    .append(d.storageId().location());
            if (d.distance() >= 0) {
                sb.append(" 距离 ").append(d.distance()).append(" 格");
            }
        }
        if (results.size() > shown) {
            sb.append('\n').append("… 共 ").append(results.size()).append(" 个");
        }
        return sb.toString();
    }

    /**
     * repair：只修复索引与失效模板引用（仅管理员）。
     * <ul>
     *   <li>失效模板引用：调用 {@link StorageSavedData#repairTemplateReferences()}；</li>
     *   <li>区块索引：校验每个记录键是否出现在其分块的索引桶中，缺失时调用
     *       {@link StorageSavedData#rebuildChunkIndex()} 按记录重建（[CHANGED] 缺陷 #7
     *       修复：不再经 SavedData 热替换绕路）；</li>
     *   <li>不猜测所有者、不删除仍可能卸载的仓储记录。</li>
     * </ul>
     */
    public static String executeRepair(CmdCtx ctx) throws CmdError {
        if (!ctx.admin()) {
            throw new CmdError("修复需要管理员权限");
        }
        StorageSavedData data = ctx.data();
        int repairedRefs = data.repairTemplateReferences();
        int missingIndex = 0;
        for (StorageKey key : data.recordsView().keySet()) {
            BlockPos pos = AbstractContainerAdapter.parsePos(key.location());
            if (pos == null) {
                // 虚拟个人仓储（末影箱）：不占用世界方块，无需区块索引
                if (VanillaEnderChestAdapter.TYPE_ID.equals(key.adapterType())) {
                    continue;
                }
                missingIndex++;
                continue;
            }
            if (!data.keysInChunk(key.dimension(), pos.getX() >> 4, pos.getZ() >> 4)
                    .contains(key)) {
                missingIndex++;
            }
        }
        int rebuilt = 0;
        if (missingIndex > 0) {
            data.rebuildChunkIndex();
            rebuilt = missingIndex;
        }
        audit(ctx, null, "repair", "templates " + repairedRefs
                + ", index missing " + missingIndex + ", rebuilt " + rebuilt);
        return "修复完成：模板引用修复 " + repairedRefs + " 处；区块索引缺失 "
                + missingIndex + " 条" + (rebuilt > 0 ? "（已重建）" : "");
    }

    // ---------------------------------------------------------------- 权限校验

    /** 非管理员操作门槛：已加载、同维度、8 格内 + MANAGE（owner 恒有，管理员绕过并记录动作）。 */
    private static StorageRecord requireManageable(CmdCtx ctx, StorageKey key) throws CmdError {
        return requireStorage(ctx, key, false);
    }

    /** transfer/unclaim 门槛：在 requireManageable 基础上仅所有者或管理员。 */
    private static StorageRecord requireOwnerOrAdmin(CmdCtx ctx, StorageKey key) throws CmdError {
        return requireStorage(ctx, key, true);
    }

    private static StorageRecord requireStorage(CmdCtx ctx, StorageKey key, boolean ownerOnly)
            throws CmdError {
        BlockPos pos = AbstractContainerAdapter.parsePos(key.location());
        if (pos == null) {
            throw new CmdError("无法解析仓储位置: " + key.location());
        }
        if (!key.dimension().equals(ctx.level().dimension().location().toString())) {
            throw new CmdError("仓储在其他维度（跨维度需管理员并显式指定维度）");
        }
        if (!ctx.admin()) {
            if (!ctx.level().isLoaded(pos)) {
                throw new CmdError("仓储所在区块未加载");
            }
            long dx = (long) ctx.actorPos().getX() - pos.getX();
            long dz = (long) ctx.actorPos().getZ() - pos.getZ();
            if (dx * dx + dz * dz > (long) MANAGE_RANGE_BLOCKS * MANAGE_RANGE_BLOCKS) {
                throw new CmdError("距离仓储过远（水平 " + MANAGE_RANGE_BLOCKS + " 格内才能操作）");
            }
        }
        StorageRecord record = ctx.data().getRecord(key)
                .orElseThrow(() -> new CmdError("该仓储尚未认领"));
        if (!ctx.admin()) {
            StorageAccessService.AccessSnapshot snapshot =
                    new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
            if (!StorageServices.access().canManage(ctx.actorId(), snapshot)) {
                throw new CmdError("缺少 MANAGE 权限，无法操作该仓储");
            }
        }
        if (ownerOnly && !ctx.admin() && !record.ownerId().equals(ctx.actorId())) {
            throw new CmdError("仅所有者或管理员可执行此操作");
        }
        return record;
    }

    /** claim 用门槛：已加载、同维度、8 格内（不要求 MANAGE——仓储尚无人认领）。 */
    private static void requireLoadedNear(CmdCtx ctx, StorageKey key, BlockPos pos)
            throws CmdError {
        if (!key.dimension().equals(ctx.level().dimension().location().toString())) {
            throw new CmdError("仓储在其他维度（跨维度需管理员并显式指定维度）");
        }
        if (!ctx.admin()) {
            if (!ctx.level().isLoaded(pos)) {
                throw new CmdError("仓储所在区块未加载");
            }
            long dx = (long) ctx.actorPos().getX() - pos.getX();
            long dz = (long) ctx.actorPos().getZ() - pos.getZ();
            if (dx * dx + dz * dz > (long) MANAGE_RANGE_BLOCKS * MANAGE_RANGE_BLOCKS) {
                throw new CmdError("距离仓储过远（水平 " + MANAGE_RANGE_BLOCKS + " 格内才能认领）");
            }
        }
    }

    /** 模板访问：writable 表示需要可管理（服务器模板仅管理员可改；玩家模板仅所有者可改）。 */
    private static StorageTemplate requireTemplate(CmdCtx ctx, String id, boolean writable)
            throws CmdError {
        StorageTemplate template = ctx.data().getTemplate(id)
                .orElseThrow(() -> new CmdError("模板不存在: " + id));
        if (template.scope() == StorageTemplate.Scope.SERVER) {
            if (writable && !ctx.admin()) {
                throw new CmdError("仅管理员可修改服务器模板");
            }
        } else if (template.ownerId() == null || !template.ownerId().equals(ctx.actorId())) {
            throw new CmdError("仅模板所有者可访问该模板");
        }
        return template;
    }

    // ---------------------------------------------------------------- 审计工具

    /** 高风险授权审计：BREAK 授权、PUBLIC+MANAGE、PUBLIC+WITHDRAW 服务端留痕。 */
    private static void auditHighRisk(CmdCtx ctx, StorageKey storageKey,
                                      StoragePrincipal principal,
                                      EnumSet<StoragePermission> perms) {
        boolean publicManage = principal instanceof StoragePrincipal.Public
                && perms.contains(StoragePermission.MANAGE);
        boolean publicWithdraw = principal instanceof StoragePrincipal.Public
                && perms.contains(StoragePermission.WITHDRAW);
        boolean breakPerm = perms.contains(StoragePermission.BREAK);
        if (publicManage || publicWithdraw || breakPerm) {
            audit(ctx, storageKey, "high_risk_grant",
                    principalLabel(ctx, principal) + " += " + permNames(perms));
        }
    }

    private static void audit(CmdCtx ctx, StorageKey key, String action, String detail) {
        ctx.data().appendAudit(System.currentTimeMillis(),
                key != null ? key.asString() : "-", ctx.actorId(), action, detail);
    }

    // ---------------------------------------------------------------- 存档数据访问

    private static StorageSavedData savedData(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    // [REMOVED] replaceSavedData / rebuildChunkIndexTag：缺陷 #7 移除 encode→decode
    // 热替换绕路后已无使用者，unclaim/rename/repair 均改调 StorageSavedData 直接 API。

    // ---------------------------------------------------------------- 解析工具

    private static StorageProtectionEvents protection() {
        StorageProtectionEvents local = protectionEvents;
        if (local == null) {
            synchronized (StorageCommands.class) {
                local = protectionEvents;
                if (local == null) {
                    local = new StorageProtectionEvents(
                            StorageServices.registry(), StorageServices.discovery());
                    protectionEvents = local;
                }
            }
        }
        return local;
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimStr) {
        try {
            return server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimStr)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static UUID resolvePlayerUuid(MinecraftServer server, String name) throws CmdError {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> profile = cache.get(name);
            if (profile.isPresent() && profile.get().getId() != null) {
                return profile.get().getId();
            }
        }
        throw new CmdError("找不到玩家: " + name + "（离线玩家需先上线过）");
    }

    private static String resolvePlayerName(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getName().getString();
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            Optional<GameProfile> profile = cache.get(uuid);
            if (profile.isPresent() && profile.get().getName() != null) {
                return profile.get().getName();
            }
        }
        return maskUuid(uuid);
    }

    private static String principalLabel(CmdCtx ctx, StoragePrincipal principal) {
        if (principal instanceof StoragePrincipal.Player p) {
            return resolvePlayerName(ctx.server(), p.uuid());
        }
        if (principal instanceof StoragePrincipal.Group g) {
            return g.toString();
        }
        return "公开";
    }

    private static StorageTemplate.Scope parseScope(String s) throws CmdError {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "player" -> StorageTemplate.Scope.PLAYER;
            case "server" -> StorageTemplate.Scope.SERVER;
            default -> throw new CmdError("无效作用域: " + s + "（可选: player,server）");
        };
    }

    private static void validateTemplateName(String name) throws CmdError {
        if (name == null || name.isEmpty() || name.length() > StorageTemplate.MAX_NAME_LENGTH) {
            throw new CmdError("模板名长度需为 1.." + StorageTemplate.MAX_NAME_LENGTH + " 字符");
        }
    }

    private static StoragePermissionSet plus(StoragePermissionSet base,
                                             EnumSet<StoragePermission> add) {
        EnumSet<StoragePermission> set = EnumSet.copyOf(base.values());
        set.addAll(add);
        return new StoragePermissionSet(set);
    }

    private static StoragePermissionSet minus(StoragePermissionSet base,
                                              EnumSet<StoragePermission> remove) {
        EnumSet<StoragePermission> set = EnumSet.copyOf(base.values());
        set.removeAll(remove);
        return new StoragePermissionSet(set);
    }

    private static String permNames(EnumSet<StoragePermission> perms) {
        List<String> names = new ArrayList<>();
        for (StoragePermission p : perms) {
            names.add(p.name().toLowerCase(Locale.ROOT));
        }
        return String.join(",", names);
    }

    private static String fmtTime(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(TIME_FMT);
    }

    private static String maskUuidsInText(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = UUID_PATTERN.matcher(text);
        return m.replaceAll(x -> x.group().substring(0, 8) + "…");
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------- Brigadier 小工具

    private static String arg(CommandContext<CommandSourceStack> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }

    private static int intArg(CommandContext<CommandSourceStack> ctx, String name) {
        return ctx.getArgument(name, Integer.class);
    }

    private static boolean bool(CommandContext<CommandSourceStack> ctx) {
        return ctx.getArgument(ARG_ON, Boolean.class);
    }

    /** 读取维度参数（DimensionArgument 解析为 ResourceKey，需转成字符串）。 */
    private static String dimArg(CommandContext<CommandSourceStack> ctx, String name) {
        return ctx.getArgument(name, ResourceKey.class).location().toString();
    }
}
