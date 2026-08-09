package com.poketrade.api.capability;

import java.util.List;

/**
 * 服务端能力探测：把已注册的适配器/Provider/后端与未适配第三方模组
 * 汇总为快照，供 {@code /poketrade capability} 命令与第三方工具查询。
 */
public interface CapabilityProbe {

    /** 当前 API 版本。 */
    int apiVersion();

    /** 已注册保护 Provider 摘要。 */
    List<CapabilityEntry> protectionProviders();

    /** 已注册经济后端摘要。 */
    List<CapabilityEntry> economyBackends();

    /** 已注册仓储适配器摘要（含内置适配器）。 */
    List<CapabilityEntry> storageAdapters();

    /** 已加载但未注册适配器的第三方模组（启动探测结果）。 */
    List<String> unadaptedMods();
}
