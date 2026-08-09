package com.poketrade.api.economy;

import java.util.Optional;

/**
 * 经济后端注册表。
 *
 * <p>重复 {@code backendId} 必须被拒绝；未注册时 {@link #activeBackend()}
 * 返回空，调用方使用内置 Pixelmon 兜底。</p>
 */
public interface EconomyRegistry {

    /**
     * 注册经济后端。
     *
     * @throws IllegalArgumentException 若 {@code backendId} 已注册，或
     *                                  {@code apiVersion()} 与当前 API 版本不一致
     */
    void register(EconomyBackend backend);

    /** 当前活动后端；未注册时返回空（调用方兜底 Pixelmon）。 */
    Optional<EconomyBackend> activeBackend();
}
