package com.pokeemc.trade.network;

import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.TradeCapability;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeStatus;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import net.minecraft.network.VarInt;
import net.minecraft.network.VarLong;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家交易网络包共享 codec（Task 8）。
 * <p>
 * 全部 codec 操作 {@link ByteBuf}（netty）而非 {@code RegistryFriendlyByteBuf}：
 * 一方面注册表 codec 只用于类型 ID，不触碰注册表上下文；另一方面可在纯 JVM
 * 单测中直接用 {@code Unpooled.buffer()} 做 round-trip 与恶意输入验证。
 */
public final class TradePayloadCodecs {

    private TradePayloadCodecs() {
    }

    public static final StreamCodec<ByteBuf, UUID> UUID_CODEC = StreamCodec.of(
            (buf, id) -> {
                buf.writeLong(id.getMostSignificantBits());
                buf.writeLong(id.getLeastSignificantBits());
            },
            buf -> new UUID(buf.readLong(), buf.readLong()));

    public static final StreamCodec<ByteBuf, TradeId> TRADE_ID = UUID_CODEC.map(TradeId::new, TradeId::value);

    public static final StreamCodec<ByteBuf, String> STRING_UTF8 = ByteBufCodecs.STRING_UTF8;

    /** 受限短字符串（≤16 字符）：storageKind、policyId 等协议标签（恶意输入防御） */
    public static final StreamCodec<ByteBuf, String> SHORT_STRING = StreamCodec.of(
            (buf, s) -> writeString(buf, s, 16),
            buf -> readString(buf, 16));

    /** 通用枚举 codec：按 ordinal 写单字节；越界解码抛 CorruptedFrameException */
    public static <E extends Enum<E>> StreamCodec<ByteBuf, E> enumCodec(E[] values) {
        return StreamCodec.of(
                (buf, v) -> buf.writeByte(v.ordinal()),
                buf -> {
                    int idx = buf.readUnsignedByte();
                    if (idx >= values.length) {
                        throw new CorruptedFrameException("invalid enum ordinal " + idx);
                    }
                    return values[idx];
                });
    }

    public static final StreamCodec<ByteBuf, DeliveryPreference.ItemDestination> ITEM_DEST =
            enumCodec(DeliveryPreference.ItemDestination.values());
    public static final StreamCodec<ByteBuf, DeliveryPreference.PokemonDestination> POKEMON_DEST =
            enumCodec(DeliveryPreference.PokemonDestination.values());
    public static final StreamCodec<ByteBuf, TradeStatus> TRADE_STATUS = enumCodec(TradeStatus.values());
    public static final StreamCodec<ByteBuf, TradeCapability> TRADE_CAPABILITY = enumCodec(TradeCapability.values());
    public static final StreamCodec<ByteBuf, AssetPageKind> ASSET_PAGE_KIND = enumCodec(AssetPageKind.values());
    public static final StreamCodec<ByteBuf, TradeError> TRADE_ERROR = enumCodec(TradeError.values());

    public static final StreamCodec<ByteBuf, DeliveryPreference> DELIVERY_PREFERENCE = StreamCodec.of(
            (buf, p) -> {
                ITEM_DEST.encode(buf, p.itemDestination());
                POKEMON_DEST.encode(buf, p.pokemonDestination());
            },
            buf -> new DeliveryPreference(ITEM_DEST.decode(buf), POKEMON_DEST.decode(buf)));

    public static final StreamCodec<ByteBuf, TradeFeeQuote.ItemFee> ITEM_FEE = StreamCodec.of(
            (buf, f) -> {
                STRING_UTF8.encode(buf, f.itemId());
                VarLong.write(buf, f.count());
                UUID_CODEC.encode(buf, f.chargedToPlayerId());
            },
            buf -> new TradeFeeQuote.ItemFee(
                    STRING_UTF8.decode(buf), VarLong.read(buf), UUID_CODEC.decode(buf)));

    /** 手续费 quote：字段与模型一一对应，无客户端计算路径 */
    public static final StreamCodec<ByteBuf, TradeFeeQuote> TRADE_FEE_QUOTE = StreamCodec.of(
            (buf, q) -> {
                UUID_CODEC.encode(buf, q.quoteId());
                UUID_CODEC.encode(buf, q.tradeId());
                VarLong.write(buf, q.quotedRevision());
                VarLong.write(buf, q.expiresAtEpochMillis());
                VarLong.write(buf, q.leftPkmFee());
                VarLong.write(buf, q.rightPkmFee());
                writeList(buf, q.itemFees(), ITEM_FEE);
                STRING_UTF8.encode(buf, q.policyId());
                VarInt.write(buf, q.policyVersion());
            },
            buf -> new TradeFeeQuote(
                    UUID_CODEC.decode(buf),
                    UUID_CODEC.decode(buf),
                    VarLong.read(buf),
                    VarLong.read(buf),
                    VarLong.read(buf),
                    VarLong.read(buf),
                    readList(buf, ITEM_FEE, 128),
                    STRING_UTF8.decode(buf),
                    VarInt.read(buf)));

    /** 列表 codec：长度用 VarInt，解码时校验硬上限（恶意输入防御） */
    public static <T> StreamCodec<ByteBuf, List<T>> boundedList(StreamCodec<ByteBuf, T> elem, int max) {
        return StreamCodec.of(
                (buf, list) -> writeList(buf, list, elem),
                buf -> readList(buf, elem, max));
    }

    private static <T> void writeList(ByteBuf buf, List<T> list, StreamCodec<ByteBuf, T> elem) {
        VarInt.write(buf, list.size());
        for (T t : list) {
            elem.encode(buf, t);
        }
    }

    private static <T> List<T> readList(ByteBuf buf, StreamCodec<ByteBuf, T> elem, int max) {
        int n = VarInt.read(buf);
        if (n < 0 || n > max) {
            throw new CorruptedFrameException("list length out of range: " + n);
        }
        List<T> out = new ArrayList<>(Math.min(n, 32));
        for (int i = 0; i < n; i++) {
            out.add(elem.decode(buf));
        }
        return out;
    }

    /** 写受限字符串（最大长度防御） */
    public static void writeString(ByteBuf buf, String s, int max) {
        if (s.length() > max) {
            throw new IllegalArgumentException("string too long: " + s.length());
        }
        VarInt.write(buf, s.length());
        buf.writeCharSequence(s, StandardCharsets.UTF_8);
    }

    /** 读受限字符串（恶意输入防御） */
    public static String readString(ByteBuf buf, int max) {
        int len = VarInt.read(buf);
        if (len < 0 || len > max) {
            throw new CorruptedFrameException("string length out of range: " + len);
        }
        return buf.readCharSequence(len, StandardCharsets.UTF_8).toString();
    }
}
