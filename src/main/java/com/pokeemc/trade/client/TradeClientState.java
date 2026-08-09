package com.pokeemc.trade.client;

import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.network.TradeAssetPagePacket;
import com.pokeemc.trade.network.TradeDirectoryPacket;
import com.pokeemc.trade.network.TradeSnapshotPacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家交易客户端缓存状态（计划 5.4，Task 9）。
 * <p>
 * 职责：保存当前活动交易快照、在线目录、按 kind+page 的资产页，并做防旧包覆盖：
 * 新快照 revision 必须严格大于当前；资产页 assetRevision 必须大于等于当前值才会替换。
 * 退出世界（或切换服务器）时必须调用 {@link #clear()} 清空全部缓存，避免跨服务器泄漏。
 * <p>
 * 纯 JVM 可测：只依赖网络层纯 record（{@link TradeSnapshotPacket} 等），不触碰 MC 客户端类型。
 */
public class TradeClientState {

    /** 客户端全局单例：网络层 S2C handler 写入，{@code PlayerTradeScreen} 读取 */
    public static final TradeClientState INSTANCE = new TradeClientState();

    private TradeSnapshotPacket snapshot;
    private UUID selfPlayerId;
    private TradeDirectoryPacket directory;
    private final Map<PageKey, TradeAssetPagePacket> assetPages = new LinkedHashMap<>();

    /** 处理新快照；旧 revision（或相等）不覆盖新值 */
    public void onSnapshot(TradeSnapshotPacket p) {
        if (snapshot != null && p.revision() <= snapshot.revision()) {
            return;
        }
        snapshot = p;
        selfPlayerId = p.selfPlayer().playerId();
    }

    /** 处理目录响应；目录无版本号，直接替换 */
    public void onDirectory(TradeDirectoryPacket p) {
        directory = p;
    }

    /** 处理资产页；assetRevision 严格更旧的不覆盖（同 kind+page） */
    public void onAssetPage(TradeAssetPagePacket p) {
        PageKey key = new PageKey(p.kind(), p.page());
        TradeAssetPagePacket cur = assetPages.get(key);
        if (cur != null && p.assetRevision() < cur.assetRevision()) {
            return;
        }
        assetPages.put(key, p);
    }

    /** 当前活动交易快照；无则 empty */
    public Optional<TradeSnapshotPacket> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    /** 本机玩家 UUID（从最近一次快照的 self 侧推导） */
    public UUID selfPlayerId() {
        return selfPlayerId;
    }

    /** 是否有活动交易（已收到过快照且未清空） */
    public boolean hasActiveTrade() {
        return snapshot != null;
    }

    /** 最新在线目录；无则 empty */
    public Optional<TradeDirectoryPacket> directory() {
        return Optional.ofNullable(directory);
    }

    /** 指定 kind+page 的资产页；未缓存返回 null */
    public TradeAssetPagePacket assetPage(AssetPageKind kind, int page) {
        return assetPages.get(new PageKey(kind, page));
    }

    /** 退出世界/切换服务器：清空全部缓存（快照、身份、目录、资产页） */
    public void clear() {
        snapshot = null;
        selfPlayerId = null;
        directory = null;
        assetPages.clear();
    }

    /** 资产页缓存键：类别 + 页码 */
    private record PageKey(AssetPageKind kind, int page) {
    }
}
