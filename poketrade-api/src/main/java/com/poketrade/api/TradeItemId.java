package com.poketrade.api;

import java.util.Objects;
import java.util.regex.Pattern;

public record TradeItemId(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    // [CHANGED] 会话 #14：PATH 正则加入 '#' —— Pixelmon 球类 itemId 编码为
    // "pixelmon:poke_ball#<球种>"（球种由 PokeBall DataComponent 区分，注册表只有
    // pixelmon:poke_ball 一个键）。此前 '#' 非法，球种感知键无法解析，覆盖价表
    // /买卖/目录全链路拒绝球类键（大师球显示「暂无定价」）。'#' 非法于
    // ResourceLocation path，天然与普通物品 id 隔离。旧 id 全部仍合法（向后兼容）。
    private static final Pattern PATH = Pattern.compile("[a-z0-9/._#-]+");

    public TradeItemId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid trade item id: " + namespace + ":" + path);
        }
    }

    public static TradeItemId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Invalid trade item id: " + value);
        }
        return new TradeItemId(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
