package com.pokeemc.trade.network;

import com.pokeemc.trade.model.TradeError;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * C2S 请求去重缓存（计划 5.1）：保留最近 {@link TradePacketLimits#REQUEST_CACHE_SIZE}
 * 个 {@code requestId -> result}。重复 requestId 直接返回缓存结果，避免重复执行
 * 非幂等操作（如 offerItem 重复托管、重复扣费）。
 * <p>
 * 纯 JVM 可测；线程安全由调用方（服务端主线程）保证。
 */
public final class TradeRequestCache {

    /** 缓存结果：成功携带 tradeId 与最新 revision；失败携带稳定错误码（tradeId 为空） */
    public record CachedResult(boolean success, TradeError error, UUID tradeId, long revision) {

        public static CachedResult ok(UUID tradeId, long revision) {
            return new CachedResult(true, TradeError.NONE, tradeId, revision);
        }

        public static CachedResult fail(TradeError error) {
            return new CachedResult(false, error, null, 0);
        }
    }

    private final int capacity;
    private final LinkedHashMap<UUID, CachedResult> cache;

    public TradeRequestCache() {
        this(TradePacketLimits.REQUEST_CACHE_SIZE);
    }

    public TradeRequestCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.cache = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, CachedResult> eldest) {
                return size() > TradeRequestCache.this.capacity;
            }
        };
    }

    /** 查询缓存；无记录返回 empty */
    public Optional<CachedResult> get(UUID requestId) {
        return Optional.ofNullable(cache.get(requestId));
    }

    /** 记住本次结果（覆盖同 requestId 旧值） */
    public CachedResult remember(UUID requestId, CachedResult result) {
        cache.put(requestId, result);
        return result;
    }

    /** 当前缓存条目数（测试断言容量） */
    public int size() {
        return cache.size();
    }

    /** 清空（测试） */
    public void clear() {
        cache.clear();
    }
}
