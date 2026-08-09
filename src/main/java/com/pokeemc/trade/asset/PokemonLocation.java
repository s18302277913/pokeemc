package com.pokeemc.trade.asset;

/**
 * 宝可梦存储位置（Task 5）：队伍使用 {@code box = -1}、{@code slot 0..5}；
 * PC 使用非负 box 与箱内 slot。位置信息由服务端权威提供，不硬编码客户端值。
 */
public record PokemonLocation(int box, int slot) {

    /** 队伍位置（box = -1） */
    public static PokemonLocation party(int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("party slot cannot be negative");
        }
        return new PokemonLocation(-1, slot);
    }

    /** PC 位置 */
    public static PokemonLocation pc(int box, int slot) {
        if (box < 0 || slot < 0) {
            throw new IllegalArgumentException("pc box/slot cannot be negative");
        }
        return new PokemonLocation(box, slot);
    }

    /** 是否队伍位置 */
    public boolean isParty() {
        return box == -1;
    }

    public PokemonLocation {
        if (box < -1) {
            throw new IllegalArgumentException("box cannot be less than -1");
        }
        if (slot < 0) {
            throw new IllegalArgumentException("slot cannot be negative");
        }
        if (box == -1 && slot > 5) {
            throw new IllegalArgumentException("party slot out of range 0..5");
        }
    }
}
