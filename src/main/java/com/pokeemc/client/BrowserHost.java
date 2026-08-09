package com.pokeemc.client;

import com.pokeemc.network.QueryStoragesPacket;
import com.pokeemc.network.StorageManagePacket;
import com.pokeemc.network.StorageDepositPacket;
import com.pokeemc.network.StorageMovePacket;
import com.pokeemc.network.StorageSnapshotPacket;

/**
 * 仓储浏览器宿主接口（Task 9）。
 *
 * <p>三个服务端响应的 {@code handleResponse} 在客户端线程把数据投递给当前屏幕
 * （{@code Minecraft.getInstance().screen instanceof BrowserHost}）。实现方
 * （{@link StorageBrowserScreen}）把数据写入 {@link StorageViewModel} 并触发重绘。</p>
 */
public interface BrowserHost {

    /** 收到仓储列表查询结果（描述 + 六项权限掩码）。 */
    void onQueryResponse(QueryStoragesPacket.Response response);

    /** 收到选中仓储的槽位快照（revision + 槽位表 + 是否有 VIEW）。 */
    void onSnapshotResponse(StorageSnapshotPacket.Response response);

    /** 收到管理详情或写入结果（ACL/模板/自动化/审计/冲突码）。 */
    void onManageResponse(StorageManagePacket.Response response);

    /** 收到一键存入回执（默认忽略；交易所/仓储浏览器各自刷新快照）。 */
    default void onDepositResponse(StorageDepositPacket.Response response) {
    }

    /** 收到移动/转移回执（默认忽略）。 */
    default void onMoveResponse(StorageMovePacket.Response response) {
    }
}
