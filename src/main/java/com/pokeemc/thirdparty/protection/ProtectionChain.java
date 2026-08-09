package com.pokeemc.thirdparty.protection;

import com.poketrade.api.permission.ProtectionContext;
import com.poketrade.api.permission.ProtectionProvider;
import com.poketrade.api.permission.ProtectionResult;

import java.util.List;
import java.util.Objects;

/**
 * 保护链串联器（AND 语义）：按注册顺序逐一询问保护 Provider。
 *
 * <ul>
 *   <li>任一 Provider 返回 {@link ProtectionResult#DENY} 即整体拒绝并写审计；</li>
 *   <li>任一返回 {@link ProtectionResult#ALLOW} 即短路放行；</li>
 *   <li>全部 NOT_APPLICABLE 或空表安全通过；</li>
 *   <li>Provider 抛异常按 NOT_APPLICABLE 容错（第三方缺陷不阻断交易）。</li>
 * </ul>
 */
public final class ProtectionChain {

    private final List<ProtectionProvider> providers;
    private final ProtectionAudit audit;

    public ProtectionChain(List<ProtectionProvider> providers, ProtectionAudit audit) {
        this.providers = List.copyOf(providers);
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public boolean allows(ProtectionContext context) {
        for (ProtectionProvider provider : providers) {
            ProtectionResult result;
            try {
                result = provider.check(context);
            } catch (RuntimeException e) {
                audit.onProviderError(provider.modId(), context, e);
                result = ProtectionResult.NOT_APPLICABLE;
            }
            if (result == ProtectionResult.DENY) {
                audit.onDenied(provider.modId(), context);
                return false;
            }
            if (result == ProtectionResult.ALLOW) {
                audit.onAllowed(provider.modId(), context);
                return true;
            }
        }
        return true;
    }
}
