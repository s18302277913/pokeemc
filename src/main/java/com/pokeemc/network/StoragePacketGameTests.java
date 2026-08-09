package com.pokeemc.network;

import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.adapter.MinecraftSlotStore;
import com.pixelmonmod.api.registry.RegistryValue;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBall;
import com.pixelmonmod.pixelmon.api.pokemon.item.pokeball.PokeBallRegistry;
import com.pixelmonmod.pixelmon.init.registry.PixelmonDataComponents;
import com.pixelmonmod.pixelmon.items.PokeBallItem;
import com.poketrade.api.storage.StorageId;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 网络包服务端执行 GameTest：拖入存入（carried）、仓储间转移。
 * GameTest 服务器无在线玩家，用 FakePlayer + 手动挂载 {@link StorageBrowserMenu}。
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class StoragePacketGameTests {

    private static final String BATCH = "packets";
    private static final String DIM = "minecraft:overworld";

    private static FakePlayer player(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        FakePlayer p = FakePlayerFactory.getMinecraft(level);
        p.getInventory().clearContent();
        p.containerMenu = new StorageBrowserMenu.Standalone(0, p.getInventory(), null);
        return p;
    }

    private static ItemStack stackOf(String itemId, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId)), count);
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) {
            helper.fail(message);
        }
    }

    private static StorageKey claimChest(GameTestHelper helper, ServerPlayer owner, BlockPos pos) {
        helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        StorageSavedData data = helper.getLevel().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageKey key = StorageKey.of(DIM, "vanilla_chest",
                pos.getX() + ";" + pos.getY() + ";" + pos.getZ());
        data.claim(key, StorageRecord.create(owner.getUUID(), "Dev",
                System.currentTimeMillis()), pos.getX() >> 4, pos.getZ() >> 4);
        return key;
    }

    private static StorageId sidOf(StorageKey key) {
        return new StorageId(key.dimension(), key.adapterType(), key.location());
    }

    private static ChestBlockEntity chestAt(GameTestHelper helper, BlockPos pos) {
        return (ChestBlockEntity) helper.getLevel().getBlockEntity(pos);
    }

    private static long revisionOf(GameTestHelper helper, StorageKey key) {
        return helper.getLevel().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME)
                .getRecord(key).orElseThrow().revision();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void carriedDepositInsertsIntoTargetSlot(GameTestHelper helper) {
        FakePlayer p = player(helper);
        BlockPos pos = BlockPos.containing(helper.absoluteVec(new Vec3(1, 0, 1)));
        p.setPos(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
        StorageKey key = claimChest(helper, p, pos);
        long rev = revisionOf(helper, key);
        p.containerMenu.setCarried(stackOf("minecraft:stick", 5));

        StorageDepositPacket.Response r = StorageDepositCarriedPacket.executeDepositCarried(p,
                new StorageDepositCarriedPacket("sess", "op", sidOf(key), 0, rev, 5));
        check(helper, r.success(), "拖入存入应成功，实际 " + r.code() + " " + r.message());
        check(helper, p.containerMenu.getCarried().isEmpty(), "存入后 carried 应清空");
        ItemStack slot = chestAt(helper, pos).getItem(0);
        check(helper, !slot.isEmpty() && slot.getCount() == 5,
                "目标槽位应有 5 个 stick，实际 " + slot);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void transferMovesItemsBetweenStorages(GameTestHelper helper) {
        FakePlayer p = player(helper);
        BlockPos a = BlockPos.containing(helper.absoluteVec(new Vec3(1, 0, 1)));
        BlockPos b = BlockPos.containing(helper.absoluteVec(new Vec3(5, 0, 1)));
        p.setPos(a.getX() + 1.5, a.getY(), a.getZ() + 0.5);
        StorageKey keyA = claimChest(helper, p, a);
        StorageKey keyB = claimChest(helper, p, b);
        chestAt(helper, a).setItem(0, stackOf("minecraft:stick", 5));

        StorageMovePacket.Response r = StorageTransferPacket.executeTransfer(p,
                new StorageTransferPacket("sess", "op",
                        sidOf(keyA), 0, 0L, sidOf(keyB), 5,
                        revisionOf(helper, keyA), revisionOf(helper, keyB)));
        check(helper, r.success(), "转移应成功，实际 " + r.code() + " " + r.message());
        check(helper, chestAt(helper, a).getItem(0).isEmpty(), "源槽位应清空");
        ItemStack target = chestAt(helper, b).getItem(0);
        check(helper, !target.isEmpty() && target.getCount() == 5,
                "目标槽位应有 5 个 stick，实际 " + target);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void transferRejectsSameSourceAndTarget(GameTestHelper helper) {
        FakePlayer p = player(helper);
        BlockPos a = BlockPos.containing(helper.absoluteVec(new Vec3(1, 0, 1)));
        p.setPos(a.getX() + 1.5, a.getY(), a.getZ() + 0.5);
        StorageKey keyA = claimChest(helper, p, a);
        long rev = revisionOf(helper, keyA);

        StorageMovePacket.Response r = StorageTransferPacket.executeTransfer(p,
                new StorageTransferPacket("sess", "op",
                        sidOf(keyA), 0, 0L, sidOf(keyA), 5, rev, rev));
        check(helper, !r.success() && "invalid_request".equals(r.code()),
                "同仓储转移应拒绝，实际 " + r.code());
        helper.succeed();
    }

    /**
     * [CHANGED] Bug 1 回归：Pixelmon 所有球类共用 {@code pixelmon:poke_ball} 注册键，
     * 球种由 PokeBall DataComponent 区分。仓储存取必须保留球种身份——
     * itemId 编码为 {@code pixelmon:poke_ball#master_ball}，写回时还原组件，
     * 大师球不允许降级成普通精灵球。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void pokeballVariantSurvivesStorageRoundTrip(GameTestHelper helper) {
        FakePlayer p = player(helper);
        BlockPos pos = BlockPos.containing(helper.absoluteVec(new Vec3(1, 0, 1)));
        p.setPos(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
        claimChest(helper, p, pos);
        ChestBlockEntity chest = chestAt(helper, pos);

        RegistryValue<PokeBall> master = PokeBallRegistry.getPokeBall("master_ball");
        check(helper, master != null && master.isInitialized(), "Pixelmon master_ball 应已注册");
        ItemStack original = PokeBallItem.of(master.get(), 1);
        chest.setItem(0, original);

        MinecraftSlotStore store = MinecraftSlotStore.of(chest);
        String encoded = store.itemId(0);
        check(helper, "pixelmon:poke_ball#master_ball".equals(encoded),
                "大师球 itemId 应编码球种，实际 " + encoded);

        // 写回：同样经身份解码还原组件，容器里仍是大师球
        store.set(0, encoded, 1);
        ItemStack restored = chest.getItem(0);
        RegistryValue<PokeBall> ball = restored.get(PixelmonDataComponents.POKE_BALL.get());
        check(helper, ball != null && "master_ball".equals(ball.get().getName()),
                "写回后容器里的球应保持 master_ball，实际 " + restored);
        helper.succeed();
    }

    /**
     * [CHANGED] Bug 1 回归：拖入存入（carried）是真实玩家操作路径，
     * 服务端取 itemId 必须经 {@link PokeballIdentity#encode} 编码球种——
     * 拖入大师球后容器里仍应是大师球，不允许降级为普通精灵球。
     */
    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void carriedDepositKeepsPokeballVariant(GameTestHelper helper) {
        FakePlayer p = player(helper);
        BlockPos pos = BlockPos.containing(helper.absoluteVec(new Vec3(1, 0, 1)));
        p.setPos(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
        StorageKey key = claimChest(helper, p, pos);
        long rev = revisionOf(helper, key);

        RegistryValue<PokeBall> master = PokeBallRegistry.getPokeBall("master_ball");
        check(helper, master != null && master.isInitialized(), "Pixelmon master_ball 应已注册");
        p.containerMenu.setCarried(PokeBallItem.of(master.get(), 1));

        StorageDepositPacket.Response r = StorageDepositCarriedPacket.executeDepositCarried(p,
                new StorageDepositCarriedPacket("sess", "op", sidOf(key), 0, rev, 1));
        check(helper, r.success(), "拖入存入大师球应成功，实际 " + r.code() + " " + r.message());
        ItemStack stored = chestAt(helper, pos).getItem(0);
        RegistryValue<PokeBall> ball = stored.get(PixelmonDataComponents.POKE_BALL.get());
        check(helper, ball != null && "master_ball".equals(ball.get().getName()),
                "拖入后容器里的球应保持 master_ball，实际 " + stored);
        helper.succeed();
    }
}
