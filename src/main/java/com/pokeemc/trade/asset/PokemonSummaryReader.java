package com.pokeemc.trade.asset;

import net.minecraft.nbt.CompoundTag;

/**
 * 宝可梦 NBT 展示摘要读取器（Task 8，计划 2.4）。
 * <p>
 * 只读取种类/形态/等级/闪光/昵称等展示字段，绝不透出招式、个体值、努力值、
 * 原训练家等隐私字段。Pixelmon 序列化键名在不同版本可能不同，所有读取都是
 * 容错的：键缺失时返回稳定默认值，不抛异常（JVM 测试可直接构造 NBT 驱动）。
 */
public final class PokemonSummaryReader {

    private PokemonSummaryReader() {
    }

    /** 种类名（如 "Pikachu"）；未知返回 "unknown" */
    public static String species(CompoundTag nbt) {
        return nbt.contains("Species", net.minecraft.nbt.Tag.TAG_STRING)
                ? nbt.getString("Species")
                : "unknown";
    }

    /** 形态；无形态返回空串 */
    public static String form(CompoundTag nbt) {
        return nbt.contains("Form", net.minecraft.nbt.Tag.TAG_STRING)
                ? nbt.getString("Form")
                : "";
    }

    /** 等级；缺失返回 0 */
    public static int level(CompoundTag nbt) {
        return nbt.contains("Level", net.minecraft.nbt.Tag.TAG_INT)
                ? nbt.getInt("Level")
                : 0;
    }

    /** 是否闪光；缺失返回 false */
    public static boolean shiny(CompoundTag nbt) {
        if (nbt.contains("Shiny", net.minecraft.nbt.Tag.TAG_BYTE)) {
            return nbt.getByte("Shiny") != 0;
        }
        if (nbt.contains("Shiny", net.minecraft.nbt.Tag.TAG_INT)) {
            return nbt.getInt("Shiny") != 0;
        }
        return false;
    }

    /** 昵称；未命名返回空串 */
    public static String nickname(CompoundTag nbt) {
        return nbt.contains("Nickname", net.minecraft.nbt.Tag.TAG_STRING)
                ? nbt.getString("Nickname")
                : "";
    }
}
