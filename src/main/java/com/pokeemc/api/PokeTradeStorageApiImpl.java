package com.pokeemc.api;

import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageTransactionService;
import com.pokeemc.storage.adapter.StorageAdapterRegistryImpl;
import com.pokeemc.storage.discovery.StorageDiscoveryService;
import com.poketrade.api.storage.StorageAdapter;
import com.poketrade.api.storage.StorageAdapterContext;
import com.poketrade.api.permission.ProtectionAction;
import com.pokeemc.thirdparty.ThirdPartyServices;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageHandle;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageQuery;
import com.poketrade.api.storage.StorageService;
import com.poketrade.api.storage.StorageSnapshot;
import com.poketrade.api.storage.StorageTransaction;
import com.poketrade.api.storage.StorageTransactionResult;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link StorageService} 的服务端实现。
 *
 * <p>所有外部调用统一调度到服务端主线程执行（已在主线程时直接执行、避免死锁）。
 * 事务执行委托给 {@link StorageTransactionService}：鉴权、revision/指纹并发控制、
 * 两阶段 simulate-commit 原子性、幂等与审计均在那里收敛；本类负责把调用桥接到
 * 服务端线程并暴露查询/快照。</p>
 */
public final class PokeTradeStorageApiImpl implements StorageService {

    private final StorageAdapterRegistryImpl registry;
    private final StorageAccessService access;
    private final StorageDiscoveryService discovery;
    private final StorageTransactionService transactionService;

    public PokeTradeStorageApiImpl(
            StorageAdapterRegistryImpl registry,
            StorageAccessService access,
            StorageDiscoveryService discovery,
            StorageTransactionService transactionService) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.access = Objects.requireNonNull(access, "access");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.transactionService = Objects.requireNonNull(transactionService, "transactionService");
    }

    // ---------------------------------------------------------------- 调度

    private <T> T onServer(Callable<T> action) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("storage service requires a running server");
        }
        if (server.isSameThread()) {
            try {
                return action.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    private StorageSavedData savedData() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("storage service requires a running server");
        }
        return server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    private static StorageId toStorageId(StorageKey key) {
        return new StorageId(key.dimension(), key.adapterType(), key.location());
    }

    private static StorageAccessService.AccessSnapshot accessSnapshot(StorageRecord record) {
        return new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public List<StorageDescriptor> query(StorageQuery query) {
        return onServer(() -> doQuery(query));
    }

    /**
     * 查询委托给 {@link StorageDiscoveryService}：半径/结果数 clamp、同 actor
     * 限频（超限返回缓存）、ACL 可见性过滤、扫描预算与增量刷新，均在此收敛。
     */
    private List<StorageDescriptor> doQuery(StorageQuery query) {
        return discovery.querySync(query);
    }

    // ---------------------------------------------------------------- 快照

    @Override
    public Optional<StorageSnapshot> snapshot(
            UUID actorId, StorageId storageId, long expectedRevision) {
        return onServer(() -> doSnapshot(actorId, storageId, expectedRevision));
    }

    private Optional<StorageSnapshot> doSnapshot(
            UUID actorId, StorageId storageId, long expectedRevision) {
        StorageAdapter adapter = registry.byTypeId(storageId.adapterType()).orElse(null);
        if (adapter == null) {
            return Optional.empty();
        }
        StorageKey key = StorageKey.of(
                storageId.dimension(), storageId.adapterType(), storageId.location());
        StorageRecord record = savedData().getRecord(key).orElse(null);
        if (record == null) {
            return Optional.empty();
        }
        if (!access.canView(actorId, accessSnapshot(record))) {
            return Optional.empty();
        }
        if (!ThirdPartyServices.protectionHook()
                .allows(actorId, storageId, ProtectionAction.VIEW)) {
            return Optional.empty();
        }
        if (expectedRevision != -1 && expectedRevision != record.revision()) {
            return Optional.empty();
        }
        StorageAdapterContext context = new StorageAdapterContext(storageId);
        if (!adapter.supports(context)) {
            return Optional.empty();
        }
        try (StorageHandle handle = adapter.open(context).orElse(null)) {
            if (handle == null) {
                return Optional.empty();
            }
            StorageSnapshot raw = handle.snapshot();
            return Optional.of(new StorageSnapshot(storageId, record.revision(), raw.slots()));
        }
    }

    // ---------------------------------------------------------------- 事务

    @Override
    public StorageTransactionResult execute(StorageTransaction transaction) {
        return onServer(() -> transactionService.execute(transaction));
    }
}
