package com.pokeemc.storage;

import com.mojang.logging.LogUtils;
import com.pokeemc.PokeEMC;
import com.pokeemc.blockentity.CondenserBlockEntity;
import com.pokeemc.registry.ModBlockEntities;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import net.neoforged.neoforge.items.wrapper.EmptyItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 11 自动化守卫：为受支持容器 BlockEntity 注册 ITEM_HANDLER capability
 * 的受控包装器，漏斗/投掷器等原版自动化对认领仓储的插入/抽取必须满足
 * 所有者的 {@code automationInsertEnabled} / {@code automationExtractEnabled} 开关。
 *
 * <p>默认（两项开关均为 false）拒绝一切自动化。判定不复用玩家 ACL、不允许
 * 管理或出售——只认所有者显式开启的自动化开关。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>capability provider 以 {@code EventPriority.HIGHEST} 注册（见
 *       {@code PokeEMC} 构造），先于 NeoForge 的内置 provider 执行；
 *       capability 查找首个非 null 获胜，因此包装器接管漏斗/投掷器存取。</li>
 *   <li>每次真实操作都<b>重新定位</b> {@link StorageSavedData} 记录，避免缓存过期。</li>
 *   <li>ThreadLocal 重入守卫：包装器内部转发 vanilla handler 前置位，
 *       若已在守卫内则直接委托，避免递归（如投掷器同时推入推出）。</li>
 *   <li>simulate 不写审计；真实操作按 tick 聚合（维度|区块|方向）写入审计，
 *       由游戏 tick 事件冲刷，避免每操作一次写一条。</li>
 * </ul>
 */
public final class StorageAutomationGuard {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 重入守卫：仅在包装器转发 vanilla handler 期间为 true。 */
    private static final ThreadLocal<Boolean> REENTRANT =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** 自动化动作无玩家主体，用固定占位 UUID 写入审计。 */
    private static final UUID AUTOMATION_ACTOR = new UUID(0L, 1L);

    /** 待冲刷的聚合计数：{@code dim|chunkX|chunkZ|direction} → [insert, extract]。 */
    private static final Map<String, long[]> PENDING_COUNTS = new HashMap<>();

    private StorageAutomationGuard() {
    }

    // ---------------------------------------------------------------- 注册

