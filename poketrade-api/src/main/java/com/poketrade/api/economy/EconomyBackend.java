package com.poketrade.api.economy;

import com.poketrade.api.PokeTradeApi;

import java.util.Optional;
import java.util.UUID;

/**
 * 经济后端：把玩家 UUID 解析为可操作的账户。
 *
 * <p>主模组按 {@link EconomyRegistry#activeBackend()} 选择后端；未注册时
 * 使用内置 Pixelmon 兜底实现。</p>
 */
public interface EconomyBackend {

    /** 后端稳定 ID（注册冲突判重键），如 pixelmon、vault。 */
    String backendId();

    /** 声明的 API 版本；注册时须与 {@link PokeTradeApi#API_VERSION} 一致。 */
    default int apiVersion() {
        return PokeTradeApi.API_VERSION;
    }

    /** 解析玩家账户；无实现或玩家离线时返回 empty。 */
    Optional<EconomyAccount> account(UUID playerId);
}
