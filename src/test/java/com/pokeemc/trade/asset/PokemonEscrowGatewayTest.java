package com.pokeemc.trade.asset;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeError;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5：Pixelmon 宝可梦托管 gateway 测试（计划 3.4 / Task 5 步骤 1）。
 * 覆盖 Party/PC 定位、空位、位置变化、不可交易、参战/放出、最后一只可用宝可梦、
 * 重复 UUID、目标队伍满后降级 PC、PC 也满进入收件箱、交付 UUID 查重幂等。
 */
class PokemonEscrowGatewayTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PKM_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PKM_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    // ------------------------------------------------------------------ prepare

    @Test
    void partyLocationPrepareSucceeds() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        var out = PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false);
        assertTrue(out.ok());
        assertEquals(PKM_A, out.value().snapshot().pokemonId());
    }

    @Test
    void pcLocationPrepareSucceeds() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.pc(1, 2), poke(PKM_A));
        var out = PokemonEscrowGateway.prepare(port, PokemonLocation.pc(1, 2), OWNER, false);
        assertTrue(out.ok());
        assertEquals(PKM_A, out.value().snapshot().pokemonId());
    }

    @Test
    void emptySlotRejected() {
        FakePort port = new FakePort();
        assertEquals(TradeError.POKEMON_SLOT_EMPTY,
                PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false).error());
    }

    @Test
    void outOfRangeLocationRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        assertEquals(TradeError.POKEMON_SLOT_EMPTY,
                PokemonEscrowGateway.prepare(port, PokemonLocation.party(5), OWNER, false).error());
        assertEquals(TradeError.POKEMON_SLOT_EMPTY,
                PokemonEscrowGateway.prepare(port, PokemonLocation.pc(2, 0), OWNER, false).error()); // box 越界
        assertEquals(TradeError.POKEMON_SLOT_EMPTY,
                PokemonEscrowGateway.prepare(port, PokemonLocation.pc(0, 3), OWNER, false).error()); // slot 越界
    }

    @Test
    void untradeablePokemonRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A, false, false));
        assertEquals(TradeError.POKEMON_UNTRADEABLE,
                PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false).error());
    }

    @Test
    void busyPokemonRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A, true, true));
        assertEquals(TradeError.POKEMON_BUSY,
                PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false).error());
    }

    @Test
    void lastUsablePartyPokemonRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        port.usablePartyCount = 1;
        assertEquals(TradeError.POKEMON_LAST_PARTY,
                PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false).error());
    }

    @Test
    void lastUsableRuleOnlyAppliesToParty() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.pc(0, 0), poke(PKM_A));
        port.usablePartyCount = 1; // 队伍只剩 1 只，但目标在 PC —— 不受限
        assertTrue(PokemonEscrowGateway.prepare(port, PokemonLocation.pc(0, 0), OWNER, false).ok());
    }

    @Test
    void alreadyEscrowedRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        assertEquals(TradeError.POKEMON_ALREADY_ESCROWED,
                PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, true).error());
    }

    // ------------------------------------------------------------------ remove

    @Test
    void removeProducesAssetAndClearsSlot() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.pc(1, 1), poke(PKM_A));
        var prep = PokemonEscrowGateway.prepare(port, PokemonLocation.pc(1, 1), OWNER, false);
        assertTrue(prep.ok());

        var removed = PokemonEscrowGateway.remove(port, prep.value(), OWNER);
        assertTrue(removed.ok());
        PokemonAsset asset = removed.value().asset();
        assertEquals(PKM_A, asset.pokemonId());
        assertEquals("pc", asset.sourceStorage());
        assertEquals(1, asset.sourceBox());
        assertEquals(1, asset.sourceSlot());
        assertTrue(port.at(PokemonLocation.pc(1, 1)).isEmpty());
    }

    @Test
    void removeMovedPokemonRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        var prep = PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false);
        assertTrue(prep.ok());
        // 位置内容变化（换成另一只）
        port.put(PokemonLocation.party(0), poke(PKM_B));
        assertEquals(TradeError.POKEMON_MOVED,
                PokemonEscrowGateway.remove(port, prep.value(), OWNER).error());
    }

    @Test
    void removeEmptySlotRejected() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        var prep = PokemonEscrowGateway.prepare(port, PokemonLocation.party(0), OWNER, false);
        assertTrue(prep.ok());
        port.storage.clear();
        assertEquals(TradeError.POKEMON_SLOT_EMPTY,
                PokemonEscrowGateway.remove(port, prep.value(), OWNER).error());
    }

    // ------------------------------------------------------------------ deliver

    @Test
    void autoDeliverFillsPartyFirst() {
        FakePort port = new FakePort();
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.AUTO);
        assertTrue(result.allDelivered());
        assertTrue(port.locate(PKM_A).isPresent());
        assertEquals(PokemonLocation.party(0), port.locate(PKM_A).orElseThrow());
    }

    @Test
    void autoDeliverDegradesToPcWhenPartyFull() {
        FakePort port = new FakePort();
        for (int i = 0; i < 6; i++) {
            port.put(PokemonLocation.party(i), poke(UUID.randomUUID()));
        }
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.AUTO);
        assertTrue(result.allDelivered());
        assertEquals(PokemonLocation.pc(0, 0), port.locate(PKM_A).orElseThrow());
    }

    @Test
    void pcDestinationOnlyFillsPc() {
        FakePort port = new FakePort();
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.PC);
        assertTrue(result.allDelivered());
        assertEquals(PokemonLocation.pc(0, 0), port.locate(PKM_A).orElseThrow());
    }

    @Test
    void partyDestinationOnlyFillsParty() {
        FakePort port = new FakePort();
        for (int i = 0; i < 6; i++) {
            port.put(PokemonLocation.party(i), poke(UUID.randomUUID()));
        }
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.PARTY);
        assertFalse(result.allDelivered()); // 队伍满，PC 不入 → 剩余进收件箱
        assertEquals(0, result.placed());
        assertEquals(1, result.remaining());
    }

    @Test
    void inboxDestinationLeavesRemaining() {
        FakePort port = new FakePort();
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.INBOX);
        assertEquals(new DeliveryResult(0, 1), result);
        assertTrue(port.locate(PKM_A).isEmpty());
    }

    @Test
    void allFullLeavesRemainingToInbox() {
        FakePort port = new FakePort();
        for (int i = 0; i < 6; i++) {
            port.put(PokemonLocation.party(i), poke(UUID.randomUUID()));
        }
        for (int box = 0; box < port.boxCount; box++) {
            for (int slot = 0; slot < port.boxCapacity; slot++) {
                port.put(PokemonLocation.pc(box, slot), poke(UUID.randomUUID()));
            }
        }
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.AUTO);
        assertEquals(new DeliveryResult(0, 1), result);
    }

    @Test
    void deliverIsIdempotentByUuid() {
        FakePort port = new FakePort();
        port.put(PokemonLocation.party(0), poke(PKM_A));
        var result = deliver(port, PKM_A, DeliveryPreference.PokemonDestination.AUTO);
        // 目标存储已存在同 UUID → 视为已交付，不重复放置
        assertEquals(new DeliveryResult(1, 0), result);
        assertEquals(0, port.placeCalls);
    }

    // ------------------------------------------------------------------ helpers

    private static DeliveryResult deliver(FakePort port, UUID pokemonId,
                                          DeliveryPreference.PokemonDestination dest) {
        PokemonAsset asset = new PokemonAsset(
                UUID.randomUUID(), OWNER, pokemonId, new CompoundTag(), "party", -1, 0);
        return PokemonEscrowGateway.deliver(port, asset, dest);
    }

    private static StoredPokemon poke(UUID id) {
        return poke(id, true, false);
    }

    private static StoredPokemon poke(UUID id, boolean tradeable, boolean busy) {
        return new StoredPokemon(id, new CompoundTag(), tradeable, busy);
    }

    // ------------------------------------------------------------------ fake

    private static final class FakePort implements PokemonStoragePort {
        final Map<PokemonLocation, StoredPokemon> storage = new HashMap<>();
        int partyCapacity = 6;
        int boxCount = 2;
        int boxCapacity = 3;
        int usablePartyCount = 6;
        int placeCalls;

        void put(PokemonLocation loc, StoredPokemon pokemon) {
            storage.put(loc, pokemon);
        }

        @Override
        public int partyCapacity() {
            return partyCapacity;
        }

        @Override
        public int boxCount() {
            return boxCount;
        }

        @Override
        public int boxCapacity(int box) {
            return box < 0 || box >= boxCount ? 0 : boxCapacity;
        }

        @Override
        public int usablePartyCount() {
            return usablePartyCount;
        }

        @Override
        public Optional<StoredPokemon> at(PokemonLocation location) {
            return Optional.ofNullable(storage.get(location));
        }

        @Override
        public Optional<PokemonLocation> locate(UUID pokemonId) {
            return storage.entrySet().stream()
                    .filter(e -> e.getValue().pokemonId().equals(pokemonId))
                    .map(Map.Entry::getKey)
                    .findFirst();
        }

        @Override
        public Optional<StoredPokemon> remove(PokemonLocation location) {
            return Optional.ofNullable(storage.remove(location));
        }

        @Override
        public boolean place(PokemonLocation location, StoredPokemon pokemon) {
            placeCalls++;
            if (location.isParty() ? location.slot() >= partyCapacity
                    : (location.box() >= boxCount || location.slot() >= boxCapacity(location.box()))) {
                return false;
            }
            if (storage.containsKey(location)) {
                return false;
            }
            storage.put(location, pokemon);
            return true;
        }
    }
}
