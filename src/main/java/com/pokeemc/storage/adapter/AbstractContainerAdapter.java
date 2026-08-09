package com.pokeemc.storage.adapter;

import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageHandle;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * 基于世界容器（{@link Container}）的适配器基类。
 *
 * <p>统一承担：维度→{@link ServerLevel} 解析、位置字符串解析、已加载区块校验、
 * {@code supports}/{@code open} 流程。子类只需声明类型 ID、能力集合并实现
 * {@link #matches(Level, BlockPos)} 与 {@link #resolve(Level, BlockPos)}。</p>
 *
 * <p>适配器只访问已加载区块：位置所在区块未加载时 {@code supports} 与 {@code open}
 * 一律返回 false / empty，绝不强制加载区块。</p>
 */
public abstract class AbstractContainerAdapter implements StorageAdapter {

    private final String typeId;
    private final Set<StorageCapability> capabilities;

    protected AbstractContainerAdapter(String typeId, StorageCapability... capabilities) {
        this.typeId = Objects.requireNonNull(typeId, "typeId");
        this.capabilities = Set.of(capabilities);
    }

    @Override
    public String typeId() {
        return typeId;
    }

    @Override
    public Set<StorageCapability> capabilities() {
        return capabilities;
    }

    @Override
    public boolean supports(StorageAdapterContext context) {
        Level level = resolveLevel(context.storageId().dimension());
        BlockPos pos = parsePos(context.storageId().location());
        return level != null && pos != null && level.isLoaded(pos) && matches(level, pos);
    }

    @Override
    public Optional<StorageHandle> open(StorageAdapterContext context) {
        Level level = resolveLevel(context.storageId().dimension());
        BlockPos pos = parsePos(context.storageId().location());
        if (level == null || pos == null || !level.isLoaded(pos)) {
            return Optional.empty();
        }
        return resolve(level, pos).map(access ->
                new StorageHandleImpl(context.storageId(),
                        MinecraftSlotStore.of(access.container()),
                        access.insertable(),
                        access.extractable(),
                        access.revisionSource()));
    }

    /** 位置上的方块是否属于本适配器类型。 */
    protected abstract boolean matches(Level level, BlockPos pos);

    /** 解析位置上的容器访问描述；容器不存在或类型不符返回空。 */
    protected abstract Optional<ContainerAccess> resolve(Level level, BlockPos pos);

    // ---- 共享工具 ----

    /** 按维度 ID 解析服务端世界；非服务端或维度不存在返回 null。 */
    protected static Level resolveLevel(String dimension) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            return server.getLevel(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 解析 "x;y;z" 位置字符串；格式非法返回 null。 */
    public static BlockPos parsePos(String location) {
        if (location == null) {
            return null;
        }
        String[] parts = location.split(";");
        if (parts.length != 3) {
            return null;
        }
        try {
            return new BlockPos(Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 将位置编码为 "x;y;z" 字符串。 */
    public static String toLocation(BlockPos pos) {
        return pos.getX() + ";" + pos.getY() + ";" + pos.getZ();
    }

    /** 容量恒定、全槽位可读写、revision 0 的便捷容器访问。 */
    protected static ContainerAccess simple(Container container) {
        return new ContainerAccess(container, s -> true, s -> true, () -> 0L);
    }

    /** 容量恒定、revision 0、带读写过滤的容器访问。 */
    protected static ContainerAccess filtered(Container container,
                                              IntPredicate insertable,
                                              IntPredicate extractable) {
        return new ContainerAccess(container, insertable, extractable, () -> 0L);
    }

    /** 已解析的容器访问描述：容器本体 + 槽位读写过滤 + 内容 revision 来源。 */
    public record ContainerAccess(Container container,
                                  IntPredicate insertable,
                                  IntPredicate extractable,
                                  LongSupplier revisionSource) {

        public ContainerAccess {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(insertable, "insertable");
            Objects.requireNonNull(extractable, "extractable");
            Objects.requireNonNull(revisionSource, "revisionSource");
        }
    }
}