    /**
     * mod bus 注册（在 {@code PokeEMC} 构造中以 {@code EventPriority.HIGHEST}
     * 挂载）：为内置容器 BlockEntity 注册 ITEM_HANDLER 受控 provider。
     */
    public static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.CHEST,
                (chest, direction) -> new GuardedHandler(chest, direction));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.TRAPPED_CHEST,
                (chest, direction) -> new GuardedHandler(chest, direction));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, BlockEntityType.BARREL,
                (barrel, direction) -> new GuardedHandler(barrel, direction));
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.CONDENSER.get(),
                (condenser, direction) -> new GuardedHandler(condenser, direction));
        // 降级说明：自动化拦截只覆盖内置容器，第三方容器可能绕过
        PokeEMC.LOGGER.warn("automation guard covers built-in containers only; "
                + "third-party containers may bypass");
    }

    /** 游戏 tick（由 {@code PokeEMC} 注册）：把聚合计数写入审计。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) {
            return;
        }
        flushPending(server);
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 自动化插入/抽取是否被所有者允许（纯判定，GameTest 可直接调用）。
     *
     * @param record 最新 {@link StorageRecord}；null（未认领）一律拒绝
     * @param insert true 查插入开关，false 查抽取开关
     */
    public static boolean allowAutomation(StorageRecord record, boolean insert) {
        if (record == null) {
            return false;
        }
        return insert ? record.automationInsertEnabled() : record.automationExtractEnabled();
    }

    // ---------------------------------------------------------------- 审计聚合

    private static void flushPending(MinecraftServer server) {
        if (PENDING_COUNTS.isEmpty()) {
            return;
        }
        Map<String, long[]> snapshot = new HashMap<>(PENDING_COUNTS);
        PENDING_COUNTS.clear();
        try {
            StorageSavedData data = server.overworld().getDataStorage()
                    .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
            long now = System.currentTimeMillis();
            snapshot.forEach((key, counts) -> {
                if (counts[0] > 0) {
                    data.appendAudit(now, "-", AUTOMATION_ACTOR, "automation_insert",
                            "aggregated=" + counts[0] + " chunk|dir=" + key);
                }
                if (counts[1] > 0) {
                    data.appendAudit(now, "-", AUTOMATION_ACTOR, "automation_extract",
                            "aggregated=" + counts[1] + " chunk|dir=" + key);
                }
            });
        } catch (RuntimeException e) {
            LOGGER.warn("storage: failed to write automation audit", e);
        }
    }

    // ---------------------------------------------------------------- 包装器

    /**
     * 受控 {@link IItemHandler} 包装器：真实插入/抽取前重新定位记录并校验
     * 所有者自动化开关；被拒时插入原样返回、抽取返回空。读取类操作直接转发。
     */
    static final class GuardedHandler implements IItemHandler {

        private final BlockEntity blockEntity;
        private final Direction direction;
        private final IItemHandler delegate;

        GuardedHandler(BlockEntity blockEntity, Direction direction) {
            this.blockEntity = blockEntity;
            this.direction = direction;
            if (blockEntity instanceof CondenserBlockEntity condenser) {
                // 凝聚器视图 = 输入槽(0) + 输出槽(1)
                this.delegate = new CombinedInvWrapper(
                        new InvWrapper(condenser.getInputContainer()),
                        new InvWrapper(condenser.getOutputContainer()));
            } else if (blockEntity instanceof Container container) {
                // 普通箱/双箱/陷阱箱/木桶：InvWrapper 内部委托 ChestBlock 组合逻辑
                this.delegate = new InvWrapper(container);
            } else {
                // 理论上不可达（只对已注册的容器类型挂载）
                this.delegate = EmptyItemHandler.INSTANCE;
            }
        }

        @Override
        public int getSlots() {
            return delegate.getSlots();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return delegate.getStackInSlot(slot);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !allowAutomation(currentRecord(), true)) {
                return stack; // 未开启自动化插入或未认领：原样返回，拒绝
            }
            if (REENTRANT.get()) {
                return delegate.insertItem(slot, stack, simulate); // 守卫内直接转发
            }
            REENTRANT.set(Boolean.TRUE);
            try {
                ItemStack result = delegate.insertItem(slot, stack, simulate);
                if (!simulate && result.getCount() < stack.getCount()) {
                    recordActivity(true);
                }
                return result;
            } finally {
                REENTRANT.set(Boolean.FALSE);
            }
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0 || !allowAutomation(currentRecord(), false)) {
                return ItemStack.EMPTY; // 未开启自动化抽取或未认领：拒绝
            }
            if (REENTRANT.get()) {
                return delegate.extractItem(slot, amount, simulate); // 守卫内直接转发
            }
            REENTRANT.set(Boolean.TRUE);
            try {
                ItemStack result = delegate.extractItem(slot, amount, simulate);
                if (!simulate && !result.isEmpty()) {
                    recordActivity(false);
                }
                return result;
            } finally {
                REENTRANT.set(Boolean.FALSE);
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            return delegate.getSlotLimit(slot);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return delegate.isItemValid(slot, stack);
        }

        /** 重新定位当前 pos 对应的最新记录；未认领或非服务端返回 null。 */
        private StorageRecord currentRecord() {
            Level level = blockEntity.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return null;
            }
            String typeId = StorageProtectionEvents.typeIdFor(blockEntity.getBlockState());
            if (typeId == null) {
                return null;
            }
            String dim = serverLevel.dimension().location().toString();
            StorageKey key = StorageProtectionEvents.canonicalKey(
                    StorageServices.registry(), serverLevel, dim, typeId,
                    blockEntity.getBlockPos());
            return StorageProtectionEvents.savedData(serverLevel).getRecord(key).orElse(null);
        }

        /** 真实插入/抽取后按「维度|区块|方向」聚合计数，tick 末统一写审计。 */
        private void recordActivity(boolean insert) {
            Level level = blockEntity.getLevel();
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            BlockEntity be = blockEntity;
            String key = serverLevel.dimension().location().toString()
                    + "|" + (be.getBlockPos().getX() >> 4)
                    + "|" + (be.getBlockPos().getZ() >> 4)
                    + "|" + (direction == null ? "none" : direction.getName());
            PENDING_COUNTS.computeIfAbsent(key, ignored -> new long[2])[insert ? 0 : 1]++;
        }
    }
}
