package com.pokeemc.trade.asset;

import com.pixelmonmod.pixelmon.api.pokemon.Pokemon;
import com.pixelmonmod.pixelmon.api.pokemon.PokemonBuilder;
import com.pixelmonmod.pixelmon.api.storage.PCBox;
import com.pixelmonmod.pixelmon.api.storage.PCStorage;
import com.pixelmonmod.pixelmon.api.storage.PlayerPartyStorage;
import com.pixelmonmod.pixelmon.api.storage.StoragePosition;
import com.pixelmonmod.pixelmon.api.storage.StorageProxy;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 生产宝可梦存储端口（Task 11 步骤 3）：桥接 Pixelmon {@link StorageProxy}
 * （Party/PC）。未加载 Pixelmon 或玩家离线时降级为空存储，物品/宝可梦交易
 * 仍可用，宝可梦资产交易自然失败。
 * <p>
 * 队伍位置 box = -1（与 {@link PokemonLocation} 约定一致）；容量从实际存储
 * 读取不硬编码。Pixelmon API 具体签名在 Task 13 真实服务器验证。
 */
public final class MinecraftPokemonStoragePort implements PokemonStoragePort {

    private static final int PARTY_CAPACITY = 6;
    private static final boolean PIXELMON_PRESENT = ModList.get().isLoaded("pixelmon");

    private final ServerPlayer player;
    private final HolderLookup.Provider registries;

    private MinecraftPokemonStoragePort(ServerPlayer player) {
        this.player = Objects.requireNonNull(player, "player");
        this.registries = player.registryAccess();
    }

    public static MinecraftPokemonStoragePort of(ServerPlayer player) {
        return new MinecraftPokemonStoragePort(player);
    }

    @Override
    public int partyCapacity() {
        return PARTY_CAPACITY;
    }

    @Override
    public int boxCount() {
        return pixelmon() ? pc().getBoxCount() : 0;
    }

    @Override
    public int boxCapacity(int box) {
        if (!pixelmon() || box < 0 || box >= pc().getBoxCount()) {
            return 0;
        }
        return pc().getBox(box).maxSize();
    }

    @Override
    public int usablePartyCount() {
        if (!pixelmon()) {
            return 0;
        }
        PlayerPartyStorage party = party();
        int count = 0;
        for (int i = 0; i < PARTY_CAPACITY; i++) {
            Pokemon pokemon = party.get(new StoragePosition(-1, i));
            if (pokemon != null && !pokemon.isUntradeable()) {
                count++;
            }
        }
        return count;
    }

    @Override
    public Optional<StoredPokemon> at(PokemonLocation location) {
        if (!pixelmon()) {
            return Optional.empty();
        }
        Pokemon pokemon = read(location);
        return pokemon == null ? Optional.empty() : Optional.of(snapshot(pokemon));
    }

    @Override
    public Optional<PokemonLocation> locate(UUID pokemonId) {
        if (!pixelmon()) {
            return Optional.empty();
        }
        PlayerPartyStorage party = party();
        for (int i = 0; i < PARTY_CAPACITY; i++) {
            Pokemon pokemon = party.get(new StoragePosition(-1, i));
            if (pokemon != null && pokemonId.equals(pokemon.getUUID())) {
                return Optional.of(PokemonLocation.party(i));
            }
        }
        PCStorage pc = pc();
        for (int box = 0; box < pc.getBoxCount(); box++) {
            PCBox pcBox = pc.getBox(box);
            for (int slot = 0; slot < pcBox.maxSize(); slot++) {
                Pokemon pokemon = pcBox.get(slot);
                if (pokemon != null && pokemonId.equals(pokemon.getUUID())) {
                    return Optional.of(PokemonLocation.pc(box, slot));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<StoredPokemon> remove(PokemonLocation location) {
        if (!pixelmon()) {
            return Optional.empty();
        }
        Pokemon pokemon = read(location);
        if (pokemon == null) {
            return Optional.empty();
        }
        set(location, null);
        return Optional.of(snapshot(pokemon));
    }

    @Override
    public boolean place(PokemonLocation location, StoredPokemon stored) {
        if (!pixelmon() || read(location) != null) {
            return false;
        }
        Pokemon built = PokemonBuilder.builder().build();
        built.readFromNBT(stored.nbt(), registries);
        set(location, built);
        return true;
    }

    // -- helpers --

    private boolean pixelmon() {
        return PIXELMON_PRESENT && !player.level().isClientSide;
    }

    private PlayerPartyStorage party() {
        return StorageProxy.getPartyNow(player);
    }

    private PCStorage pc() {
        return StorageProxy.getPCForPlayerNow(player);
    }

    private Pokemon read(PokemonLocation location) {
        if (location.isParty()) {
            return party().get(new StoragePosition(-1, location.slot()));
        }
        PCStorage pc = pc();
        if (location.box() >= pc.getBoxCount()) {
            return null;
        }
        PCBox box = pc.getBox(location.box());
        if (location.slot() >= box.maxSize()) {
            return null;
        }
        return box.get(location.slot());
    }

    private void set(PokemonLocation location, Pokemon pokemon) {
        if (location.isParty()) {
            party().set(new StoragePosition(-1, location.slot()), pokemon);
        } else {
            pc().set(location.box(), location.slot(), pokemon);
        }
    }

    private StoredPokemon snapshot(Pokemon pokemon) {
        CompoundTag nbt = pokemon.writeToNBT(new CompoundTag(), registries);
        return new StoredPokemon(pokemon.getUUID(), nbt, !pokemon.isUntradeable(), false);
    }
}
