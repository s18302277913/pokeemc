package com.pokeemc.client;

import com.pokeemc.network.ExchangeCatalogPacket;

/**
 * 最近一次交易所目录响应的客户端缓存（会话 #16：无屏时在途响应不再被丢弃，
 * 交易所屏打开即可立即消费最新已知目录，随后由新鲜请求覆盖）。
 * <p>登出/断线时随客户端进程自然释放（static volatile 无持久化）。</p>
 */
public final class ClientCatalogCache {

    /** 最近一次收到的目录响应（可为 null = 尚未收到）。 */
    public static volatile ExchangeCatalogPacket.Response latest;

    private ClientCatalogCache() {
    }

    /** 清空缓存（登出/断开连接时调用，避免跨服务器误用旧目录）。 */
    public static void clear() {
        latest = null;
    }
}
