package com.poketrade.api.capability;

/**
 * 适配类别（命令输出分组与探测用途）。
 */
public enum AdapterCategory {
    /** 容器适配（StorageAdapter）。 */
    CONTAINER,
    /** 权限/保护（ProtectionProvider）。 */
    PROTECTION,
    /** 经济后端（EconomyBackend）。 */
    ECONOMY
}
