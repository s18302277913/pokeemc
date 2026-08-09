package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.PokemonLocation;

/**
 * 宝可梦报价定位（Task 6）：客户端只携带存储种类与位置，
 * 服务端权威读取存储。{@code storageKind} 为 {@code party}/{@code pc}。
 */
public record PokemonLocator(String storageKind, int box, int slot) {

    public PokemonLocator {
        if (storageKind == null || (!storageKind.equals("party") && !storageKind.equals("pc"))) {
            throw new IllegalArgumentException("storageKind must be party or pc");
        }
        if (storageKind.equals("party") && box != -1) {
            throw new IllegalArgumentException("party uses box=-1");
        }
    }

    public static PokemonLocator party(int slot) {
        return new PokemonLocator("party", -1, slot);
    }

    public static PokemonLocator pc(int box, int slot) {
        return new PokemonLocator("pc", box, slot);
    }

    /** 转换为服务端存储位置 */
    public PokemonLocation toLocation() {
        return storageKind.equals("party") ? PokemonLocation.party(slot) : PokemonLocation.pc(box, slot);
    }
}
