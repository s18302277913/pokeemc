package com.pokeemc.network;

import com.pokeemc.storage.StorageAuditEntry;
import com.pokeemc.storage.StorageGrant;
import com.pokeemc.storage.StoragePermission;
import com.pokeemc.storage.StoragePermissionSet;
import com.pokeemc.storage.StoragePrincipal;
import com.pokeemc.storage.StorageTemplate;
import com.poketrade.api.storage.StorageCapability;
import com.poketrade.api.storage.StorageDescriptor;
import com.poketrade.api.storage.StorageId;
import com.poketrade.api.storage.StorageItemSlot;
import com.poketrade.api.storage.StorageSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Task 9/10 网络载荷的共享编解码器。
 *
 * <p>所有记录类型均为纯 JVM 值对象，因此统一用手写 {@link StreamCodec}
 * （与 StorageMovePacket / StorageSellPacket 的既有风格一致），
 * 避免在 Value Objects 上引入 Minecraft 序列化依赖。</p>
 */
public final class StoragePayloadCodecs {

    private StoragePayloadCodecs() {
    }

    // ================= 基础助手 =================

    private static void writeString(ByteBuf buf, String s) {
        ByteBufCodecs.STRING_UTF8.encode(buf, s == null ? "" : s);
    }

    private static String readString(ByteBuf buf) {
        return ByteBufCodecs.STRING_UTF8.decode(buf);
    }

    private static void writeUuid(ByteBuf buf, UUID u) {
        buf.writeLong(u.getMostSignificantBits());
        buf.writeLong(u.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuf buf) {
        return new UUID(buf.readLong(), buf.readLong());
    }

    private static <T> void writeList(ByteBuf buf, List<T> list, BiConsumer<ByteBuf, T> writer) {
        buf.writeInt(list.size());
        for (T t : list) {
            writer.accept(buf, t);
        }
    }

    private static <T> List<T> readList(ByteBuf buf, Function<ByteBuf, T> reader) {
        int n = buf.readInt();
        List<T> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(reader.apply(buf));
        }
        return out;
    }

    private static <K, V> void writeMap(ByteBuf buf, Map<K, V> map,
                                        BiConsumer<ByteBuf, K> keyWriter,
                                        BiConsumer<ByteBuf, V> valueWriter) {
        buf.writeInt(map.size());
        for (Map.Entry<K, V> e : map.entrySet()) {
            keyWriter.accept(buf, e.getKey());
            valueWriter.accept(buf, e.getValue());
        }
    }

    private static <K, V> Map<K, V> readMap(ByteBuf buf,
                                            Function<ByteBuf, K> keyReader,
                                            Function<ByteBuf, V> valueReader) {
        int n = buf.readInt();
        Map<K, V> out = new LinkedHashMap<>(n);
        for (int i = 0; i < n; i++) {
            out.put(keyReader.apply(buf), valueReader.apply(buf));
        }
        return out;
    }

    private static void writePermMask(ByteBuf buf, EnumSet<StoragePermission> set) {
        int m = 0;
        for (StoragePermission p : set) {
            m |= 1 << p.ordinal();
        }
        buf.writeByte(m);
    }

    private static EnumSet<StoragePermission> readPermMask(ByteBuf buf) {
        int m = buf.readByte();
        EnumSet<StoragePermission> out = EnumSet.noneOf(StoragePermission.class);
        for (StoragePermission p : StoragePermission.values()) {
            if ((m & (1 << p.ordinal())) != 0) {
                out.add(p);
            }
        }
        return out;
    }

    private static void writeCapMask(ByteBuf buf, java.util.Collection<StorageCapability> caps) {
        int m = 0;
        for (StorageCapability c : caps) {
            m |= 1 << c.ordinal();
        }
        buf.writeByte(m);
    }

    private static EnumSet<StorageCapability> readCapMask(ByteBuf buf) {
        int m = buf.readByte();
        EnumSet<StorageCapability> out = EnumSet.noneOf(StorageCapability.class);
        for (StorageCapability c : StorageCapability.values()) {
            if ((m & (1 << c.ordinal())) != 0) {
                out.add(c);
            }
        }
        return out;
    }

