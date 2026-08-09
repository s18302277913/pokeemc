package com.pokeemc.trade.service;

import com.pokeemc.trade.asset.DeliveryResult;
import com.pokeemc.trade.asset.Outcome;
import com.pokeemc.trade.asset.PokemonEscrowGateway;
import com.pokeemc.trade.asset.PokemonLocation;
import com.pokeemc.trade.asset.PokemonStoragePort;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PokemonAsset;

import java.util.UUID;

/**
 * 宝可梦托管端口（Task 6）：TradeService 依赖的托管抽象，测试注入 fake。
 */
public interface PokemonEscrowPort {

    Outcome<PokemonEscrowGateway.PreparedPokemon> prepare(PokemonStoragePort port, PokemonLocation location,
                                                          UUID owner, boolean alreadyEscrowed);

    Outcome<PokemonEscrowGateway.EscrowedPokemon> remove(PokemonStoragePort port,
                                                         PokemonEscrowGateway.PreparedPokemon prepared, UUID owner);

    DeliveryResult deliver(PokemonStoragePort port, PokemonAsset asset,
                           DeliveryPreference.PokemonDestination destination);
}
