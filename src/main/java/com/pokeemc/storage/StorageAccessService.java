package com.pokeemc.storage;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 仓储授权服务：六项独立权限的逐项判定。
 *
 * <p>判定顺序（见计划 2.2）：所有者返回完整权限；管理员仅对当前动作绕过并审计；
 * 显式玩家 deny 覆盖组与 PUBLIC；合并精确玩家、已解析组与 PUBLIC 的 allow；
 * 最后逐项检查所需权限，不得因拥有其他权限放行。</p>
 */
public final class StorageAccessService {

    private static final EnumSet<StoragePermission> OWNER_FULL =
            EnumSet.allOf(StoragePermission.class);

    /** 按玩家 UUID 解析权限组；未安装组权限适配器时返回空。 */
    @FunctionalInterface
    public interface GroupResolver {
        Optional<StoragePrincipal.Group> groupOf(UUID playerId);
    }

    /** 管理员判定。 */
    @FunctionalInterface
    public interface AdminChecker {
        boolean isAdmin(UUID playerId);
    }

    /** 管理员绕过审计回调。 */
    @FunctionalInterface
    public interface AuditSink {
        void record(UUID actorId, UUID storageOwnerId, StoragePermission permission);
    }

    /** 授权所需的仓储访问快照（由 StorageRecord 提供，便于纯 JVM 测试）。 */
    public record AccessSnapshot(UUID ownerId, Map<StoragePrincipal, StorageGrant> grants) {
        public AccessSnapshot {
            Objects.requireNonNull(ownerId, "ownerId");
            Objects.requireNonNull(grants, "grants");
        }
    }

    private final GroupResolver groupResolver;
    private final AdminChecker adminChecker;
    private final AuditSink auditSink;

    public StorageAccessService(GroupResolver groupResolver,
                                AdminChecker adminChecker,
                                AuditSink auditSink) {
        this.groupResolver = Objects.requireNonNull(groupResolver, "groupResolver");
        this.adminChecker = Objects.requireNonNull(adminChecker, "adminChecker");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    // ---- 六项独立服务端入口 ----

    public boolean canView(UUID actorId, AccessSnapshot snapshot) {
        return allows(actorId, StoragePermission.VIEW, snapshot);
    }

    public boolean canDeposit(UUID actorId, AccessSnapshot snapshot) {
        return allows(actorId, StoragePermission.DEPOSIT, snapshot);
    }

    public boolean canWithdraw(UUID actorId, AccessSnapshot snapshot) {
        return allows(actorId, StoragePermission.WITHDRAW, snapshot);
    }

    public boolean canSell(UUID actorId, AccessSnapshot snapshot) {
        return allows(actorId, StoragePermission.SELL, snapshot);
    }

    public boolean canBreak(UUID actorId, AccessSnapshot snapshot) {
        return allows(actorId, StoragePermission.BREAK, snapshot);
    }

    public boolean canManage(UUID actorId, AccessSnapshot snapshot) {
        return allows(actorId, StoragePermission.MANAGE, snapshot);
    }

    /**
     * 核心判定：仅检查指定权限；拥有其他权限不会放行。
     */
    public boolean allows(UUID actorId, StoragePermission permission, AccessSnapshot snapshot) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(snapshot, "snapshot");

        // 2. 所有者：完整权限集
        if (snapshot.ownerId().equals(actorId)) {
            return OWNER_FULL.contains(permission);
        }

        // 3. 管理员：仅对当前动作绕过并审计
        if (adminChecker.isAdmin(actorId)) {
            auditSink.record(actorId, snapshot.ownerId(), permission);
            return true;
        }

        // 4-5. 合并并应用 deny 覆盖
        return mergedPermissions(actorId, snapshot).allows(permission);
    }

    /**
     * 计算合并后的权限集（不含所有者与管理员的特殊放行）。
     */
    public StoragePermissionSet mergedPermissions(UUID actorId, AccessSnapshot snapshot) {
        EnumSet<StoragePermission> allow = EnumSet.noneOf(StoragePermission.class);
        EnumSet<StoragePermission> deny = EnumSet.noneOf(StoragePermission.class);

        StoragePrincipal.Player playerPrincipal = new StoragePrincipal.Player(actorId);
        Optional<StoragePrincipal.Group> resolvedGroup = groupResolver.groupOf(actorId);

        snapshot.grants().forEach((principal, grant) -> {
            boolean matches = principal.equals(playerPrincipal)
                    || principal instanceof StoragePrincipal.Public
                    || (resolvedGroup.isPresent() && principal.equals(resolvedGroup.get()));
            if (!matches) {
                return;
            }
            allow.addAll(grant.allow().values());
            deny.addAll(grant.deny().values());
        });

        // deny 优先：显式 deny 覆盖组与 PUBLIC 的 allow
        deny.forEach(allow::remove);
        return StoragePermissionSet.from(allow);
    }
}