    // ================= 值对象编解码器 =================

    /** StorageId：dimension / adapterType / location 三个字符串。 */
    public static final StreamCodec<ByteBuf, StorageId> STORAGE_ID = StreamCodec.of(
            (buf, id) -> {
                writeString(buf, id.dimension());
                writeString(buf, id.adapterType());
                writeString(buf, id.location());
            },
            buf -> new StorageId(readString(buf), readString(buf), readString(buf)));

    /** 六项权限的位掩码。 */
    public static final StreamCodec<ByteBuf, EnumSet<StoragePermission>> PERMISSION_SET =
            StreamCodec.of(StoragePayloadCodecs::writePermMask, StoragePayloadCodecs::readPermMask);

    /** 能力位掩码。 */
    public static final StreamCodec<ByteBuf, EnumSet<StorageCapability>> CAPABILITY_SET =
            StreamCodec.of(StoragePayloadCodecs::writeCapMask, StoragePayloadCodecs::readCapMask);

    public static final StreamCodec<ByteBuf, StorageItemSlot> ITEM_SLOT = StreamCodec.of(
            (buf, s) -> {
                buf.writeInt(s.slotIndex());
                writeString(buf, s.itemId());
                buf.writeInt(s.count());
                buf.writeLong(s.fingerprint());
            },
            buf -> new StorageItemSlot(buf.readInt(), readString(buf), buf.readInt(), buf.readLong()));

    /** 槽位表：slotIndex → StorageItemSlot。 */
    public static final StreamCodec<ByteBuf, Map<Integer, StorageItemSlot>> SLOT_MAP =
            StreamCodec.of(
                    (buf, map) -> writeMap(buf, map, ByteBuf::writeInt, ITEM_SLOT::encode),
                    buf -> readMap(buf, ByteBuf::readInt, ITEM_SLOT::decode));

    public static final StreamCodec<ByteBuf, StorageSnapshot> SNAPSHOT = StreamCodec.of(
            (buf, s) -> {
                STORAGE_ID.encode(buf, s.storageId());
                buf.writeLong(s.revision());
                SLOT_MAP.encode(buf, s.slots());
            },
            buf -> new StorageSnapshot(STORAGE_ID.decode(buf), buf.readLong(), SLOT_MAP.decode(buf)));

    public static final StreamCodec<ByteBuf, StorageDescriptor> DESCRIPTOR = StreamCodec.of(
            (buf, d) -> {
                STORAGE_ID.encode(buf, d.storageId());
                writeString(buf, d.displayName());
                buf.writeInt(d.distance());
                buf.writeBoolean(d.claimed());
                if (d.ownerId() != null) {
                    buf.writeBoolean(true);
                    writeUuid(buf, d.ownerId());
                } else {
                    buf.writeBoolean(false);
                }
                writeCapMask(buf, d.capabilities());
                buf.writeInt(d.slotCount());
                buf.writeInt(d.usedSlots());
                buf.writeLong(d.revision());
                buf.writeBoolean(d.scanComplete());
            },
            buf -> {
                StorageId id = STORAGE_ID.decode(buf);
                String name = readString(buf);
                int distance = buf.readInt();
                boolean claimed = buf.readBoolean();
                UUID ownerId = buf.readBoolean() ? readUuid(buf) : null;
                EnumSet<StorageCapability> caps = readCapMask(buf);
                int slotCount = buf.readInt();
                int usedSlots = buf.readInt();
                long revision = buf.readLong();
                boolean scanComplete = buf.readBoolean();
                return new StorageDescriptor(id, name, distance, claimed, ownerId, caps,
                        slotCount, usedSlots, revision, scanComplete);
            });

    public static final StreamCodec<ByteBuf, List<StorageDescriptor>> DESCRIPTOR_LIST =
            StreamCodec.of(
                    (buf, list) -> writeList(buf, list, DESCRIPTOR::encode),
                    buf -> readList(buf, DESCRIPTOR::decode));

