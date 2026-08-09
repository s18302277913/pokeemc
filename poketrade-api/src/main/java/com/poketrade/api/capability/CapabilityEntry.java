package com.poketrade.api.capability;

/**
 * 能力摘要条目。
 *
 * @param id             稳定标识（modId / backendId / typeId）
 * @param implementation 实现类名
 * @param active         是否处于活动状态
 */
public record CapabilityEntry(String id, String implementation, boolean active) {
}
