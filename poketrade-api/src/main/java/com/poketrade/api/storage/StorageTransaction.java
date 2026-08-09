package com.poketrade.api.storage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 仓储事务请求。
 *
 * <p>使用 {@code sessionId} + {@code operationId} 实现幂等：同一操作重复提交
 * 返回相同结果而不重复执行。{@code expectedRevisions} 用于并发校验，
 * 服务端会拒绝与当前修订不符的请求。</p>
 */
public record StorageTransaction(
        UUID actorId,
        String sessionId,
        String operationId,
        StorageEndpoint source,
        StorageEndpoint target,
        int requestedCount,
        long sourceFingerprint,
        long targetFingerprint,
        Map<StorageId, Long> expectedRevisions) {

    public StorageTransaction {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (source.equals(target)) {
            throw new IllegalArgumentException("source and target must differ");
        }
        if (requestedCount <= 0) {
            throw new IllegalArgumentException("requestedCount must be positive");
        }
        Objects.requireNonNull(expectedRevisions, "expectedRevisions");
        Map<StorageId, Long> copy = new LinkedHashMap<>(expectedRevisions.size());
        expectedRevisions.forEach((id, rev) -> {
            Objects.requireNonNull(id, "expected revision storage id");
            if (rev == null || rev < 0) {
                throw new IllegalArgumentException("expected revision must be non-negative");
            }
            copy.put(id, rev);
        });
        expectedRevisions = Collections.unmodifiableMap(copy);
    }

    @Override
    public Map<StorageId, Long> expectedRevisions() {
        return expectedRevisions;
    }
}