    public static final StreamCodec<ByteBuf, StoragePrincipal> PRINCIPAL = StreamCodec.of(
            (buf, p) -> {
                if (p instanceof StoragePrincipal.Player pl) {
                    buf.writeByte(0);
                    writeUuid(buf, pl.uuid());
                } else if (p instanceof StoragePrincipal.Public) {
                    buf.writeByte(1);
                } else if (p instanceof StoragePrincipal.Group gr) {
                    buf.writeByte(2);
                    writeString(buf, gr.provider());
                    writeString(buf, gr.id());
                } else {
                    throw new IllegalArgumentException("未知主体类型: " + p);
                }
            },
            buf -> switch (buf.readByte()) {
                case 0 -> new StoragePrincipal.Player(readUuid(buf));
                case 1 -> new StoragePrincipal.Public();
                case 2 -> new StoragePrincipal.Group(readString(buf), readString(buf));
                default -> throw new IllegalArgumentException("未知主体类型标记");
            });

    public static final StreamCodec<ByteBuf, StorageGrant> GRANT = StreamCodec.of(
            (buf, g) -> {
                writePermMask(buf, g.allow().values());
                writePermMask(buf, g.deny().values());
            },
            buf -> new StorageGrant(
                    new StoragePermissionSet(readPermMask(buf)),
                    new StoragePermissionSet(readPermMask(buf))));

    public static final StreamCodec<ByteBuf, Map<StoragePrincipal, StorageGrant>> GRANT_MAP =
            StreamCodec.of(
                    (buf, map) -> writeMap(buf, map, PRINCIPAL::encode, GRANT::encode),
                    buf -> readMap(buf, PRINCIPAL::decode, GRANT::decode));

    public static final StreamCodec<ByteBuf, StorageAuditEntry> AUDIT_ENTRY = StreamCodec.of(
            (buf, e) -> {
                buf.writeLong(e.id());
                buf.writeLong(e.timestampEpochMillis());
                writeString(buf, e.storageKey());
                writeUuid(buf, e.actorId());
                writeString(buf, e.action());
                writeString(buf, e.detail());
            },
            buf -> new StorageAuditEntry(
                    buf.readLong(), buf.readLong(), readString(buf),
                    readUuid(buf), readString(buf), readString(buf)));

    public static final StreamCodec<ByteBuf, List<StorageAuditEntry>> AUDIT_LIST =
            StreamCodec.of(
                    (buf, list) -> writeList(buf, list, AUDIT_ENTRY::encode),
                    buf -> readList(buf, AUDIT_ENTRY::decode));

    public static final StreamCodec<ByteBuf, StorageTemplate> TEMPLATE = StreamCodec.of(
            (buf, t) -> {
                writeString(buf, t.id());
                buf.writeByte(t.scope().ordinal());
                if (t.ownerId() != null) {
                    buf.writeBoolean(true);
                    writeUuid(buf, t.ownerId());
                } else {
                    buf.writeBoolean(false);
                }
                writeString(buf, t.name());
                GRANT_MAP.encode(buf, t.grants());
                buf.writeLong(t.createdAtEpochMillis());
                buf.writeLong(t.updatedAtEpochMillis());
                buf.writeLong(t.revision());
            },
            buf -> {
                String id = readString(buf);
                StorageTemplate.Scope scope = StorageTemplate.Scope.values()[buf.readByte()];
                UUID ownerId = buf.readBoolean() ? readUuid(buf) : null;
                String name = readString(buf);
                Map<StoragePrincipal, StorageGrant> grants = GRANT_MAP.decode(buf);
                long createdAt = buf.readLong();
                long updatedAt = buf.readLong();
                long revision = buf.readLong();
                return new StorageTemplate(id, scope, ownerId, name, grants, createdAt, updatedAt, revision);
            });

    public static final StreamCodec<ByteBuf, List<StorageTemplate>> TEMPLATE_LIST =
            StreamCodec.of(
                    (buf, list) -> writeList(buf, list, TEMPLATE::encode),
                    buf -> readList(buf, TEMPLATE::decode));
}
