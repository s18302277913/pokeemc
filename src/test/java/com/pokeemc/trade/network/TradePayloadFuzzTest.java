package com.pokeemc.trade.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.CorruptedFrameException;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Task 13 步骤 1：payload codec 边界与模糊测试（计划 5.5.1）。
 *
 * <p>目标：任意随机字节输入都只能产生受控失败——{@link CorruptedFrameException}、
 * {@link IndexOutOfBoundsException} 等由网络栈以"丢弃数据包"方式处理的异常；
 * 绝不泄漏 {@link Error}（OOM / 栈溢出）、{@link NullPointerException} 或
 * {@link ClassCastException}（codec 类型混淆 bug），也绝不分配无界内存
 * （全部列表/字符串 codec 均有上限）。</p>
 */
class TradePayloadFuzzTest {

    private static final long SEED = 0xFEEDFACE_FEEDFACEL;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void randomBuffersNeverCrashPacketCodecs() {
        List<StreamCodec<ByteBuf, ?>> codecs = allCodecs();
        Random rng = new Random(SEED);
        for (int i = 0; i < 2_000; i++) {
            byte[] bytes = new byte[rng.nextInt(256)];
            rng.nextBytes(bytes);
            for (StreamCodec codec : codecs) {
                ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                buf.readerIndex(0);
                try {
                    codec.decode(buf);
                } catch (Throwable t) {
                    assertControlledFailure(t);
                } finally {
                    buf.release();
                }
            }
        }
    }

    @Test
    void randomBuffersNeverCrashBoundedList() {
        Random rng = new Random(SEED);
        StreamCodec<ByteBuf, Integer> intCodec = StreamCodec.of(
                (buf, v) -> buf.writeInt(v), ByteBuf::readInt);
        StreamCodec<ByteBuf, List<Integer>> bounded = TradePayloadCodecs.boundedList(intCodec, 64);
        for (int i = 0; i < 1_000; i++) {
            ByteBuf buf = Unpooled.buffer();
            // 随机长度前缀：可为正/负/超大/超小
            VarInt.write(buf, rng.nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE));
            byte[] body = new byte[rng.nextInt(64)];
            rng.nextBytes(body);
            buf.writeBytes(body);
            try {
                bounded.decode(buf);
            } catch (Throwable t) {
                assertControlledFailure(t);
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void extremeVarIntLengthsAreControlled() {
        // 5 字节 VarInt 边界：0x7FFFFFFF（极大）与 0xFFFFFFFF（-1）
        long[] lengths = {Integer.MAX_VALUE, Integer.MIN_VALUE, 0x7FFFFFFFL, -1L, -2L};
        for (long len : lengths) {
            ByteBuf buf = Unpooled.buffer();
            VarInt.write(buf, (int) len);
            buf.writeBytes(new byte[16]);
            try {
                TradePayloadCodecs.STRING_UTF8.decode(buf);
            } catch (Throwable t) {
                assertControlledFailure(t);
            } finally {
                buf.release();
            }
        }
    }

    // ------------------------------------------------------------------ 工具

    @SuppressWarnings("rawtypes")
    private static List<StreamCodec<ByteBuf, ?>> allCodecs() {
        List<StreamCodec<ByteBuf, ?>> out = new ArrayList<>();
        out.add(TradePayloadCodecs.UUID_CODEC);
        out.add(TradePayloadCodecs.TRADE_ID);
        out.add(TradePayloadCodecs.STRING_UTF8);
        out.add(TradePayloadCodecs.SHORT_STRING);
        out.add(TradePayloadCodecs.ITEM_DEST);
        out.add(TradePayloadCodecs.POKEMON_DEST);
        out.add(TradePayloadCodecs.TRADE_STATUS);
        out.add(TradePayloadCodecs.TRADE_CAPABILITY);
        out.add(TradePayloadCodecs.ASSET_PAGE_KIND);
        out.add(TradePayloadCodecs.TRADE_ERROR);
        out.add(TradePayloadCodecs.DELIVERY_PREFERENCE);
        out.add(TradePayloadCodecs.ITEM_FEE);
        out.add(TradePayloadCodecs.TRADE_FEE_QUOTE);
        out.add(CreateTradePacket.STREAM_CODEC);
        out.add(AcceptTradePacket.STREAM_CODEC);
        out.add(OfferItemPacket.STREAM_CODEC);
        out.add(OfferPkmPacket.STREAM_CODEC);
        out.add(OfferPokemonPacket.STREAM_CODEC);
        out.add(RemoveOfferAssetPacket.STREAM_CODEC);
        out.add(ConfirmTradePacket.STREAM_CODEC);
        out.add(CancelTradePacket.STREAM_CODEC);
        out.add(SetDeliveryPreferencePacket.STREAM_CODEC);
        out.add(RequestTradeDirectoryPacket.STREAM_CODEC);
        out.add(RequestTradeAssetPagePacket.STREAM_CODEC);
        out.add(TradeResultPacket.STREAM_CODEC);
        out.add(TradeSnapshotPacket.STREAM_CODEC);
        out.add(TradeDirectoryPacket.STREAM_CODEC);
        out.add(TradeAssetPagePacket.STREAM_CODEC);
        return out;
    }

    /** 受控失败：禁止致命 Error 与类型混淆 bug；允许网络栈的丢弃性异常。 */
    private static void assertControlledFailure(Throwable t) {
        boolean fatal = t instanceof Error
                || t instanceof NullPointerException
                || t instanceof ClassCastException;
        assertFalse(fatal, "uncontrolled failure from codec: " + t);
    }
}
