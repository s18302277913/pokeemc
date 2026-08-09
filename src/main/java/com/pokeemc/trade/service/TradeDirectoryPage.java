package com.pokeemc.trade.service;

import com.pokeemc.trade.model.TradeCapability;

import java.util.List;
import java.util.UUID;

/**
 * 在线玩家目录分页结果（计划 5.2）。
 * 目录只列出当前在线且未隐藏交易入口的玩家，不返回任何资产统计。
 */
public record TradeDirectoryPage(
        List<DirectoryEntry> entries,
        int total,
        int page,
        int pageSize
) {

    public TradeDirectoryPage {
        if (entries == null) {
            throw new IllegalArgumentException("entries cannot be null");
        }
    }

    /**
     * 目录条目：UUID、当前公开名称、能力枚举。
     * 能力状态是短期提示，邀请与结算时必须重新校验（计划 2.5）。
     */
    public record DirectoryEntry(UUID playerId, String displayName, TradeCapability capability) {

        public DirectoryEntry {
            if (playerId == null) {
                throw new IllegalArgumentException("playerId cannot be null");
            }
            if (displayName == null) {
                throw new IllegalArgumentException("displayName cannot be null");
            }
            if (capability == null) {
                throw new IllegalArgumentException("capability cannot be null");
            }
        }
    }
}
