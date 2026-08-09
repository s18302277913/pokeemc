package com.pokeemc.trade.network;

import com.mojang.logging.LogUtils;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.service.PokemonLocator;
import com.pokeemc.trade.service.TradeAssetPage;
import com.pokeemc.trade.service.TradeDirectoryPage;
import com.pokeemc.trade.service.TradeResult;
import com.pokeemc.trade.service.TradeRuntime;
import com.pokeemc.trade.service.TradeService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 玩家交易 C2S 统一处理门面（计划 5.1/5.3，Task 8）。
 * <p>
 * 每个 handler 的固定流水线（全部在 {@code context.enqueueWork} 服务端主线程内）：
 * <ol>
 *   <li>解析玩家 UUID；</li>
 *   <li>按类别限流（{@link TradeRateLimiter}：创建/接受/确认 2/s、报价变更 10/s、翻页 5/s）；</li>
 *   <li>请求去重（{@link TradeRequestCache}：重复 requestId 直接回缓存结果，避免重复执行非幂等操作）；</li>
 *   <li>边界校验（{@link TradePacketBoundaries}：槽位/数量/箱号/页码/搜索词）；</li>
 *   <li>{@link TradeRuntime#service()} 非空检查（未装配阶段安全 no-op）；</li>
 *   <li>调用 {@link TradeService}；成功向双方广播新快照（{@link TradeSnapshotProjection}），
 *       失败只回请求者失败回执（不泄露 tradeId/对手信息）。</li>
 * </ol>
 * 限流失败不进入去重缓存（客户端可稍后重试）；边界/服务失败进入缓存（确定性结果）。
 */
public final class TradeNetworkHandlers {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final TradeRateLimiter RATE = new TradeRateLimiter();
    private static final TradeRequestCache CACHE = new TradeRequestCache();

    private TradeNetworkHandlers() {
    }

    /** 服务调用函数：handler 闭包捕获 packet 字段，注入 playerId 调用 */
    @FunctionalInterface
    private interface ServiceCall {
        TradeResult invoke(TradeService service, UUID playerId);
    }

    // ------------------------------------------------------------------ 创建 / 接受 / 确认

    public static void onCreate(CreateTradePacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.CREATE_OR_CONFIRM,
                () -> TradePacketBoundaries.checkCreate(packet),
                (svc, pid) -> svc.invite(pid, packet.targetPlayerId()));
    }

    public static void onAccept(AcceptTradePacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.CREATE_OR_CONFIRM,
                () -> TradeError.NONE,
                (svc, pid) -> svc.accept(pid, new TradeId(packet.tradeId()), packet.expectedRevision()));
    }

    public static void onConfirm(ConfirmTradePacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.CREATE_OR_CONFIRM,
                () -> TradeError.NONE,
                (svc, pid) -> svc.confirm(pid, new TradeId(packet.tradeId()), packet.expectedRevision()));
    }

    // ------------------------------------------------------------------ 报价变更

    public static void onOfferItem(OfferItemPacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.OFFER_CHANGE,
                () -> TradePacketBoundaries.checkOfferItem(packet),
                (svc, pid) -> svc.offerItem(pid, new TradeId(packet.tradeId()), packet.expectedRevision(),
                        packet.inventorySlot(), packet.count()));
    }

    public static void onOfferPkm(OfferPkmPacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.OFFER_CHANGE,
                () -> TradePacketBoundaries.checkOfferPkm(packet),
                (svc, pid) -> svc.offerPkm(pid, new TradeId(packet.tradeId()), packet.expectedRevision(),
                        packet.amount()));
    }

    public static void onOfferPokemon(OfferPokemonPacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.OFFER_CHANGE,
                () -> TradePacketBoundaries.checkOfferPokemon(packet),
                (svc, pid) -> svc.offerPokemon(pid, new TradeId(packet.tradeId()), packet.expectedRevision(),
                        new PokemonLocator(packet.storageKind(), packet.box(), packet.slot())));
    }

    public static void onRemoveAsset(RemoveOfferAssetPacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.OFFER_CHANGE,
                () -> TradePacketBoundaries.checkRemoveAsset(packet),
                (svc, pid) -> svc.removeAsset(pid, new TradeId(packet.tradeId()), packet.expectedRevision(),
                        packet.assetId()));
    }

    public static void onSetPreference(SetDeliveryPreferencePacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.OFFER_CHANGE,
                () -> TradePacketBoundaries.checkSetPreference(packet),
                (svc, pid) -> svc.setDeliveryPreference(pid, new TradeId(packet.tradeId()),
                        packet.expectedRevision(), packet.preference()));
    }

    public static void onCancel(CancelTradePacket packet, IPayloadContext context) {
        handleMutate(context, packet.requestId(), TradeRateLimiter.Category.OFFER_CHANGE,
                () -> TradeError.NONE,
                (svc, pid) -> svc.cancel(pid, new TradeId(packet.tradeId()), packet.expectedRevision()));
    }

    // ------------------------------------------------------------------ 翻页（只读）

    public static void onDirectory(RequestTradeDirectoryPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer self = playerOf(context);
            if (self == null) {
                return;
            }
            UUID playerId = self.getUUID();
            if (!RATE.allow(playerId, TradeRateLimiter.Category.PAGE, System.currentTimeMillis())) {
                context.reply(TradeResultPacket.fail(packet.requestId(), TradeError.RATE_LIMITED));
                return;
            }
            TradeError boundary = TradePacketBoundaries.checkDirectory(packet);
            if (boundary != TradeError.NONE) {
                context.reply(TradeResultPacket.fail(packet.requestId(), boundary));
                return;
            }
            TradeService service = TradeRuntime.service();
            if (service == null) {
                context.reply(TradeResultPacket.fail(packet.requestId(), TradeError.CAPABILITY_UNAVAILABLE));
                return;
            }
            TradeDirectoryPage page = service.directory(playerId, packet.query(), packet.page(), packet.pageSize());
            List<TradeDirectoryPacket.PlayerDirectoryEntry> entries = new ArrayList<>(page.entries().size());
            for (TradeDirectoryPage.DirectoryEntry e : page.entries()) {
                entries.add(new TradeDirectoryPacket.PlayerDirectoryEntry(
                        e.playerId(), e.displayName(), e.capability()));
            }
            context.reply(new TradeDirectoryPacket(
                    packet.requestId(), entries, page.total(), page.page(), page.pageSize()));
        });
    }

    public static void onAssetPage(RequestTradeAssetPagePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer self = playerOf(context);
            if (self == null) {
                return;
            }
            UUID playerId = self.getUUID();
            if (!RATE.allow(playerId, TradeRateLimiter.Category.PAGE, System.currentTimeMillis())) {
                context.reply(TradeResultPacket.fail(packet.requestId(), TradeError.RATE_LIMITED));
                return;
            }
            TradeError boundary = TradePacketBoundaries.checkAssetPage(packet);
            if (boundary != TradeError.NONE) {
                context.reply(TradeResultPacket.fail(packet.requestId(), boundary));
                return;
            }
            TradeService service = TradeRuntime.service();
            if (service == null) {
                context.reply(TradeResultPacket.fail(packet.requestId(), TradeError.CAPABILITY_UNAVAILABLE));
                return;
            }
            Outcome<TradeAssetPage> out = service.ownAssets(playerId, packet.tradeId(), packet.expectedRevision(),
                    packet.kind(), packet.page(), packet.pageSize());
            if (!out.ok()) {
                context.reply(TradeResultPacket.fail(packet.requestId(), out.error()));
                return;
            }
            TradeAssetPage page = out.value();
            context.reply(new TradeAssetPagePacket(packet.requestId(), playerId, page.assetRevision(),
                    page.kind(), page.page(), page.pageSize(), page.total(), page.entries()));
        });
    }

    // ------------------------------------------------------------------ 公共流水线

    /**
     * 变更类操作统一流水线：限流 → 去重 → 边界 → 装配检查 → 服务调用 → 广播/回执。
     * 限流失败不缓存；边界失败与服务失败进入去重缓存（确定性结果，重复请求直接回）。
     */
    private static void handleMutate(IPayloadContext context, UUID requestId,
                                     TradeRateLimiter.Category category,
                                     Supplier<TradeError> boundary,
                                     ServiceCall call) {
        context.enqueueWork(() -> {
            ServerPlayer self = playerOf(context);
            if (self == null) {
                return;
            }
            UUID playerId = self.getUUID();
            if (!RATE.allow(playerId, category, System.currentTimeMillis())) {
                replyResult(context, requestId, TradeRequestCache.CachedResult.fail(TradeError.RATE_LIMITED));
                return;
            }
            TradeRequestCache.CachedResult cached = CACHE.get(requestId).orElse(null);
            if (cached != null) {
                replyResult(context, requestId, cached);
                return;
            }
            TradeError b = boundary.get();
            if (b != TradeError.NONE) {
                replyResult(context, requestId,
                        CACHE.remember(requestId, TradeRequestCache.CachedResult.fail(b)));
                return;
            }
            TradeService service = TradeRuntime.service();
            if (service == null) {
                replyResult(context, requestId, CACHE.remember(requestId,
                        TradeRequestCache.CachedResult.fail(TradeError.CAPABILITY_UNAVAILABLE)));
                return;
            }
            TradeResult result;
            try {
                result = call.invoke(service, playerId);
            } catch (RuntimeException ex) {
                // 服务层异常（非法位置/序列化等）：不泄漏异常文本，按稳定错误处理
                LOGGER.warn("PokeEMC: trade op failed player={} requestId={}", playerId, requestId, ex);
                replyResult(context, requestId, CACHE.remember(requestId,
                        TradeRequestCache.CachedResult.fail(TradeError.INVALID_INPUT)));
                return;
            }
            TradeRequestCache.CachedResult r = result.success()
                    ? TradeRequestCache.CachedResult.ok(
                            result.tradeId() == null ? null : result.tradeId().value(), result.revision())
                    : TradeRequestCache.CachedResult.fail(result.error());
            CACHE.remember(requestId, r);
            if (result.success()) {
                broadcastSnapshots(self);
            }
            replyResult(context, requestId, r);
        });
    }

    /** 成功变更后向双方各推送一次新快照（按各玩家 self 视角投影）；终态移除后无快照可推 */
    private static void broadcastSnapshots(ServerPlayer requester) {
        TradeService service = TradeRuntime.service();
        if (service == null) {
            return;
        }
        service.snapshot(requester.getUUID()).ifPresent(selfSnap -> {
            UUID otherId = selfSnap.otherPlayerId();
            sendSnapshot(requester.server, selfSnap);
            service.snapshot(otherId).ifPresent(otherSnap -> sendSnapshot(requester.server, otherSnap));
        });
    }

    private static void sendSnapshot(MinecraftServer server, com.pokeemc.trade.service.TradeSnapshot snap) {
        ServerPlayer player = server.getPlayerList().getPlayer(snap.selfPlayerId());
        if (player == null) {
            return; // 离线不推；登录时由收件箱/客户端刷新恢复
        }
        String selfName = TradeRuntime.displayName(snap.selfPlayerId());
        String otherName = TradeRuntime.displayName(snap.otherPlayerId());
        PacketDistributor.sendToPlayer(player,
                TradeSnapshotProjection.project(snap, selfName, otherName));
    }

    /** 回执：成功携带 tradeId + 最新 revision；失败只回请求者，不带 tradeId */
    private static void replyResult(IPayloadContext context, UUID requestId,
                                    TradeRequestCache.CachedResult r) {
        if (r.success()) {
            context.reply(TradeResultPacket.ok(requestId, r.tradeId(), r.revision()));
        } else {
            context.reply(TradeResultPacket.fail(requestId, r.error()));
        }
    }

    private static ServerPlayer playerOf(IPayloadContext context) {
        return context.player() instanceof ServerPlayer p ? p : null;
    }
}
