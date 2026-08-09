package com.poketrade.api.permission;

/**
 * 保护 Provider 声明的能力类别。
 */
public enum ProtectionCapability {
    /** 领地/区域类保护（如 griefdefense、worldguard）。 */
    CLAIM_PROTECTION,
    /** 单方块锁类保护（如 lockettepro）。 */
    LOCK_PROTECTION
}
