package com.poketrade.api.storage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 逻辑仓储的稳定标识：维度、适配器类型与规范化位置的组合。
 *
 * <p>该类型位于公共 API，使用稳定字符串，不依赖 Minecraft/NeoForge 类型。
 * 维度使用资源风格 ID（如 {@code minecraft:overworld}），适配器类型使用
 * 适配器声明的 {@code typeId}（如 {@code vanilla_chest}），位置是适配器
 * 提供的规范化字符串（如 {@code 0;64;0}）。</p>
 */
public record StorageId(String dimension, String adapterType, String location) {

    private static final Pattern DIMENSION =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern ADAPTER_TYPE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern LOCATION =
            Pattern.compile("[A-Za-z0-9_.,;:=-]+");

    private static final char SEPARATOR = '|';

    public StorageId {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(adapterType, "adapterType");
        Objects.requireNonNull(location, "location");
        if (!DIMENSION.matcher(dimension).matches()) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
        if (!ADAPTER_TYPE.matcher(adapterType).matches()) {
            throw new IllegalArgumentException("Invalid adapter type: " + adapterType);
        }
        if (!LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("Invalid location: " + location);
        }
    }

    /**
     * 解析 {@link #asString()} 生成的稳定字符串。
     */
    public static StorageId parse(String value) {
        Objects.requireNonNull(value, "value");
        String[] parts = value.split(Pattern.quote(String.valueOf(SEPARATOR)), -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid storage id: " + value);
        }
        return new StorageId(parts[0], parts[1], parts[2]);
    }

    /**
     * 返回可持久化、可传输的稳定字符串表示。
     */
    public String asString() {
        return dimension + SEPARATOR + adapterType + SEPARATOR + location;
    }

    @Override
    public String toString() {
        return asString();
    }
}
