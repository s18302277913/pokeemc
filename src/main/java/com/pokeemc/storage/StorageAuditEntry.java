package com.pokeemc.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * 单条审计记录。
 *
 * <p>内容约束：detail 最长 256 字符（超长截断）；禁止保存完整物品 NBT、
 * 聊天内容、IP 或令牌——这些属于调用方义务，本类型只做机械截断与基本
 * 字段校验。审计保存在 {@link StorageSavedData} 的环形队列中，超出容量
 * 时丢弃最旧条目。</p>
 */
public record StorageAuditEntry(
        long id,
        long timestampEpochMillis,
        String storageKey,
        UUID actorId,
        String action,
        String detail) {

    public static final int MAX_DETAIL_LENGTH = 256;

    public StorageAuditEntry {
        if (id < 0) {
            throw new IllegalArgumentException("id must be >= 0");
        }
        if (timestampEpochMillis < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative");
        }
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(action, "action");
        if (action.isEmpty()) {
            throw new IllegalArgumentException("action must not be empty");
        }
        Objects.requireNonNull(detail, "detail");
        detail = truncate(detail);
    }

    /**
     * 将 detail 截断到 {@value #MAX_DETAIL_LENGTH} 字符。
     */
    public static String truncate(String text) {
        return text.length() <= MAX_DETAIL_LENGTH
                ? text
                : text.substring(0, MAX_DETAIL_LENGTH);
    }
}
