package com.pokeemc.trade.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 一方报价（计划 4.1/4.3）：不可变值对象，变更即创建新实例并由状态机
 * 递增 revision、清空双方确认。条目上限见 {@link #MAX_ITEMS}/{@link #MAX_PKM_ENTRIES}/{@link #MAX_POKEMON}。
 */
public final class TradeOffer {

    /** 单方物品栈上限（对应 27 格购物车） */
    public static final int MAX_ITEMS = 27;

    /** 单方 PKM 条目上限（计划 5.1 预算） */
    public static final int MAX_PKM_ENTRIES = 1;

    /** 单方宝可梦上限（计划 5.1 预算） */
    public static final int MAX_POKEMON = 6;

    private final List<ItemAsset> items;
    private final List<PkmAsset> pkm;
    private final List<PokemonAsset> pokemon;

    private TradeOffer(List<ItemAsset> items, List<PkmAsset> pkm, List<PokemonAsset> pokemon) {
        this.items = List.copyOf(items);
        this.pkm = List.copyOf(pkm);
        this.pokemon = List.copyOf(pokemon);
    }

    public static TradeOffer empty() {
        return new TradeOffer(List.of(), List.of(), List.of());
    }

    public List<ItemAsset> items() {
        return items;
    }

    public List<PkmAsset> pkm() {
        return pkm;
    }

    public List<PokemonAsset> pokemon() {
        return pokemon;
    }

    /** 本报价是否为空 */
    public boolean isEmpty() {
        return items.isEmpty() && pkm.isEmpty() && pokemon.isEmpty();
    }

    /** 本报价内所有资产（按 ITEM -> PKM -> POKEMON 顺序，即锁顺序） */
    public List<TradeAsset> allAssets() {
        List<TradeAsset> out = new ArrayList<>(items.size() + pkm.size() + pokemon.size());
        out.addAll(items);
        out.addAll(pkm);
        out.addAll(pokemon);
        return Collections.unmodifiableList(out);
    }

    public Optional<TradeAsset> find(UUID assetId) {
        for (TradeAsset a : allAssets()) {
            if (a.assetId().equals(assetId)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** 新增条目；超限或重复 assetId 时抛出 IllegalArgumentException（由调用方转为稳定错误码） */
    public TradeOffer withAdded(TradeAsset asset) {
        Objects.requireNonNull(asset, "asset");
        if (find(asset.assetId()).isPresent()) {
            throw new IllegalArgumentException("duplicate assetId: " + asset.assetId());
        }
        List<ItemAsset> ni = new ArrayList<>(items);
        List<PkmAsset> np = new ArrayList<>(pkm);
        List<PokemonAsset> nk = new ArrayList<>(pokemon);
        if (asset instanceof ItemAsset ia) {
            if (ni.size() >= MAX_ITEMS) {
                throw new IllegalArgumentException("item offer limit reached");
            }
            ni.add(ia);
        } else if (asset instanceof PkmAsset pa) {
            if (np.size() >= MAX_PKM_ENTRIES) {
                throw new IllegalArgumentException("pkm entry limit reached");
            }
            np.add(pa);
        } else if (asset instanceof PokemonAsset ka) {
            if (nk.size() >= MAX_POKEMON) {
                throw new IllegalArgumentException("pokemon offer limit reached");
            }
            nk.add(ka);
        } else {
            throw new IllegalArgumentException("unknown asset type: " + asset.getClass());
        }
        return new TradeOffer(ni, np, nk);
    }

    /** 移除条目；不存在时返回自身 */
    public TradeOffer without(UUID assetId) {
        if (find(assetId).isEmpty()) {
            return this;
        }
        List<ItemAsset> ni = new ArrayList<>(items);
        List<PkmAsset> np = new ArrayList<>(pkm);
        List<PokemonAsset> nk = new ArrayList<>(pokemon);
        ni.removeIf(a -> a.assetId().equals(assetId));
        np.removeIf(a -> a.assetId().equals(assetId));
        nk.removeIf(a -> a.assetId().equals(assetId));
        return new TradeOffer(ni, np, nk);
    }

    /** 计算本报价 PKM 总额（long 精确加法，溢出抛 ArithmeticException） */
    public long totalPkm() {
        long sum = 0;
        for (PkmAsset a : pkm) {
            sum = Math.addExact(sum, a.amount());
        }
        return sum;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TradeOffer that)) {
            return false;
        }
        return items.equals(that.items) && pkm.equals(that.pkm) && pokemon.equals(that.pokemon);
    }

    @Override
    public int hashCode() {
        return Objects.hash(items, pkm, pokemon);
    }
}
