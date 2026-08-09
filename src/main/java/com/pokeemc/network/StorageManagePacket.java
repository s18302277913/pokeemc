package com.pokeemc.network;

import com.pokeemc.PokeEMC;
import com.pokeemc.menu.StorageBrowserMenu;
import com.pokeemc.storage.StorageAccessService;
import com.pokeemc.storage.StorageAuditEntry;
import com.pokeemc.storage.StorageGrant;
import com.pokeemc.storage.StorageKey;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StoragePrincipal;
import com.pokeemc.storage.StorageRecord;
import com.pokeemc.storage.StorageSavedData;
import com.pokeemc.storage.StorageServices;
import com.pokeemc.storage.StorageTemplate;
import com.poketrade.api.storage.StorageId;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端 -> 服务器：仓储管理（ACL 读取/更新、模板应用/解除、自动化、重命名）。
 *
 * <p>读取（{@link ManageAction#GET_DETAILS}）不经 {@code MANAGE} 校验，但响应中
 * 的私有 ACL、自动化设置与审计摘要仅在调用者拥有 {@code MANAGE} 时下发
 * （服务端过滤，客户端不可绕过）。全部写入动作都要求 {@code MANAGE}，并携带
 * {@code expectedRevision} 做并发控制；冲突时返回最新服务端状态，客户端保留
 * 用户草稿供重新应用。</p>
 */
public record StorageManagePacket(
        String sessionId,
        StorageId storageId,
        long expectedRevision,
        ManageAction action,
        Map<StoragePrincipal, StorageGrant> grants,
        String templateId,
        StorageRecord.TemplateMode templateMode,
        String displayName,
        Boolean automationInsert,
        Boolean automationExtract) implements CustomPacketPayload {

    public static final int MAX_SESSION_FIELD_LENGTH = 64;
    public static final int MAX_AUDIT_SUMMARY = 10;
    public static final String CODE_OK = "ok";
    public static final String CODE_PERMISSION_DENIED = "permission_denied";
    public static final String CODE_REVISION_CONFLICT = "revision_conflict";
    public static final String CODE_TEMPLATE_NOT_FOUND = "template_not_found";
    public static final String CODE_TEMPLATE_FORBIDDEN = "template_forbidden";
    public static final String CODE_INVALID_REQUEST = "invalid_request";
    public static final String CODE_STORAGE_NOT_FOUND = "storage_not_found";

    /** 管理动作。 */
    public enum ManageAction {
        GET_DETAILS,
        PUT_GRANTS,
        APPLY_TEMPLATE,
        CLEAR_TEMPLATE,
        SET_AUTOMATION,
        RENAME
    }

    public static final Type<StorageManagePacket> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_manage"));

    private static final StreamCodec<ByteBuf, ManageAction> ACTION_CODEC =
            ByteBufCodecs.BYTE.map(b -> ManageAction.values()[b], action -> (byte) action.ordinal());

    private static final StreamCodec<ByteBuf, StorageRecord.TemplateMode> MODE_CODEC =
            ByteBufCodecs.BYTE.map(b -> StorageRecord.TemplateMode.values()[b],
                    mode -> (byte) mode.ordinal());

    private static final StreamCodec<ByteBuf, Boolean> OPTIONAL_BOOL = new StreamCodec<>() {
        @Override
        public Boolean decode(ByteBuf buf) {
            byte b = buf.readByte();
            return b == 0 ? null : b == 1;
        }

        @Override
        public void encode(ByteBuf buf, Boolean value) {
            buf.writeByte(value == null ? 0 : (value ? 1 : 2));
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageManagePacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public StorageManagePacket decode(RegistryFriendlyByteBuf buf) {
                    String sessionId = ByteBufCodecs.STRING_UTF8.decode(buf);
                    StorageId storageId = StoragePayloadCodecs.STORAGE_ID.decode(buf);
                    long expectedRevision = buf.readLong();
                    ManageAction action = ACTION_CODEC.decode(buf);
                    Map<StoragePrincipal, StorageGrant> grants = action == ManageAction.PUT_GRANTS
                            ? StoragePayloadCodecs.GRANT_MAP.decode(buf) : Map.of();
                    String templateId = action == ManageAction.APPLY_TEMPLATE
                            ? ByteBufCodecs.STRING_UTF8.decode(buf) : null;
                    StorageRecord.TemplateMode mode = action == ManageAction.APPLY_TEMPLATE
                            ? MODE_CODEC.decode(buf) : null;
                    String displayName = action == ManageAction.RENAME
                            ? ByteBufCodecs.STRING_UTF8.decode(buf) : null;
                    Boolean automationInsert = action == ManageAction.SET_AUTOMATION
                            ? OPTIONAL_BOOL.decode(buf) : null;
                    Boolean automationExtract = action == ManageAction.SET_AUTOMATION
                            ? OPTIONAL_BOOL.decode(buf) : null;
                    return new StorageManagePacket(sessionId, storageId, expectedRevision,
                            action, grants, templateId, mode, displayName,
                            automationInsert, automationExtract);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, StorageManagePacket packet) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                    StoragePayloadCodecs.STORAGE_ID.encode(buf, packet.storageId());
                    buf.writeLong(packet.expectedRevision());
                    ACTION_CODEC.encode(buf, packet.action());
                    if (packet.action() == ManageAction.PUT_GRANTS) {
                        StoragePayloadCodecs.GRANT_MAP.encode(buf, packet.grants());
                    } else if (packet.action() == ManageAction.APPLY_TEMPLATE) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.templateId());
                        MODE_CODEC.encode(buf, packet.templateMode());
                    } else if (packet.action() == ManageAction.RENAME) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.displayName());
                    } else if (packet.action() == ManageAction.SET_AUTOMATION) {
                        OPTIONAL_BOOL.encode(buf, packet.automationInsert());
                        OPTIONAL_BOOL.encode(buf, packet.automationExtract());
                    }
                }
            };

    @Override
    public Type<StorageManagePacket> type() {
        return TYPE;
    }

    // ---------------------------------------------------------------- 响应

    /**
     * 服务端 -> 客户端：管理详情或写入结果。
     *
     * <p>{@code grants} 在无 {@code MANAGE} 时恒为空表（私有 ACL 不下发）；
     * {@code audit} 仅在拥有 {@code MANAGE} 时下发最近条目。写入后返回最新
     * revision，冲突时 {@code code=revision_conflict} 且携带服务端最新值。</p>
     */
    public record Response(
            String sessionId,
            StorageId storageId,
            long revision,
            boolean canManage,
            Map<StoragePrincipal, StorageGrant> grants,
            String templateBinding,
            StorageRecord.TemplateMode templateMode,
            boolean automationInsert,
            boolean automationExtract,
            boolean listed,
            List<StorageAuditEntry> audit,
            List<StorageTemplate> templates,
            String code,
            String message) implements CustomPacketPayload {

        public static final Type<Response> RESPONSE_TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(PokeEMC.MODID, "storage_manage_response"));

        public static final StreamCodec<RegistryFriendlyByteBuf, Response> RESPONSE_CODEC =
                new StreamCodec<>() {
                    @Override
                    public Response decode(RegistryFriendlyByteBuf buf) {
                        String sessionId = ByteBufCodecs.STRING_UTF8.decode(buf);
                        StorageId storageId = StoragePayloadCodecs.STORAGE_ID.decode(buf);
                        long revision = buf.readLong();
                        boolean canManage = buf.readBoolean();
                        Map<StoragePrincipal, StorageGrant> grants =
                                StoragePayloadCodecs.GRANT_MAP.decode(buf);
                        String binding = buf.readBoolean()
                                ? ByteBufCodecs.STRING_UTF8.decode(buf) : null;
                        StorageRecord.TemplateMode mode = buf.readBoolean()
                                ? MODE_CODEC.decode(buf) : null;
                        boolean insert = buf.readBoolean();
                        boolean extract = buf.readBoolean();
                        boolean listed = buf.readBoolean();
                        List<StorageAuditEntry> audit = StoragePayloadCodecs.AUDIT_LIST.decode(buf);
                        List<StorageTemplate> templates =
                                StoragePayloadCodecs.TEMPLATE_LIST.decode(buf);
                        String code = ByteBufCodecs.STRING_UTF8.decode(buf);
                        String message = ByteBufCodecs.STRING_UTF8.decode(buf);
                        return new Response(sessionId, storageId, revision, canManage,
                                grants, binding, mode, insert, extract, listed,
                                audit, templates, code, message);
                    }

                    @Override
                    public void encode(RegistryFriendlyByteBuf buf, Response packet) {
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.sessionId());
                        StoragePayloadCodecs.STORAGE_ID.encode(buf, packet.storageId());
                        buf.writeLong(packet.revision());
                        buf.writeBoolean(packet.canManage());
                        StoragePayloadCodecs.GRANT_MAP.encode(buf, packet.grants());
                        if (packet.templateBinding() != null) {
                            buf.writeBoolean(true);
                            ByteBufCodecs.STRING_UTF8.encode(buf, packet.templateBinding());
                        } else {
                            buf.writeBoolean(false);
                        }
                        if (packet.templateMode() != null) {
                            buf.writeBoolean(true);
                            MODE_CODEC.encode(buf, packet.templateMode());
                        } else {
                            buf.writeBoolean(false);
                        }
                        buf.writeBoolean(packet.automationInsert());
                        buf.writeBoolean(packet.automationExtract());
                        buf.writeBoolean(packet.listed());
                        StoragePayloadCodecs.AUDIT_LIST.encode(buf, packet.audit());
                        StoragePayloadCodecs.TEMPLATE_LIST.encode(buf, packet.templates());
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.code());
                        ByteBufCodecs.STRING_UTF8.encode(buf, packet.message());
                    }
                };

        @Override
        public Type<Response> type() {
            return RESPONSE_TYPE;
        }

        public static void handleResponse(Response packet, IPayloadContext context) {
            context.enqueueWork(() -> {
                if (net.minecraft.client.Minecraft.getInstance().screen
                        instanceof com.pokeemc.client.BrowserHost host) {
                    host.onManageResponse(packet);
                }
            });
        }
    }

    // ---------------------------------------------------------------- 服务端执行

    public static void handle(StorageManagePacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                Response response = executeManage(player, packet);
                if (response != null) {
                    PacketDistributor.sendToPlayer(player, response);
                }
            }
        });
    }

    /**
     * 服务端执行入口（独立静态方法便于测试）。
     */
    public static Response executeManage(ServerPlayer player, StorageManagePacket packet) {
        if (packet.sessionId() == null || packet.sessionId().isBlank()
                || packet.sessionId().length() > MAX_SESSION_FIELD_LENGTH) {
            return null;
        }
        if (!(player.containerMenu instanceof StorageBrowserMenu)) {
            return null; // 必须处于仓储浏览器/转化桌菜单会话
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        StorageSavedData data = server.overworld().getDataStorage()
                .computeIfAbsent(StorageSavedData.factory(), StorageSavedData.DATA_NAME);
        StorageId sid = packet.storageId();
        StorageKey key = StorageKey.of(sid.dimension(), sid.adapterType(), sid.location());

        UUID actorId = player.getUUID();

        // 读取：不要求 MANAGE，但私有字段按权限过滤
        if (packet.action() == ManageAction.GET_DETAILS) {
            StorageRecord record = data.getRecord(key).orElse(null);
            if (record == null) {
                return new Response(packet.sessionId(), sid, -1, false,
                        Map.of(), null, null, false, false, false,
                        List.of(), applicableTemplates(data, actorId),
                        CODE_STORAGE_NOT_FOUND, "storage not found");
            }
            return buildResponse(packet, data, key, record, actorId, CODE_OK, "");
        }

        // 写入：全部要求 MANAGE
        StorageRecord record = data.getRecord(key).orElse(null);
        if (record == null) {
            return new Response(packet.sessionId(), sid, -1, false,
                    Map.of(), null, null, false, false, false,
                    List.of(), applicableTemplates(data, actorId),
                    CODE_STORAGE_NOT_FOUND, "storage not found");
        }
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        if (!StorageServices.access().canManage(actorId, snapshot)) {
            return buildResponse(packet, data, key, record, actorId,
                    CODE_PERMISSION_DENIED, "manage permission required");
        }
        if (packet.expectedRevision() != record.revision()) {
            return buildResponse(packet, data, key, record, actorId,
                    CODE_REVISION_CONFLICT, "revision conflict: expected "
                            + packet.expectedRevision() + " but got " + record.revision());
        }

        long now = System.currentTimeMillis();
        String auditAction;
        String auditDetail;
        switch (packet.action()) {
            case PUT_GRANTS -> {
                if (packet.grants() == null) {
                    return buildResponse(packet, data, key, record, actorId,
                            CODE_INVALID_REQUEST, "grants missing");
                }
                Map<StoragePrincipal, StorageGrant> grants = sanitizeGrants(packet.grants());
                boolean ok = data.updateRecord(key, record.revision(),
                        r -> copyWith(r, grants));
                if (!ok) {
                    return buildResponse(packet, data, key, data.getRecord(key).orElse(record),
                            actorId, CODE_REVISION_CONFLICT, "revision conflict on apply");
                }
                auditAction = "manage_grants";
                auditDetail = "更新 ACL：" + grants.size() + " 个主体";
            }
            case APPLY_TEMPLATE -> {
                StorageTemplate template = data.getTemplate(packet.templateId()).orElse(null);
                if (template == null) {
                    return buildResponse(packet, data, key, record, actorId,
                            CODE_TEMPLATE_NOT_FOUND, "template not found: " + packet.templateId());
                }
                if (!templateApplicable(template, actorId)) {
                    return buildResponse(packet, data, key, record, actorId,
                            CODE_TEMPLATE_FORBIDDEN, "cannot apply template owned by another player");
                }
                Map<StoragePrincipal, StorageGrant> target =
                        packet.templateMode() == StorageRecord.TemplateMode.FOLLOW
                                ? StorageTemplate.mergeGrants(template.grants(), record.grants())
                                : template.grants();
                boolean ok = data.updateRecord(key, record.revision(),
                        r -> copyWith(r, target, packet.templateId(), packet.templateMode()));
                if (!ok) {
                    return buildResponse(packet, data, key, data.getRecord(key).orElse(record),
                            actorId, CODE_REVISION_CONFLICT, "revision conflict on apply");
                }
                auditAction = "template_apply";
                auditDetail = "应用模板 " + packet.templateId()
                        + "（" + packet.templateMode() + "）";
            }
            case CLEAR_TEMPLATE -> {
                boolean ok = data.updateRecord(key, record.revision(), StorageRecord::withoutTemplate);
                if (!ok) {
                    return buildResponse(packet, data, key, data.getRecord(key).orElse(record),
                            actorId, CODE_REVISION_CONFLICT, "revision conflict on apply");
                }
                auditAction = "template_clear";
                auditDetail = "解除模板绑定";
            }
            case SET_AUTOMATION -> {
                boolean insert = packet.automationInsert() == null
                        ? record.automationInsertEnabled() : packet.automationInsert();
                boolean extract = packet.automationExtract() == null
                        ? record.automationExtractEnabled() : packet.automationExtract();
                boolean ok = data.updateRecord(key, record.revision(), r -> new StorageRecord(
                        r.ownerId(), r.ownerName(), r.displayName(), r.grants(),
                        r.templateBinding(), r.templateMode(),
                        insert, extract, r.listedInBrowser(),
                        r.createdAtEpochMillis(), r.updatedAtEpochMillis(), r.revision()));
                if (!ok) {
                    return buildResponse(packet, data, key, data.getRecord(key).orElse(record),
                            actorId, CODE_REVISION_CONFLICT, "revision conflict on apply");
                }
                auditAction = "automation";
                auditDetail = "自动化 插入=" + insert + " 取出=" + extract;
            }
            case RENAME -> {
                String name = packet.displayName() == null ? "" : packet.displayName().trim();
                if (name.isEmpty() || name.length() > StorageRecord.MAX_DISPLAY_NAME_LENGTH) {
                    return buildResponse(packet, data, key, record, actorId,
                            CODE_INVALID_REQUEST, "invalid display name");
                }
                boolean ok = data.updateRecord(key, record.revision(), r -> r.renamed(name));
                if (!ok) {
                    return buildResponse(packet, data, key, data.getRecord(key).orElse(record),
                            actorId, CODE_REVISION_CONFLICT, "revision conflict on apply");
                }
                auditAction = "rename";
                auditDetail = "重命名为 " + name;
            }
            default -> {
                return buildResponse(packet, data, key, record, actorId,
                        CODE_INVALID_REQUEST, "unsupported action");
            }
        }

        data.appendAudit(now, key.asString(), actorId, auditAction, auditDetail);
        StorageRecord latest = data.getRecord(key).orElse(record);
        return buildResponse(packet, data, key, latest, actorId, CODE_OK, "");
    }

    // ---------------------------------------------------------------- 内部

    /** 校验并清洗客户端提交的授权表（拒绝 null 主体/授权，避免污染持久化数据）。 */
    private static Map<StoragePrincipal, StorageGrant> sanitizeGrants(
            Map<StoragePrincipal, StorageGrant> input) {
        LinkedHashMap<StoragePrincipal, StorageGrant> out = new LinkedHashMap<>();
        for (Map.Entry<StoragePrincipal, StorageGrant> e : input.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static StorageRecord copyWith(
            StorageRecord r, Map<StoragePrincipal, StorageGrant> grants) {
        return new StorageRecord(
                r.ownerId(), r.ownerName(), r.displayName(), grants,
                r.templateBinding(), r.templateMode(),
                r.automationInsertEnabled(), r.automationExtractEnabled(), r.listedInBrowser(),
                r.createdAtEpochMillis(), r.updatedAtEpochMillis(), r.revision());
    }

    private static StorageRecord copyWith(
            StorageRecord r, Map<StoragePrincipal, StorageGrant> grants,
            String templateId, StorageRecord.TemplateMode mode) {
        return new StorageRecord(
                r.ownerId(), r.ownerName(), r.displayName(), grants,
                templateId, mode,
                r.automationInsertEnabled(), r.automationExtractEnabled(), r.listedInBrowser(),
                r.createdAtEpochMillis(), r.updatedAtEpochMillis(), r.revision());
    }

    /** 模板是否可被该玩家应用：PLAYER 模板仅所有者，SERVER 模板所有人。 */
    public static boolean templateApplicable(StorageTemplate template, UUID actorId) {
        return template.scope() == StorageTemplate.Scope.SERVER
                || (template.scope() == StorageTemplate.Scope.PLAYER
                && actorId.equals(template.ownerId()));
    }

    private static List<StorageTemplate> applicableTemplates(
            StorageSavedData data, UUID actorId) {
        List<StorageTemplate> out = new ArrayList<>();
        for (StorageTemplate template : data.templatesView().values()) {
            if (templateApplicable(template, actorId)) {
                out.add(template);
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static Response buildResponse(
            StorageManagePacket packet, StorageSavedData data,
            StorageKey key, StorageRecord record, UUID actorId,
            String code, String message) {
        StorageAccessService.AccessSnapshot snapshot =
                new StorageAccessService.AccessSnapshot(record.ownerId(), record.grants());
        boolean canManage = StorageServices.access().canManage(actorId, snapshot);
        Map<StoragePrincipal, StorageGrant> grants =
                canManage ? record.grants() : Map.of();

        List<StorageAuditEntry> audit = List.of();
        if (canManage) {
            List<StorageAuditEntry> recent = new ArrayList<>();
            for (StorageAuditEntry entry : data.auditView()) {
                if (entry.storageKey().equals(key.asString())) {
                    recent.add(entry);
                }
            }
            int from = Math.max(0, recent.size() - MAX_AUDIT_SUMMARY);
            audit = Collections.unmodifiableList(recent.subList(from, recent.size()));
        }

        return new Response(
                packet.sessionId(), packet.storageId(), record.revision(), canManage,
                grants, record.templateBinding(),
                record.templateBinding() == null ? null : record.templateMode(),
                record.automationInsertEnabled(), record.automationExtractEnabled(),
                record.listedInBrowser(), audit, applicableTemplates(data, actorId),
                code, message);
    }
}
