package com.pokeemc.thirdparty.protection;

import com.poketrade.api.permission.ProtectionContext;

/**
 * 保护链审计回调：记录 DENY/ALLOW/Provider 异常，来源以 modId 标识。
 */
public interface ProtectionAudit {

    void onDenied(String modId, ProtectionContext context);

    void onAllowed(String modId, ProtectionContext context);

    void onProviderError(String modId, ProtectionContext context, RuntimeException error);
}
