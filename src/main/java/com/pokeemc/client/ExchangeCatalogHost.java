package com.pokeemc.client;

import com.pokeemc.network.ExchangeCatalogPacket;

/** 屏幕实现此接口以接收服务端目录响应（仿 BrowserHost 模式）。 */
public interface ExchangeCatalogHost {

    /** 收到目录响应（客户端线程回调）。 */
    void onCatalogResponse(ExchangeCatalogPacket.Response packet);

    /**
     * 服务端目录已变更（CatalogChangedPacket，会话 #16）：实现方应在此重新拉取目录。
     * 默认空实现，避免破坏其他实现者。
     */
    default void onCatalogChanged(long catalogVersion) {
        // 默认忽略；需要响应目录变更的屏幕（交易所）覆写
    }
}
