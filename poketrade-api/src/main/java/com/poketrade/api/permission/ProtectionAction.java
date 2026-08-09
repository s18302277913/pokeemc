package com.poketrade.api.permission;

/**
 * 需要第三方保护判定的动作。
 *
 * <p>仅有人类行为者的动作才会进入保护链（MOVE 预留给未来适配器，当前阶段
 * 不集成活塞/爆炸等无行为者的场景）。</p>
 */
public enum ProtectionAction {
    OPEN, VIEW, DEPOSIT, WITHDRAW, BREAK, MOVE
}
