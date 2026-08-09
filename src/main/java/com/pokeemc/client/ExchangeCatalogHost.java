package com.pokeemc.client;

import com.pokeemc.network.ExchangeCatalogPacket;

/** 屏幕实现此接口以接收服务端目录响应（仿 BrowserHost 模式）。 */
public interface ExchangeCatalogHost {

    /** 收到目录响应（客户端线程回调）。 */
    void onCatalogResponse(ExchangeCatalogPacket.Response packet);
}
