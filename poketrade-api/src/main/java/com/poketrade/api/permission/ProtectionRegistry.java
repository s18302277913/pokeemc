package com.poketrade.api.permission;

import java.util.List;
import java.util.Optional;

/**
 * 保护 Provider 注册表。
 *
 * <p>重复 {@code modId} 必须被拒绝（注册冲突直接失败并记录错误）。</p>
 */
public interface ProtectionRegistry {

    /**
     * 注册保护 Provider。
     *
     * @throws IllegalArgumentException 若 {@code modId} 已注册，或
     *                                  {@code apiVersion()} 与当前 API 版本不一致
     */
    void register(ProtectionProvider provider);

    /** 按注册顺序返回全部 Provider。 */
    List<ProtectionProvider> providers();

    /** 按 modId 查找 Provider。 */
    Optional<ProtectionProvider> byModId(String modId);
}
