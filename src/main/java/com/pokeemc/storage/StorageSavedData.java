package com.pokeemc.storage;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * 世界级仓储数据：持久化归属、独立权限、模板绑定、设置、revision 与区块索引，
 * 以及有界审计环形队列。
 *
 * <p>所有变更方法内部调用 {@code setDirty()}；对已有仓储/模板的修改统一走
 * {@link #updateRecord(StorageKey, long, UnaryOperator)} 与
 * {@link #updateTemplate(String, long, Map)}，携带 {@code expectedRevision}
 * 防止旧状态覆盖新状态。</p>
 *
 * <p>序列化与反序列化为纯静态方法 {@link #encode(StorageSavedData)} /
 * {@link #decode(CompoundTag, StorageLoadContext)}，不依赖 {@code HolderLookup}，
 * 可直接在 JVM 单元测试中往返。游戏侧 {@link #load(CompoundTag, HolderLookup.Provider)}
 * 通过注册表与已注册适配器 ID 校验维度/适配器：非法条目跳过，不回退主世界。</p>
 */
public class StorageSavedData extends SavedData {

    /** SavedData 文件名（存档目录内固定）。 */
    public static final String DATA_NAME = "poketrade_storage";

    /** 数据格式版本，从 1 开始。 */
    public static final int DATA_VERSION = 1;

    /** 审计环形队列默认容量。 */
    public static final int DEFAULT_AUDIT_CAPACITY = 10_000;

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 已注册适配器 typeId 判定；Task 5 注册适配器后设置，未设置时接受所有 ID。 */
    private static volatile Predicate<String> adapterTypeValidator = typeId -> true;

    private final LinkedHashMap<StorageKey, StorageRecord> storages = new LinkedHashMap<>();
    private final LinkedHashMap<String, StorageTemplate> templates = new LinkedHashMap<>();
    private final ArrayDeque<StorageAuditEntry> audit = new ArrayDeque<>();
    private final Map<ChunkKey, Set<StorageKey>> chunkIndex = new HashMap<>();
    private int auditCapacity = DEFAULT_AUDIT_CAPACITY;
    private long nextAuditId = 1L;

    public StorageSavedData() {
        super();
    }

    /**
     * 设置适配器 typeId 有效性判定（适配器注册表接入后调用）。
     */
    public static void setAdapterTypeValidator(Predicate<String> validator) {
        adapterTypeValidator = validator == null ? (typeId -> true) : validator;
    }

    // ---------------------------------------------------------------- 工厂

    public static StorageSavedData create() {
        return new StorageSavedData();
    }

    /**
     * 供 {@code DimensionDataStorage#computeIfAbsent} 使用。
     */
    public static SavedData.Factory<StorageSavedData> factory() {
        return new SavedData.Factory<>(StorageSavedData::create, StorageSavedData::load);
    }

    /**
     * 游戏侧加载：维度经注册表校验，适配器 ID 经已注册适配器校验；
     * 非法条目跳过，不回退主世界。
     */
    public static StorageSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return decode(tag, new StorageLoadContext(
                dimension -> isRegisteredDimension(registries, dimension),
                adapterTypeValidator));
    }

    private static boolean isRegisteredDimension(
            HolderLookup.Provider registries, String dimension) {
        try {
            ResourceKey<Level> key =
                    ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension));
            return registries.lookup(Registries.DIMENSION)
                    .flatMap(lookup -> lookup.get(key))
                    .isPresent();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 查询

    public Optional<StorageRecord> getRecord(StorageKey key) {
        return Optional.ofNullable(storages.get(key));
    }

    public Optional<StorageTemplate> getTemplate(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    public Map<StorageKey, StorageRecord> recordsView() {
        return Collections.unmodifiableMap(storages);
    }

    public Map<String, StorageTemplate> templatesView() {
        return Collections.unmodifiableMap(templates);
    }

    public List<StorageAuditEntry> auditView() {
        return new ArrayList<>(audit);
    }

    public int auditSize() {
        return audit.size();
    }

    public Set<StorageKey> keysInChunk(String dimension, int chunkX, int chunkZ) {
        Set<StorageKey> keys = chunkIndex.get(new ChunkKey(dimension, chunkX, chunkZ));
        return keys == null ? Set.of() : Collections.unmodifiableSet(keys);
    }

    /**
     * 返回区块坐标落在闭区间内（含）的全部仓储键。仅供发现服务使用：
     * 先按区块索引收窄候选，再逐键过滤半径/权限/加载状态。
     */
    public Set<StorageKey> keysInChunks(
            String dimension, int minChunkX, int maxChunkX,
            int minChunkZ, int maxChunkZ) {
        LinkedHashSet<StorageKey> result = new LinkedHashSet<>();
        for (Map.Entry<ChunkKey, Set<StorageKey>> entry : chunkIndex.entrySet()) {
            ChunkKey chunk = entry.getKey();
            if (chunk.dimension().equals(dimension)
                    && chunk.chunkX() >= minChunkX && chunk.chunkX() <= maxChunkX
                    && chunk.chunkZ() >= minChunkZ && chunk.chunkZ() <= maxChunkZ) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    public int auditCapacity() {
        return auditCapacity;
    }

    // ---------------------------------------------------------------- 仓储变更

    /**
     * 认领/登记一个仓储并加入区块索引。重复认领返回 {@code false}。
     */
    public boolean claim(StorageKey key, StorageRecord record, int chunkX, int chunkZ) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(record, "record");
        if (storages.containsKey(key)) {
            return false;
        }
        storages.put(key, record);
        index(key, chunkX, chunkZ);
        setDirty();
        return true;
    }

    /**
     * 删除一个仓储记录（方块已被破坏/替换时清理幽灵记录）。
     * 同时从区块索引移除；键不存在返回 {@code false}。
     */
    public boolean deleteStorage(StorageKey key) {
        Objects.requireNonNull(key, "key");
        if (storages.remove(key) == null) {
            return false;
        }
        chunkIndex.values().forEach(keys -> keys.remove(key));
        chunkIndex.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        setDirty();
        return true;
    }

    /**
     * 迁移记录到新键：双箱主半区变化（放置更靠前的半区）时，把旧半区的记录
     * 原样迁移到规范化主键，避免同一物理双箱出现两个键。记录 ACL 保持不变，
     * 不会因合并静默扩大任何权限。旧键从所有区块索引移除，新键加入索引。
     * 目标键已存在或源键不存在时返回 {@code false}，不做任何修改。
     */
    public boolean migrateRecord(StorageKey from, StorageKey to, int chunkX, int chunkZ) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) {
            return false;
        }
        StorageRecord record = storages.get(from);
        if (record == null || storages.containsKey(to)) {
            return false;
        }
        storages.remove(from);
        storages.put(to, record.touch(System.currentTimeMillis()));
        chunkIndex.values().forEach(keys -> keys.remove(from));
        chunkIndex.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        index(to, chunkX, chunkZ);
        setDirty();
        return true;
    }

    /**
     * 统一变更入口：校验 revision 后应用变换并递增 revision、刷新 updatedAt。
     * 仓储不存在或 revision 不匹配时返回 {@code false}，不做任何修改。
     */
    public boolean updateRecord(
            StorageKey key, long expectedRevision, UnaryOperator<StorageRecord> transform) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(transform, "transform");
        StorageRecord current = storages.get(key);
        if (current == null || current.revision() != expectedRevision) {
            return false;
        }
        StorageRecord updated = transform.apply(current);
        if (updated == null) {
            return false;
        }
        storages.put(key, updated.touch(System.currentTimeMillis()));
        setDirty();
        return true;
    }

    /**
     * 强制应用一次 revision 递增（不复查当前 revision）。
     *
     * <p>仅供 {@link StorageTransactionService} 提交阶段使用：调用方已在校验阶段确认
     * revision 未变（服务端单线程内无并发改写），此处直接写回，避免 {@link #updateRecord}
     * 的复查在槽位已提交后才失败（缺陷 #6：commit 成功但 bump 失败误报冲突且物品已移动）。
     * 仓储不存在返回 {@code false}。</p>
     */
    public boolean applyRevision(StorageKey key) {
        Objects.requireNonNull(key, "key");
        StorageRecord current = storages.get(key);
        if (current == null) {
            return false;
        }
        storages.put(key, current.touch(System.currentTimeMillis()));
        setDirty();
        return true;
    }

    public boolean renameStorage(StorageKey key, long expectedRevision, String displayName) {
        return updateRecord(key, expectedRevision, record -> record.renamed(displayName));
    }

    public boolean applyGrant(
            StorageKey key, long expectedRevision,
            StoragePrincipal principal, StorageGrant grant) {
        return updateRecord(
                key, expectedRevision, record -> record.withGrant(principal, grant));
    }

    public boolean removeGrant(
            StorageKey key, long expectedRevision, StoragePrincipal principal) {
        return updateRecord(
                key, expectedRevision, record -> record.withoutGrant(principal));
    }

    public boolean setAutomationInsert(StorageKey key, long expectedRevision, boolean enabled) {
        return updateRecord(
                key, expectedRevision, record -> record.withAutomationInsert(enabled));
    }

    public boolean setAutomationExtract(StorageKey key, long expectedRevision, boolean enabled) {
        return updateRecord(
                key, expectedRevision, record -> record.withAutomationExtract(enabled));
    }

    public boolean setBrowserListed(StorageKey key, long expectedRevision, boolean listed) {
        return updateRecord(
                key, expectedRevision, record -> record.withBrowserListed(listed));
    }

    public boolean bindTemplate(
            StorageKey key, long expectedRevision,
            String templateId, StorageRecord.TemplateMode mode) {
        if (!templates.containsKey(templateId)) {
            return false;
        }
        return updateRecord(
                key, expectedRevision, record -> record.withTemplate(templateId, mode));
    }

    public boolean clearTemplate(StorageKey key, long expectedRevision) {
        return updateRecord(key, expectedRevision, StorageRecord::withoutTemplate);
    }

    // ---------------------------------------------------------------- 模板变更

    /**
     * 新建模板；ID 重复抛 {@link IllegalArgumentException}（原子性：不改任何数据）。
     */
    public StorageTemplate createTemplate(StorageTemplate template) {
        Objects.requireNonNull(template, "template");
        if (templates.containsKey(template.id())) {
            throw new IllegalArgumentException("template already exists: " + template.id());
        }
        templates.put(template.id(), template);
        setDirty();
        return template;
    }

    /**
     * 原子更新模板权限；模板不存在或 revision 不匹配返回 {@code false}。
     */
    public boolean updateTemplate(
            String id, long expectedRevision,
            Map<StoragePrincipal, StorageGrant> grants) {
        StorageTemplate current = templates.get(id);
        if (current == null || current.revision() != expectedRevision) {
            return false;
        }
        templates.put(id, current.withGrants(grants).touch(System.currentTimeMillis()));
        setDirty();
        return true;
    }

    /**
     * 原子删除模板：先以删除前的模板权限冻结所有 FOLLOW 绑定仓储（合并为
     * COPY、清除引用、递增 revision），再移除模板。返回被冻结的仓储数。
     */
    public int deleteTemplate(String id) {
        StorageTemplate template = templates.get(id);
        if (template == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int frozen = 0;
        for (Map.Entry<StorageKey, StorageRecord> entry
                : new ArrayList<>(storages.entrySet())) {
            StorageRecord record = entry.getValue();
            if (record.templateMode() == StorageRecord.TemplateMode.FOLLOW
                    && id.equals(record.templateBinding())) {
                Map<StoragePrincipal, StorageGrant> merged =
                        StorageTemplate.mergeGrants(template.grants(), record.grants());
                storages.put(entry.getKey(),
                        record.withFrozenCopy(merged).touch(now));
                frozen++;
            }
        }
        templates.remove(id);
        setDirty();
        return frozen;
    }

    /**
     * 引用修复：将绑定到已缺失模板的 FOLLOW 仓储冻结为 COPY（保留当前计算
     * 后的权限），避免突然公开或清空权限。返回修复数量。
     */
    public int repairTemplateReferences() {
        long now = System.currentTimeMillis();
        int repaired = 0;
        for (Map.Entry<StorageKey, StorageRecord> entry
                : new ArrayList<>(storages.entrySet())) {
            StorageRecord record = entry.getValue();
            if (record.templateMode() == StorageRecord.TemplateMode.FOLLOW
                    && !templates.containsKey(record.templateBinding())) {
                storages.put(entry.getKey(),
                        record.withFrozenCopy(record.grants()).touch(now));
                repaired++;
            }
        }
        if (repaired > 0) {
            setDirty();
        }
        return repaired;
    }

    // ---------------------------------------------------------------- 审计

    /**
     * 追加审计条目：自动分配递增 ID、截断 detail、超出容量时丢弃最旧条目。
     */
    public StorageAuditEntry appendAudit(
            long timestampEpochMillis, String storageKey,
            UUID actorId, String action, String detail) {
        StorageAuditEntry entry = new StorageAuditEntry(
                nextAuditId, timestampEpochMillis, storageKey, actorId, action, detail);
        nextAuditId++;
        audit.addLast(entry);
        while (audit.size() > auditCapacity) {
            audit.removeFirst();
        }
        setDirty();
        return entry;
    }

    /**
     * 调整审计容量并立即裁剪；容量必须 >= 1。
     */
    public void setAuditCapacity(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("audit capacity must be >= 1");
        }
        auditCapacity = capacity;
        while (audit.size() > auditCapacity) {
            audit.removeFirst();
        }
        setDirty();
    }

    // ---------------------------------------------------------------- 索引

    private void index(StorageKey key, int chunkX, int chunkZ) {
        chunkIndex.computeIfAbsent(
                        new ChunkKey(key.dimension(), chunkX, chunkZ),
                        ignored -> new LinkedHashSet<>())
                .add(key);
    }

    /**
     * 区块键：维度 + 区块坐标。
     */
    public record ChunkKey(String dimension, int chunkX, int chunkZ) {
        public ChunkKey {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    /**
     * 加载期判定上下文：维度与适配器 ID 校验器。
     */
    public record StorageLoadContext(
            Predicate<String> dimensionValidator,
            Predicate<String> adapterTypeValidator) {

        /** 全接受上下文（测试与容错加载用）。 */
        public static final StorageLoadContext ACCEPT_ALL =
                new StorageLoadContext(dimension -> true, adapterType -> true);

        public StorageLoadContext {
            Objects.requireNonNull(dimensionValidator, "dimensionValidator");
            Objects.requireNonNull(adapterTypeValidator, "adapterTypeValidator");
        }
    }

    // ---------------------------------------------------------------- 序列化

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return encode(this);
    }

    /**
     * 纯序列化：不依赖 {@code HolderLookup}，JVM 测试可直接调用。
     */
    static CompoundTag encode(StorageSavedData data) {
        CompoundTag root = new CompoundTag();
        root.putInt("version", DATA_VERSION);

        ListTag storagesTag = new ListTag();
        for (Map.Entry<StorageKey, StorageRecord> entry : data.storages.entrySet()) {
            storagesTag.add(encodeStorage(entry.getKey(), entry.getValue()));
        }
        root.put("storages", storagesTag);

        ListTag templatesTag = new ListTag();
        for (StorageTemplate template : data.templates.values()) {
            templatesTag.add(encodeTemplate(template));
        }
        root.put("templates", templatesTag);

        ListTag auditTag = new ListTag();
        for (StorageAuditEntry entry : data.audit) {
            auditTag.add(encodeAudit(entry));
        }
        root.put("audit", auditTag);

        ListTag indexTag = new ListTag();
        for (Map.Entry<ChunkKey, Set<StorageKey>> entry : data.chunkIndex.entrySet()) {
            indexTag.add(encodeChunk(entry.getKey(), entry.getValue()));
        }
        root.put("chunkIndex", indexTag);
        return root;
    }

    /**
     * 纯反序列化：按条目逐条容错解析，损坏条目跳过，其余保留。
     * 版本高于当前版本或低于 1 时返回空数据（不破坏文件，静默重来）。
     */
    static StorageSavedData decode(CompoundTag tag, StorageLoadContext context) {
        Objects.requireNonNull(context, "context");
        StorageSavedData data = new StorageSavedData();
        if (tag == null) {
            return data;
        }
        int version = tag.contains("version", Tag.TAG_INT) ? tag.getInt("version") : 1;
        if (version < 1 || version > DATA_VERSION) {
            LOGGER.warn("Poketrade storage data version {} unsupported (current {}); "
                    + "starting fresh", version, DATA_VERSION);
            return data;
        }

        ListTag storagesTag = tag.getList("storages", Tag.TAG_COMPOUND);
        for (int i = 0; i < storagesTag.size(); i++) {
            try {
                CompoundTag entryTag = storagesTag.getCompound(i);
                StorageKey key = StorageKey.parse(entryTag.getString("key")).orElse(null);
                if (key == null) {
                    continue;
                }
                // 非法维度或未知适配器 ID：跳过该条，不回退主世界。
                if (!context.dimensionValidator().test(key.dimension())) {
                    continue;
                }
                if (!context.adapterTypeValidator().test(key.adapterType())) {
                    continue;
                }
                StorageRecord record = decodeStorage(entryTag);
                data.storages.put(key, record);
            } catch (Exception e) {
                LOGGER.warn("Skipping corrupt storage entry {}: {}", i, e);
            }
        }

        ListTag templatesTag = tag.getList("templates", Tag.TAG_COMPOUND);
        for (int i = 0; i < templatesTag.size(); i++) {
            try {
                StorageTemplate template = decodeTemplate(templatesTag.getCompound(i));
                data.templates.put(template.id(), template);
            } catch (Exception e) {
                LOGGER.warn("Skipping corrupt template entry {}: {}", i, e);
            }
        }

        ListTag auditTag = tag.getList("audit", Tag.TAG_COMPOUND);
        long maxId = 0L;
        for (int i = 0; i < auditTag.size(); i++) {
            try {
                StorageAuditEntry entry = decodeAudit(auditTag.getCompound(i));
                data.audit.addLast(entry);
                maxId = Math.max(maxId, entry.id());
            } catch (Exception e) {
                LOGGER.warn("Skipping corrupt audit entry {}: {}", i, e);
            }
        }
        while (data.audit.size() > data.auditCapacity) {
            data.audit.removeFirst();
        }
        data.nextAuditId = maxId + 1;

        ListTag indexTag = tag.getList("chunkIndex", Tag.TAG_COMPOUND);
        for (int i = 0; i < indexTag.size(); i++) {
            try {
                CompoundTag entryTag = indexTag.getCompound(i);
                ChunkKey chunkKey = new ChunkKey(
                        entryTag.getString("dim"),
                        entryTag.getInt("x"),
                        entryTag.getInt("z"));
                ListTag keysTag = entryTag.getList("keys", Tag.TAG_STRING);
                for (int j = 0; j < keysTag.size(); j++) {
                    StorageKey key = StorageKey.parse(keysTag.getString(j)).orElse(null);
                    if (key == null || !data.storages.containsKey(key)) {
                        continue; // 引用修复：丢弃指向不存在仓储的索引键
                    }
                    data.chunkIndex
                            .computeIfAbsent(chunkKey, ignored -> new LinkedHashSet<>())
                            .add(key);
                }
            } catch (Exception e) {
                LOGGER.warn("Skipping corrupt chunk index entry {}: {}", i, e);
            }
        }

        int repaired = data.repairTemplateReferences();
        if (repaired > 0) {
            LOGGER.info("Repaired {} storage template references on load", repaired);
        }
        return data;
    }

    private static CompoundTag encodeStorage(StorageKey key, StorageRecord record) {
        CompoundTag tag = new CompoundTag();
        tag.putString("key", key.asString());
        tag.putUUID("ownerId", record.ownerId());
        tag.putString("ownerName", record.ownerName());
        tag.putString("displayName", record.displayName());
        ListTag grantsTag = new ListTag();
        for (Map.Entry<StoragePrincipal, StorageGrant> entry
                : record.grants().entrySet()) {
            grantsTag.add(encodeGrant(entry.getKey(), entry.getValue()));
        }
        tag.put("grants", grantsTag);
        if (record.templateBinding() != null) {
            tag.putString("templateBinding", record.templateBinding());
            tag.putString("templateMode", record.templateMode().name());
        }
        tag.putByte("automationInsert",
                (byte) (record.automationInsertEnabled() ? 1 : 0));
        tag.putByte("automationExtract",
                (byte) (record.automationExtractEnabled() ? 1 : 0));
        tag.putByte("listed", (byte) (record.listedInBrowser() ? 1 : 0));
        tag.putLong("createdAt", record.createdAtEpochMillis());
        tag.putLong("updatedAt", record.updatedAtEpochMillis());
        tag.putLong("revision", record.revision());
        return tag;
    }

    private static StorageRecord decodeStorage(CompoundTag tag) {
        UUID ownerId = tag.getUUID("ownerId");
        String ownerName = tag.getString("ownerName");
        String displayName = tag.getString("displayName");
        Map<StoragePrincipal, StorageGrant> grants =
                decodeGrants(tag.getList("grants", Tag.TAG_COMPOUND));
        String binding = tag.contains("templateBinding", Tag.TAG_STRING)
                ? tag.getString("templateBinding") : null;
        StorageRecord.TemplateMode mode = StorageRecord.TemplateMode.COPY;
        if (binding != null) {
            mode = StorageRecord.TemplateMode.valueOf(tag.getString("templateMode"));
        }
        boolean insert = tag.getByte("automationInsert") != 0;
        boolean extract = tag.getByte("automationExtract") != 0;
        boolean listed = !tag.contains("listed", Tag.TAG_BYTE) || tag.getByte("listed") != 0;
        long createdAt = tag.getLong("createdAt");
        long updatedAt = tag.getLong("updatedAt");
        long revision = tag.getLong("revision");
        return new StorageRecord(
                ownerId, ownerName, displayName, grants, binding, mode,
                insert, extract, listed, createdAt, updatedAt, revision);
    }

    private static CompoundTag encodeTemplate(StorageTemplate template) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", template.id());
        tag.putString("scope", template.scope().name());
        if (template.ownerId() != null) {
            tag.putUUID("ownerId", template.ownerId());
        }
        tag.putString("name", template.name());
        ListTag grantsTag = new ListTag();
        for (Map.Entry<StoragePrincipal, StorageGrant> entry
                : template.grants().entrySet()) {
            grantsTag.add(encodeGrant(entry.getKey(), entry.getValue()));
        }
        tag.put("grants", grantsTag);
        tag.putLong("createdAt", template.createdAtEpochMillis());
        tag.putLong("updatedAt", template.updatedAtEpochMillis());
        tag.putLong("revision", template.revision());
        return tag;
    }

    private static StorageTemplate decodeTemplate(CompoundTag tag) {
        String id = tag.getString("id");
        StorageTemplate.Scope scope =
                StorageTemplate.Scope.valueOf(tag.getString("scope"));
        UUID ownerId = scope == StorageTemplate.Scope.PLAYER
                ? tag.getUUID("ownerId") : null;
        String name = tag.getString("name");
        Map<StoragePrincipal, StorageGrant> grants =
                decodeGrants(tag.getList("grants", Tag.TAG_COMPOUND));
        long createdAt = tag.getLong("createdAt");
        long updatedAt = tag.getLong("updatedAt");
        long revision = tag.getLong("revision");
        return new StorageTemplate(
                id, scope, ownerId, name, grants, createdAt, updatedAt, revision);
    }

    private static CompoundTag encodeGrant(
            StoragePrincipal principal, StorageGrant grant) {
        CompoundTag tag = new CompoundTag();
        CompoundTag principalTag = new CompoundTag();
        if (principal instanceof StoragePrincipal.Player player) {
            principalTag.putString("kind", "player");
            principalTag.putUUID("uuid", player.uuid());
        } else if (principal instanceof StoragePrincipal.Group group) {
            principalTag.putString("kind", "group");
            principalTag.putString("provider", group.provider());
            principalTag.putString("id", group.id());
        } else {
            principalTag.putString("kind", "public");
        }
        tag.put("principal", principalTag);
        tag.put("allow", encodePermissions(grant.allow()));
        tag.put("deny", encodePermissions(grant.deny()));
        return tag;
    }

    private static Map<StoragePrincipal, StorageGrant> decodeGrants(ListTag list) {
        LinkedHashMap<StoragePrincipal, StorageGrant> grants = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            grants.put(
                    decodePrincipal(tag.getCompound("principal")),
                    new StorageGrant(
                            decodePermissions(tag.getList("allow", Tag.TAG_STRING)),
                            decodePermissions(tag.getList("deny", Tag.TAG_STRING))));
        }
        return grants;
    }

    private static StoragePrincipal decodePrincipal(CompoundTag tag) {
        String kind = tag.getString("kind");
        return switch (kind) {
            case "player" -> new StoragePrincipal.Player(tag.getUUID("uuid"));
            case "group" -> new StoragePrincipal.Group(
                    tag.getString("provider"), tag.getString("id"));
            case "public" -> new StoragePrincipal.Public();
            default -> throw new IllegalArgumentException(
                    "unknown principal kind: " + kind);
        };
    }

    private static ListTag encodePermissions(StoragePermissionSet set) {
        ListTag list = new ListTag();
        for (StoragePermission permission : StoragePermission.values()) {
            if (set.allows(permission)) {
                list.add(StringTag.valueOf(permission.name()));
            }
        }
        return list;
    }

    private static StoragePermissionSet decodePermissions(ListTag list) {
        EnumSet<StoragePermission> permissions =
                EnumSet.noneOf(StoragePermission.class);
        for (int i = 0; i < list.size(); i++) {
            try {
                permissions.add(StoragePermission.valueOf(list.getString(i)));
            } catch (IllegalArgumentException e) {
                // 未来版本新增的权限名：跳过，不做反向映射。
            }
        }
        return new StoragePermissionSet(permissions);
    }

    private static CompoundTag encodeAudit(StorageAuditEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("id", entry.id());
        tag.putLong("ts", entry.timestampEpochMillis());
        tag.putString("key", entry.storageKey());
        tag.putUUID("actor", entry.actorId());
        tag.putString("action", entry.action());
        tag.putString("detail", entry.detail());
        return tag;
    }

    private static StorageAuditEntry decodeAudit(CompoundTag tag) {
        return new StorageAuditEntry(
                tag.getLong("id"),
                tag.getLong("ts"),
                tag.getString("key"),
                tag.getUUID("actor"),
                tag.getString("action"),
                tag.getString("detail"));
    }

    private static CompoundTag encodeChunk(ChunkKey chunkKey, Set<StorageKey> keys) {
        CompoundTag tag = new CompoundTag();
        tag.putString("dim", chunkKey.dimension());
        tag.putInt("x", chunkKey.chunkX());
        tag.putInt("z", chunkKey.chunkZ());
        ListTag keysTag = new ListTag();
        for (StorageKey key : keys) {
            keysTag.add(StringTag.valueOf(key.asString()));
        }
        tag.put("keys", keysTag);
        return tag;
    }
}
