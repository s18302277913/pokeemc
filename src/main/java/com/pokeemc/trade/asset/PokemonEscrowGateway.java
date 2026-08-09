package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeError;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Pixelmon 宝可梦托管 gateway（Task 5，计划 3.4）：三阶段 prepare -> remove -> deliver。
 * 核心逻辑只依赖 {@link PokemonStoragePort} 抽象（Party/PC 存储），可在 JVM 单测驱动。
 *
 * <ul>
 *   <li>{@link #prepare}：校验位置合法、非空、可交易、不忙碌、非最后一只可用宝可梦、未重复托管；</li>
 *   <li>{@link #remove}：重新比较 UUID（防位置变化误扣），原子移除产出 {@link PokemonAsset}；</li>
 *   <li>{@link #deliver}：按收货偏好放入目标存储，UUID 查重（已存在视为已交付），
 *       放不下的部分返回剩余由调用方转收件箱。</li>
 * </ul>
 *
 * <p>「最后一只可用宝可梦」规则：队伍中可用宝可梦数量 {@code <= 1} 时拒绝移出队伍宝可梦
 * （防止玩家被搬空队伍）。位置边界从端口实时获取，不硬编码。</p>
 */
public final class PokemonEscrowGateway {

    private PokemonEscrowGateway() {
    }

    /** 托管前的存储快照 */
    public record PreparedPokemon(PokemonLocation location, StoredPokemon snapshot) {

        public PreparedPokemon {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /** remove 产物：已托管资产 + 移除的快照 */
    public record EscrowedPokemon(PokemonAsset asset, StoredPokemon removed) {

        public EscrowedPokemon {
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(removed, "removed");
        }
    }

    /**
     * 阶段 1：校验并快照宝可梦位置。
     *
     * @param alreadyEscrowed 该宝可梦是否已在其他交易托管（由调用方查 SavedData 传入）
     */
    public static Outcome<PreparedPokemon> prepare(PokemonStoragePort port, PokemonLocation location,
                                                   UUID owner, boolean alreadyEscrowed) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(owner, "owner");
        // 位置越界视为空位
        if (outOfRange(port, location)) {
            return Outcome.fail(TradeError.POKEMON_SLOT_EMPTY);
        }
        Optional<StoredPokemon> current = port.at(location);
        if (current.isEmpty()) {
            return Outcome.fail(TradeError.POKEMON_SLOT_EMPTY);
        }
        StoredPokemon pokemon = current.get();
        if (!pokemon.tradeable()) {
            return Outcome.fail(TradeError.POKEMON_UNTRADEABLE);
        }
        if (pokemon.busy()) {
            return Outcome.fail(TradeError.POKEMON_BUSY);
        }
        // 最后一只可用宝可梦：移出后队伍无可用宝可梦则拒绝
        if (location.isParty() && port.usablePartyCount() <= 1) {
            return Outcome.fail(TradeError.POKEMON_LAST_PARTY);
        }
        if (alreadyEscrowed) {
            return Outcome.fail(TradeError.POKEMON_ALREADY_ESCROWED);
        }
        return Outcome.ok(new PreparedPokemon(location, pokemon));
    }

    /**
     * 阶段 2：原子移除。remove 前重新比较 UUID，防位置变化导致误扣。
     */
    public static Outcome<EscrowedPokemon> remove(PokemonStoragePort port, PreparedPokemon prepared, UUID owner) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(owner, "owner");
        Optional<StoredPokemon> current = port.at(prepared.location());
        if (current.isEmpty()) {
            return Outcome.fail(TradeError.POKEMON_SLOT_EMPTY);
        }
        if (!current.get().pokemonId().equals(prepared.snapshot().pokemonId())) {
            return Outcome.fail(TradeError.POKEMON_MOVED);
        }
        Optional<StoredPokemon> removed = port.remove(prepared.location());
        if (removed.isEmpty()) {
            return Outcome.fail(TradeError.POKEMON_SLOT_EMPTY);
        }
        PokemonAsset asset = new PokemonAsset(
                UUID.randomUUID(),
                owner,
                removed.get().pokemonId(),
                removed.get().nbt(),
                prepared.location().isParty() ? "party" : "pc",
                prepared.location().box(),
                prepared.location().slot());
        return Outcome.ok(new EscrowedPokemon(asset, removed.get()));
    }

    /**
     * 交付：按偏好放入目标存储（AUTO = 队伍优先 → PC 降级）。
     * 交付前扫描目标存储 UUID：已存在视为已交付（幂等，防重复放置）。
     *
     * @return 放置数量与剩余数量；剩余需由调用方转入收件箱
     */
    public static DeliveryResult deliver(PokemonStoragePort port, PokemonAsset asset,
                                         DeliveryPreference.PokemonDestination destination) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(destination, "destination");
        // 幂等：目标存储已存在同 UUID → 视为已交付
        if (port.locate(asset.pokemonId()).isPresent()) {
            return new DeliveryResult(1, 0);
        }
        StoredPokemon pokemon = StoredPokemon.from(asset);
        return switch (destination) {
            case INBOX -> new DeliveryResult(0, 1);
            case PARTY -> placeFirstParty(port, pokemon);
            case PC -> placeFirstPc(port, pokemon);
            case AUTO -> {
                DeliveryResult partyResult = placeFirstParty(port, pokemon);
                yield partyResult.allDelivered()
                        ? partyResult
                        : placeFirstPc(port, pokemon);
            }
        };
    }

    // ------------------------------------------------------------------ 内部

    private static boolean outOfRange(PokemonStoragePort port, PokemonLocation location) {
        if (location.isParty()) {
            return location.slot() >= port.partyCapacity();
        }
        if (location.box() >= port.boxCount()) {
            return true;
        }
        return location.slot() >= port.boxCapacity(location.box());
    }

    private static DeliveryResult placeFirstParty(PokemonStoragePort port, StoredPokemon pokemon) {
        for (int slot = 0; slot < port.partyCapacity(); slot++) {
            if (port.place(PokemonLocation.party(slot), pokemon)) {
                return new DeliveryResult(1, 0);
            }
        }
        return new DeliveryResult(0, 1);
    }

    private static DeliveryResult placeFirstPc(PokemonStoragePort port, StoredPokemon pokemon) {
        for (int box = 0; box < port.boxCount(); box++) {
            int capacity = port.boxCapacity(box);
            for (int slot = 0; slot < capacity; slot++) {
                if (port.place(PokemonLocation.pc(box, slot), pokemon)) {
                    return new DeliveryResult(1, 0);
                }
            }
        }
        return new DeliveryResult(0, 1);
    }
}
