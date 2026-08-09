package com.pokeemc.trade.asset;

import java.util.Optional;
import java.util.UUID;

/**
 * 宝可梦存储端口（Task 5）：抽象 Party/PC 存储，让 {@link PokemonEscrowGateway}
 * 可在 JVM 单测驱动。Pixelmon 桥接适配器（{@code PlayerPartyStorage}/{@code PCStorage}）
 * 在能力服务接线时实现。
 * <p>
 * 容量规则：队伍容量固定 {@link #partyCapacity()}；PC box/slot 边界
 * 从实际 {@code PCStorage#getBoxCount()} / {@code PCBox#maxSize()} 获取，不硬编码。
 */
public interface PokemonStoragePort {

    /** 队伍容量（固定 6） */
    int partyCapacity();

    /** PC 箱数量 */
    int boxCount();

    /** 指定 PC 箱容量（box 越界返回 0） */
    int boxCapacity(int box);

    /** 队伍中可用（非忙碌）宝可梦数量 —— 用于「最后一只可用宝可梦」规则 */
    int usablePartyCount();

    /** 按位置读取宝可梦；位置为空返回 empty */
    Optional<StoredPokemon> at(PokemonLocation location);

    /** 按 UUID 全存储扫描（交付前去重 / 重复托管检查） */
    Optional<PokemonLocation> locate(UUID pokemonId);

    /**
     * 原子移除指定位置宝可梦并返回其快照；位置为空返回 empty。
     * 移除成功后该位置必须为空（{@link #at} 返回 empty）。
     */
    Optional<StoredPokemon> remove(PokemonLocation location);

    /** 放置宝可梦到指定位置；目标位置非空或越界返回 false */
    boolean place(PokemonLocation location, StoredPokemon pokemon);
}
