package com.pokeemc.storage;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 逻辑仓储的游戏内稳定键：维度、适配器 {@code typeId} 与规范化位置的组合。
 *
 * <p>与公共 API 的 {@code com.poketrade.api.storage.StorageId} 结构一致，
 * 但位于实现层、不依赖任何游戏类型，可安全用作 {@code Map} 键并持久化。
 * 维度为资源风格 ID（如 {@code minecraft:overworld}），适配器类型为适配器
 * 声明的 {@code typeId}（如 {@code vanilla_chest}），位置为适配器提供的
 * 规范化字符串（如 {@code 0;64;0}）。</p>
 *
 * <p>加载存档条目时：格式非法的键、未注册的维度或未知适配器 ID 必须跳过
 * 该条，不得回退到主世界或改写位置。</p>
 */
public record StorageKey(String dimension, String adapterType, String location) {

    private static final Pattern DIMENSION =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern ADAPTER_TYPE =
            Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern LOCATION =
            Pattern.compile("[A-Za-z0-9_.,;:=-]+");

    private static final char SEPARATOR = '|';

    public StorageKey {
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
     * 构造并规范化位置（去除首尾空白后校验字符集）。
     */
    public static StorageKey of(String dimension, String adapterType, String location) {
        return new StorageKey(dimension, adapterType, location.trim());
    }

    /**
     * 解析 {@link #asString()} 生成的字符串；格式非法返回 {@code empty}，
     * 供存档加载跳过损坏条目，不抛异常。
     */
    public static Optional<StorageKey> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split(Pattern.quote(String.valueOf(SEPARATOR)), -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StorageKey(parts[0], parts[1], parts[2]));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
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
