package com.poketrade.api.permission;

/**
 * 保护 Provider 的检查结果三态。
 */
public enum ProtectionResult {
    /** 拒绝（整体拒绝，写审计）。 */
    DENY,
    /** 放行（短路，不再询问后续 Provider）。 */
    ALLOW,
    /** 不适用（交由后续 Provider 与默认行为）。 */
    NOT_APPLICABLE
}
