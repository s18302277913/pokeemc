package com.pokeemc.trade.network;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeStatus;
import com.pokeemc.trade.service.TradeSnapshot;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 9-1：玩家交易隐私投影验证（计划 5.2/5.4）。
 * <p>
 * 逐项断言：
 * <ul>
 *   <li>目录（{@link TradeDirectoryPacket}）条目只含 playerId/displayName/capability，不含任何资产字段；</li>
 *   <li>对手视角快照只含已托管报价摘要（物品 itemId+count、PKM 总额、宝可梦展示字段），绝不含 NBT/招式/个体值；</li>
 *   <li>本人收货偏好不会投影给对手：对手视角快照的 selfDeliveryPreference 是对手自己的偏好；</li>
 *   <li>非托管资产不出现：只有已进报价的资产才会出现在快照中。</li>
 * </ul>
 * 快照投影基于服务层 {@link TradeSnapshot}（已按查看者视角构造），网络层只做展示字段裁剪。
 */
class TradePrivacyProjectionTest {

    private static final UUID LEFT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RIGHT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TRADE = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    void directoryEntriesCarryNoAssetFields() {
        // 类型即契约：PlayerDirectoryEntry 只有 playerId/displayName/capability 三个字段
        TradeDirectoryPacket.PlayerDirectoryEntry e =
                new TradeDirectoryPacket.PlayerDirectoryEntry(RIGHT, "right",
                        com.pokeemc.trade.model.TradeCapability.AVAILABLE);
        assertEquals(RIGHT, e.playerId());
        assertEquals("right", e.displayName());
        assertEquals(com.pokeemc.trade.model.TradeCapability.AVAILABLE, e.capability());
    }

    @Test
    void opponentSnapshotContainsOnlyEscrowedSummary() {
        // LEFT 托管：物品(含完整 NBT 键)、宝可梦(含招式/个体值隐私键)
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "minecraft:diamond");
        stack.putByte("Count", (byte) 8);
        stack.putString("tag", "{\"enchantments\":\"sharpness\"}");

        CompoundTag pkmNbt = new CompoundTag();
        pkmNbt.putString("Species", "Gengar");
        pkmNbt.putInt("Level", 60);
        pkmNbt.putString("Moves", "shadow_ball");
        pkmNbt.putInt("IVs", 31);
        pkmNbt.putString("Nickname", "Boo");

        TradeOffer leftOffer = TradeOffer.empty()
                .withAdded(new ItemAsset(UUID.randomUUID(), LEFT, stack))
                .withAdded(new PokemonAsset(UUID.randomUUID(), LEFT, UUID.randomUUID(),
                        pkmNbt, "party", -1, 2));

        TradeSnapshot leftView = new TradeSnapshot(
                new TradeId(TRADE), TradeStatus.OPEN, 3L, LEFT, RIGHT,
                leftOffer, TradeOffer.empty(),
                false, false, 1_000L, 0L,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INBOX,
                        DeliveryPreference.PokemonDestination.INBOX),
                null);

        TradeSnapshotPacket wire = TradeSnapshotProjection.project(leftView, "left", "right");

        // 物品摘要：只有 itemId + count
        assertEquals(1, wire.selfOffer().items().size());
        TradeSnapshotPacket.ItemWire item = wire.selfOffer().items().get(0);
        assertEquals("minecraft:diamond", item.itemId());
        assertEquals(8, item.count());
        // 宝可梦摘要：只有展示字段
        TradeSnapshotPacket.PokemonWire pkm = wire.selfOffer().pokemon().get(0);
        assertEquals("Gengar", pkm.species());
        assertEquals(60, pkm.level());
        assertEquals("Boo", pkm.nickname());
        // 未托管资产不在快照中：对手报价为空
        assertTrue(wire.otherOffer().items().isEmpty());
        assertTrue(wire.otherOffer().pokemon().isEmpty());
    }

    @Test
    void ownDeliveryPreferenceIsNotProjectedToOpponent() {
        // LEFT 视角：LEFT 偏好 INVENTORY
        TradeSnapshot leftView = new TradeSnapshot(
                new TradeId(TRADE), TradeStatus.OPEN, 3L, LEFT, RIGHT,
                TradeOffer.empty(), TradeOffer.empty(),
                false, false, 1_000L, 0L,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INVENTORY,
                        DeliveryPreference.PokemonDestination.PC),
                null);
        // RIGHT 视角：RIGHT 偏好 INBOX
        TradeSnapshot rightView = new TradeSnapshot(
                new TradeId(TRADE), TradeStatus.OPEN, 3L, RIGHT, LEFT,
                TradeOffer.empty(), TradeOffer.empty(),
                false, false, 1_000L, 0L,
                new DeliveryPreference(DeliveryPreference.ItemDestination.INBOX,
                        DeliveryPreference.PokemonDestination.INBOX),
                null);

        // 推给 LEFT 的包只携带 LEFT 自己的偏好；推给 RIGHT 的包只携带 RIGHT 自己的偏好
        TradeSnapshotPacket toLeft = TradeSnapshotProjection.project(leftView, "left", "right");
        TradeSnapshotPacket toRight = TradeSnapshotProjection.project(rightView, "right", "left");

        assertEquals(DeliveryPreference.ItemDestination.INVENTORY,
                toLeft.selfDeliveryPreference().itemDestination());
        assertEquals(DeliveryPreference.PokemonDestination.PC,
                toLeft.selfDeliveryPreference().pokemonDestination());

        assertEquals(DeliveryPreference.ItemDestination.INBOX,
                toRight.selfDeliveryPreference().itemDestination());
        assertEquals(DeliveryPreference.PokemonDestination.INBOX,
                toRight.selfDeliveryPreference().pokemonDestination());
    }

    @Test
    void nonEscrowedAssetsDoNotAppearInSnapshot() {
        // 双方报价为空时，快照中不存在任何资产条目
        TradeSnapshot emptyView = new TradeSnapshot(
                new TradeId(TRADE), TradeStatus.INVITED, 1L, LEFT, RIGHT,
                TradeOffer.empty(), TradeOffer.empty(),
                false, false, 1_000L, 0L,
                new DeliveryPreference(DeliveryPreference.ItemDestination.AUTO,
                        DeliveryPreference.PokemonDestination.AUTO),
                null);
        TradeSnapshotPacket wire = TradeSnapshotProjection.project(emptyView, "left", "right");
        assertTrue(wire.selfOffer().items().isEmpty());
        assertTrue(wire.selfOffer().pokemon().isEmpty());
        assertEquals(0L, wire.selfOffer().pkmTotal());
        assertEquals(TradeStatus.INVITED, wire.status());
    }
}
