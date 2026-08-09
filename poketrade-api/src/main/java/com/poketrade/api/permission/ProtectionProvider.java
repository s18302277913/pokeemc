package com.poketrade.api.permission;

import com.poketrade.api.PokeTradeApi;

import java.util.Set;

/**
 * 第三方保护提供者：对仓储动作给出 DENY/ALLOW/NOT_APPLICABLE。
 *
 * <p>实现方在服务端启动阶段（本模组初始化后）通过
 * {@link ProtectionRegistry#register(ProtectionProvider)} 注册。注册时校验
 * {@link #apiVersion()} 与 {@link PokeTradeApi#API_VERSION} 一致。</p>
 */
public interface ProtectionProvider {

    /** 所属模组稳定 ID（注册冲突判重键）。 */
    String modId();

    /** 本 Provider 可处理的能力类别。 */
    Set<ProtectionCapability> capabilities();

    /** 声明的 API 版本；注册时须与 {@link PokeTradeApi#API_VERSION} 一致。 */
    default int apiVersion() {
        return PokeTradeApi.API_VERSION;
    }

    /**
     * 检查上下文。
     *
     * <p>实现不得抛异常；若异常发生，调用方捕获后按
     * {@link ProtectionResult#NOT_APPLICABLE} 继续。</p>
     */
    ProtectionResult check(ProtectionContext context);
}
