package com.pokeemc.storage;

import com.pokeemc.storage.StorageCommands.CmdCtx;
import com.pokeemc.storage.StorageCommands.CmdError;
import com.pokeemc.storage.adapter.AbstractContainerAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Task 12 命令核心逻辑 GameTest（服务端运行，空结构 empty.nbt + 运行时放置方块）。
 *
 * <p>命令层与核心逻辑分离：测试直接调用 {@link StorageCommands} 的静态逻辑方法，
 * 通过 {@link CmdCtx#of} 显式构造身份/位置/管理员标志，不依赖在线玩家。</p>
 *
 * <p>覆盖：权限解析拒绝别名；grant/deny/revoke/info；非管理员距离门禁与
 * owner-only 门禁；transfer/unclaim；模板 create/rename/copy/apply FOLLOW/
 * delete 冻结；审计脱敏、倒序与条数上限；repair 重建区块索引；scan 触发发现。</p>
 */
@GameTestHolder("poketrade")
@PrefixGameTestTemplate(false)
public class StorageCommandGameTests {

    private static final String BATCH = "commands";
    private static final String SINGLE_CHEST = "vanilla_chest";

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void parsePermissionsRejectsAliasesAndUnknown(GameTestHelper helper) {
        check(StorageCommands.parsePermissions("view,deposit,withdraw,sell,break,manage").size() == 6,
                "all six exact permission names must parse");
        check(StorageCommands.parsePermissions(" view , deposit ").equals(
                EnumSet.of(StoragePermission.VIEW, StoragePermission.DEPOSIT)),
                "whitespace around tokens must be tolerated");

        expectError(() -> StorageCommands.parsePermissions("view,read"), "read alias must be rejected");
        expectError(() -> StorageCommands.parsePermissions("write"), "write alias must be rejected");
        expectError(() -> StorageCommands.parsePermissions(""), "empty list must be rejected");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void claimGrantDenyRevokeAndInfo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BlockPos abs = chest(helper, 1);
        StorageKey key = key(level, abs);
        CmdCtx ctx = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", true, abs);

        StorageCommands.executeClaim(ctx, key, "Owner");
        StorageRecord rec = savedData(helper).getRecord(key)
                .orElseThrow(() -> new IllegalStateException("chest must be claimed"));
        check(rec.ownerId().equals(owner), "owner must match");

        StoragePrincipal p = new StoragePrincipal.Player(other);
        StorageCommands.executeGrant(ctx, key, p,
                EnumSet.of(StoragePermission.VIEW, StoragePermission.DEPOSIT));
        rec = savedData(helper).getRecord(key).orElseThrow();
        StorageGrant grant = rec.grants().get(p);
        check(grant != null && grant.allow().values().contains(StoragePermission.VIEW)
                        && grant.allow().values().contains(StoragePermission.DEPOSIT),
                "grant must add allow permissions");
        check(grant.deny().values().isEmpty(), "grant must not carry deny");

        StorageCommands.executeDeny(ctx, key, p, EnumSet.of(StoragePermission.VIEW));
        rec = savedData(helper).getRecord(key).orElseThrow();
        grant = rec.grants().get(p);
        check(grant.allow().values().contains(StoragePermission.DEPOSIT)
                        && !grant.allow().values().contains(StoragePermission.VIEW),
                "deny must remove VIEW from allow");
        check(grant.deny().values().contains(StoragePermission.VIEW),
                "deny must set VIEW in deny");

        StorageCommands.executeRevoke(ctx, key, p);
        rec = savedData(helper).getRecord(key).orElseThrow();
        check(!rec.grants().containsKey(p), "revoke must remove the principal entirely");

        CmdCtx ownerCtx = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", false, abs);
        String info = StorageCommands.executeInfo(ownerCtx, key);
        check(info.contains("所有者: Owner"), "info must show the owner name");
        check(info.contains("自动化插入: 关"), "info must show automation defaults");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void nonAdminDistanceGateAndOwnerOnlyGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        BlockPos abs = chest(helper, 1);
        StorageKey key = key(level, abs);
        CmdCtx admin = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", true, BlockPos.ZERO);
        StorageCommands.executeClaim(admin, key, "Owner");

        // 非管理员距离过远 → 拒绝（已加载但超过 8 格）
        CmdCtx far = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", false,
                abs.offset(100, 0, 100));
        expectError(() -> StorageCommands.executeInfo(far, key),
                "non-admin beyond 8 blocks must be rejected");

        // 管理员可跨距离操作
        check(StorageCommands.executeInfo(admin, key).contains("所有者: Owner"),
                "admin may operate from anywhere");

        // 授权 MANAGE 后，其他玩家在 8 格内可 info，但不可 transfer/unclaim
        StoragePrincipal p = new StoragePrincipal.Player(other);
        StorageCommands.executeGrant(admin, key, p, EnumSet.of(StoragePermission.MANAGE));
        CmdCtx nearOther = CmdCtx.of(helper.getLevel().getServer(), level, other, "Other", false, abs);
        check(StorageCommands.executeInfo(nearOther, key) != null,
                "MANAGE holder within range may view");
        expectError(() -> StorageCommands.executeTransfer(nearOther, key, UUID.randomUUID(), "X"),
                "non-owner transfer must be denied");
        expectError(() -> StorageCommands.executeUnclaim(nearOther, key),
                "non-owner unclaim must be denied");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void transferChangesOwnerThenUnclaimRemovesRecord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        BlockPos abs = chest(helper, 1);
        StorageKey key = key(level, abs);
        CmdCtx ownerCtx = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", false, abs);

        StorageCommands.executeClaim(ownerCtx, key, "Owner");
        StorageCommands.executeTransfer(ownerCtx, key, target, "Target");
        StorageRecord rec = savedData(helper).getRecord(key)
                .orElseThrow(() -> new IllegalStateException("record must exist after transfer"));
        check(rec.ownerId().equals(target), "transfer must change the owner");

        // 原所有者不再是所有者 → transfer/unclaim 被拒
        expectError(() -> StorageCommands.executeTransfer(ownerCtx, key, owner, "Owner"),
                "ex-owner must not transfer again");
        expectError(() -> StorageCommands.executeUnclaim(ownerCtx, key),
                "ex-owner must not unclaim");

        // 管理员可取消认领（unclaim 走 SavedData 热替换删除记录）
        CmdCtx admin = CmdCtx.of(helper.getLevel().getServer(), level, UUID.randomUUID(), "Admin",
                true, BlockPos.ZERO);
        StorageCommands.executeUnclaim(admin, key);
        check(savedData(helper).getRecord(key).isEmpty(), "unclaim must remove the record");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void templateCreateRenameCopyApplyFollowAndDeleteFreezes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        BlockPos abs = chest(helper, 1);
        StorageKey key = key(level, abs);
        CmdCtx ctx = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", true, abs);
        StorageCommands.executeClaim(ctx, key, "Owner");

        // 幂等清理：GameTest 世界跨运行持久化，上次失败残留的固定模板 id 必须先清除
        savedData(helper).deleteTemplate("t1");
        savedData(helper).deleteTemplate("t2");

        StoragePrincipal pub = new StoragePrincipal.Public();
        StorageCommands.executeTemplateCreate(ctx, StorageTemplate.Scope.PLAYER, "t1", "T1", pub,
                EnumSet.of(StoragePermission.VIEW, StoragePermission.DEPOSIT));
        StorageCommands.executeTemplateRename(ctx, "t1", "Renamed");
        StorageTemplate t1 = savedData(helper).getTemplate("t1")
                .orElseThrow(() -> new IllegalStateException("template must exist"));
        check("Renamed".equals(t1.name()), "rename must update the template name");

        StorageCommands.executeTemplateCopy(ctx, "t1", "t2", "T2");
        check(savedData(helper).getTemplate("t2").isPresent(), "copy must create t2");

        // FOLLOW 绑定：模板更新动态叠加
        StorageCommands.executeTemplateApply(ctx, "t1", StorageRecord.TemplateMode.FOLLOW, key);
        StorageRecord rec = savedData(helper).getRecord(key).orElseThrow();
        check("t1".equals(rec.templateBinding())
                        && rec.templateMode() == StorageRecord.TemplateMode.FOLLOW,
                "storage must be FOLLOW-bound to t1");

        // 删除模板 → FOLLOW 仓储冻结为独立 COPY，权限不静默扩大
        StorageCommands.executeTemplateDelete(ctx, "t1");
        rec = savedData(helper).getRecord(key).orElseThrow();
        check(rec.templateBinding() == null && rec.templateMode() == StorageRecord.TemplateMode.COPY,
                "FOLLOW storage must be frozen to COPY without binding");
        StorageGrant frozen = rec.grants().get(pub);
        check(frozen != null && frozen.allow().values().contains(StoragePermission.VIEW)
                        && frozen.allow().values().contains(StoragePermission.DEPOSIT),
                "frozen grants must preserve the template allows");
        check(savedData(helper).getTemplate("t1").isEmpty(), "template must be deleted");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void auditMaskedNewestFirstAndCountLimited(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID actor = UUID.randomUUID();
        CmdCtx ctx = CmdCtx.of(helper.getLevel().getServer(), level, actor, "Tester", true, BlockPos.ZERO);
        StorageSavedData data = savedData(helper);

        long now = System.currentTimeMillis();
        data.appendAudit(now, "-", actor, "first_marker", "detail " + actor);
        data.appendAudit(now + 1, "-", actor, "second_marker", "second detail");

        List<String> lines = StorageCommands.executeAudit(ctx, 100, null);
        String joined = String.join("\n", lines);
        check(!joined.contains(actor.toString()), "actor UUID must be masked in output");
        check(joined.contains(StorageCommands.maskUuid(actor)), "masked uuid form must appear");
        int iSecond = joined.indexOf("second_marker");
        int iFirst = joined.indexOf("first_marker");
        check(iSecond >= 0 && iFirst >= 0 && iSecond < iFirst, "audit must be newest-first by sequence");

        check(StorageCommands.executeAudit(ctx, 1, null).size() == 1, "count limit must apply");
        check(StorageCommands.executeAudit(ctx, 5000, null).size() <= StorageCommands.AUDIT_MAX_COUNT,
                "count must be clamped to the configured max");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void repairRebuildsMissingChunkIndex(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        BlockPos abs = chest(helper, 1);
        StorageKey key = key(level, abs);
        CmdCtx admin = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", true, BlockPos.ZERO);
        StorageCommands.executeClaim(admin, key, "Owner");

        // 破坏区块索引：SavedData 热替换移除该键所在的分块桶
        StorageSavedData data = savedData(helper);
        CompoundTag tag = StorageSavedData.encode(data);
        int chunkX = abs.getX() >> 4;
        int chunkZ = abs.getZ() >> 4;
        ListTag index = tag.getList("chunkIndex", Tag.TAG_COMPOUND);
        index.removeIf(t -> {
            CompoundTag e = (CompoundTag) t;
            if (!key.dimension().equals(e.getString("dim"))) {
                return false;
            }
            if (e.getInt("x") != chunkX || e.getInt("z") != chunkZ) {
                return false;
            }
            ListTag keys = e.getList("keys", Tag.TAG_STRING);
            for (int i = 0; i < keys.size(); i++) {
                if (key.asString().equals(keys.getString(i))) {
                    return true;
                }
            }
            return false;
        });
        StorageSavedData rebuilt = StorageSavedData.decode(tag, StorageSavedData.StorageLoadContext.ACCEPT_ALL);
        level.getServer().overworld().getDataStorage().set(StorageSavedData.DATA_NAME, rebuilt);

        StorageSavedData corrupt = savedData(helper);
        check(!corrupt.keysInChunk(key.dimension(), chunkX, chunkZ).contains(key),
                "chunk index must be corrupted after hot-swap");

        // repair：只重建索引，不猜测所有者、不删除记录
        String msg = StorageCommands.executeRepair(admin);
        check(msg.contains("区块索引缺失 1 条"), "repair must report exactly one missing index entry");
        StorageSavedData after = savedData(helper);
        check(after.keysInChunk(key.dimension(), chunkX, chunkZ).contains(key),
                "repair must rebuild the chunk index");
        check(after.getRecord(key).isPresent(), "repair must keep the storage record");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "poketrade", batch = BATCH, timeoutTicks = 200)
    public void scanTriggersDiscovery(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID owner = UUID.randomUUID();
        BlockPos abs = chest(helper, 1);
        StorageKey key = key(level, abs);
        CmdCtx ctx = CmdCtx.of(helper.getLevel().getServer(), level, owner, "Owner", false, abs);
        StorageCommands.executeClaim(ctx, key, "Owner");

        String msg = StorageCommands.executeScan(ctx, StorageCommands.SCAN_DEFAULT_RADIUS);
        check(msg != null && msg.startsWith("扫描完成"), "scan must complete with a summary");
        helper.succeed();
    }

    // ---------------------------------------------------------------- 工具

    private static BlockPos chest(GameTestHelper helper, int x) {
        BlockPos rel = new BlockPos(x, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        return helper.absolutePos(rel);
    }

    private static StorageKey key(ServerLevel level, BlockPos abs) {
        return StorageKey.of(dim(level), SINGLE_CHEST, AbstractContainerAdapter.toLocation(abs));
    }

    private static StorageSavedData savedData(GameTestHelper helper) {
        return helper.getLevel().getServer().overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
    }

    private static String dim(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static void expectError(Runnable action, String message) {
        try {
            action.run();
            throw new IllegalStateException("expected error: " + message);
        } catch (CmdError expected) {
            // 符合预期
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
