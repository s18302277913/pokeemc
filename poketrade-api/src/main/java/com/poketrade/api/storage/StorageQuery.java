package com.poketrade.api.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * 仓储浏览查询参数。
 *
 * <p>查询本身不授予权限：服务端始终按 {@code actorId} 重新鉴权，
 * 未获 VIEW（或至少一个可执行动作）权限的仓储不会出现在结果中。</p>
 */
public record StorageQuery(
        UUID actorId,
        String dimension,
        int centerX,
        int centerZ,
        int radius,
        String searchText,
        Sort sort,
        Filter filter,
        int maxResults) {

    /**
     * 排序方式。
     */
    public enum Sort {
        DISTANCE, NAME, FREE_SLOTS, RECENTLY_UPDATED
    }

    /**
     * 浏览过滤维度。服务端按权限与能力共同决定。
     */
    public enum Filter {
        /** 我可查看（VIEW） */
        VIEWABLE,
        /** 我可存入（DEPOSIT） */
        DEPOSITABLE,
        /** 我可取出（WITHDRAW） */
        WITHDRAWABLE,
        /** 我可出售（SELL 且适配器支持 SELL_SOURCE） */
        SELLABLE,
        /** 我拥有（MANAGE 或 owner） */
        OWNED,
        /** 我可管理（MANAGE） */
        MANAGEABLE
    }

    public static final int DEFAULT_MAX_RESULTS = 50;
    public static final int HARD_MAX_RESULTS = 200;

    public StorageQuery {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(dimension, "dimension");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must be non-negative");
        }
        if (maxResults < 1 || maxResults > HARD_MAX_RESULTS) {
            throw new IllegalArgumentException(
                    "maxResults out of range [1," + HARD_MAX_RESULTS + "]: " + maxResults);
        }
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(filter, "filter");
    }

    /**
     * 便捷工厂：使用默认排序与过滤器。
     */
    public static StorageQuery nearby(UUID actorId, String dimension,
                                      int centerX, int centerZ, int radius) {
        return new StorageQuery(actorId, dimension, centerX, centerZ, radius,
                null, Sort.DISTANCE, Filter.VIEWABLE, DEFAULT_MAX_RESULTS);
    }
}
