package com.pokeemc.trade.network;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每玩家每秒速率限制器（计划 5.1）：固定窗口计数。
 * <p>
 * 类别配额：创建/确认 2 次/秒、报价变更 10 次/秒、目录/资产翻页 5 次/秒。
 * 窗口以 epoch 秒为桶，容量有上限（淘汰最旧桶）防止恶意玩家撑爆内存。
 * 纯 JVM 可测：{@code nowMillis} 由调用方（handler）注入，测试可直接驱动。
 */
public final class TradeRateLimiter {

    /** 操作类别（handler 按类别限流） */
    public enum Category {
        /** 创建邀请 / 确认：2 次/秒 */
        CREATE_OR_CONFIRM,
        /** 报价变更（托管/移除/偏好/取消）：10 次/秒 */
        OFFER_CHANGE,
        /** 目录 / 资产翻页：5 次/秒 */
        PAGE
    }

    private final int createLimit;
    private final int offerLimit;
    private final int pageLimit;

    /** accessOrder=true：迭代顺序即 LRU，便于淘汰最旧桶 */
    private final Map<Key, Integer> counts = new LinkedHashMap<>(64, 0.75f, true);
    private final int maxBuckets;

    public TradeRateLimiter() {
        this(TradePacketLimits.RATE_CREATE_OR_CONFIRM_PER_SECOND,
                TradePacketLimits.RATE_OFFER_CHANGE_PER_SECOND,
                TradePacketLimits.RATE_PAGE_PER_SECOND);
    }

    public TradeRateLimiter(int createLimit, int offerLimit, int pageLimit) {
        this(createLimit, offerLimit, pageLimit, 4096);
    }

    public TradeRateLimiter(int createLimit, int offerLimit, int pageLimit, int maxBuckets) {
        if (createLimit <= 0 || offerLimit <= 0 || pageLimit <= 0 || maxBuckets <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.createLimit = createLimit;
        this.offerLimit = offerLimit;
        this.pageLimit = pageLimit;
        this.maxBuckets = maxBuckets;
    }

    /** 该玩家当前窗口内是否允许本次操作 */
    public boolean allow(UUID playerId, Category category, long nowMillis) {
        int limit = switch (category) {
            case CREATE_OR_CONFIRM -> createLimit;
            case OFFER_CHANGE -> offerLimit;
            case PAGE -> pageLimit;
        };
        Key key = new Key(playerId, category, nowMillis / 1000);
        Integer c = counts.get(key);
        int count = c == null ? 0 : c;
        if (count >= limit) {
            return false;
        }
        counts.put(key, count + 1);
        evictIfNeeded();
        return true;
    }

    /** 清空全部计数（测试 / 服务器重载） */
    public void reset() {
        counts.clear();
    }

    private void evictIfNeeded() {
        if (counts.size() <= maxBuckets) {
            return;
        }
        int toRemove = counts.size() - maxBuckets;
        Iterator<Map.Entry<Key, Integer>> it = counts.entrySet().iterator();
        while (toRemove-- > 0 && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private record Key(UUID playerId, Category category, long secondBucket) {
    }
}
