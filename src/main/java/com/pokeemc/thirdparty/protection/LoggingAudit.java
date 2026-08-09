package com.pokeemc.thirdparty.protection;

import com.mojang.logging.LogUtils;
import com.poketrade.api.permission.ProtectionContext;
import org.slf4j.Logger;

/** 默认审计：DENY 记 info、ALLOW 记 debug、Provider 异常记 error。 */
public final class LoggingAudit implements ProtectionAudit {

    public static final LoggingAudit INSTANCE = new LoggingAudit();

    private static final Logger LOGGER = LogUtils.getLogger();

    private LoggingAudit() {
    }

    @Override
    public void onDenied(String modId, ProtectionContext context) {
        LOGGER.info("[thirdparty] {} DENY {} {} for {}", modId, context.action(),
                context.storageId(), context.actorId());
    }

    @Override
    public void onAllowed(String modId, ProtectionContext context) {
        LOGGER.debug("[thirdparty] {} ALLOW {} {}", modId, context.action(),
                context.storageId());
    }

    @Override
    public void onProviderError(String modId, ProtectionContext context, RuntimeException error) {
        LOGGER.error("[thirdparty] provider {} errored on {}: {}", modId,
                context.storageId(), error.toString());
    }
}
