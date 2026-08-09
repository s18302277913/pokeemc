package com.pokeemc.trade.persistence;

import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeStatus;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 13 步骤 1：NBT codec 边界与模糊测试（计划 5.5.1）。
 *
 * <p>目标：任何恶意/损坏的 NBT 输入都必须产生稳定行为——要么成功解码，
 * 要么抛出文档化的受控异常（由 {@link TradeSavedData#load} 的调用方记录后
 * 安全失败），绝不泄漏未捕获的致命错误（OOM / 栈溢出 / NPE / CCE），
 * 也绝不分配无界内存（超大集合在分配模型对象之前被拒绝）。</p>
 */
class TradeNbtCodecFuzzTest {

    private static final long SEED = 0xC0DEC0DEC0DEC0DEL;

    private static final UUID A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    /** schema 真实键 + 随机键，提高模糊命中率 */
    private static final String[] SCHEMA_KEYS = {
            "schema_version", "trades", "inbox", "receipts", "operations", "preferences",
            "tradeId", "left", "right", "status", "revision", "leftOffer", "rightOffer",
            "leftConfirmedRevision", "rightConfirmedRevision", "leftPreference", "rightPreference",
            "feeQuote", "createdAt", "updatedAt", "expiresAt", "lockDeadline", "failureError",
            "failureDetail", "items", "pkm", "pokemon", "kind", "assetId", "owner", "stack",
            "amount", "debitOperationId", "debited", "pokemonId", "pokemonNbt", "sourceStorage",
            "sourceBox", "sourceSlot", "item", "quoteId", "leftFee", "rightFee", "itemFees",
            "policyId", "policyVersion", "itemId", "count", "entryId", "recipientId", "asset",
            "state", "operationId", "opType", "data", "error"
    };

    // ------------------------------------------------------------------ 随机模糊

    @Test
    void randomMalformedRootsNeverCrashLoader() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 3_000; i++) {
            CompoundTag root = randomCompound(rng, 0);
            try {
                TradeSavedData.load(root, null);
            } catch (Throwable t) {
                assertControlledFailure(t);
            }
        }
    }

    @Test
    void randomMalformedTradeEntriesAreSkippedOrControlled() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 500; i++) {
            CompoundTag root = new CompoundTag();
            root.putInt("schema_version", 1);
            ListTag trades = new ListTag();
            for (int t = 0; t < 8; t++) {
                trades.add(randomCompound(rng, 0));
            }
            root.put("trades", trades);
            try {
                TradeSavedData.load(root, null); // 单条损坏 -> 跳过并警告，其余保留
            } catch (Throwable t) {
                assertControlledFailure(t); // 只有超大集合/重复资产才整体安全失败
            }
        }
    }

    @Test
    void randomMalformedInboxAndReceiptsNeverCrashLoader() {
        Random rng = new Random(SEED);
        for (int i = 0; i < 500; i++) {
            CompoundTag root = new CompoundTag();
            root.putInt("schema_version", 1);
            root.put("inbox", randomList(rng, 0));
            root.put("receipts", randomList(rng, 0));
            root.put("operations", randomList(rng, 0));
            root.put("preferences", randomList(rng, 0));
            try {
                TradeSavedData.load(root, null);
            } catch (Throwable t) {
                assertControlledFailure(t);
            }
        }
    }

    // ------------------------------------------------------------------ 集合大小（不分配无界内存）

    @Test
    void oversizedCollectionsAreRejectedBeforeAllocation() {
        assertOversizedRejected("trades", TradeNbtCodec.MAX_TRADES + 1);
        assertOversizedRejected("inbox", TradeNbtCodec.MAX_INBOX_ENTRIES + 1);
        assertOversizedRejected("receipts", TradeNbtCodec.MAX_RECEIPTS + 1);
        assertOversizedRejected("operations", TradeNbtCodec.MAX_OPERATIONS + 1);
        assertOversizedRejected("preferences", TradeNbtCodec.MAX_PREFERENCES + 1);
    }

    private static void assertOversizedRejected(String section, int size) {
        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", 1);
        ListTag list = new ListTag();
        for (int i = 0; i < size; i++) {
            list.add(new CompoundTag());
        }
        root.put(section, list);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TradeSavedData.load(root, null));
        assertTrue(e.getMessage().contains(section + " too large"),
                "expected section guard for " + section + " but got: " + e.getMessage());
    }

    @Test
    void oversizedFeeQuoteIsSkippedAsCorruptEntry() {
        List<TradeFeeQuote.ItemFee> fees = new ArrayList<>();
        for (int i = 0; i < TradeNbtCodec.MAX_QUOTE_FEES + 1; i++) {
            fees.add(new TradeFeeQuote.ItemFee("minecraft:stick", i + 1, A));
        }
        TradeId id = TradeId.random();
        TradeFeeQuote quote = new TradeFeeQuote(UUID.randomUUID(), id.value(), 1, 2_000L, 0, 0,
                fees, "pkm_percentage", 1);
        PlayerTrade trade = PlayerTrade.builder(id, A, B)
                .status(TradeStatus.LOCKED)
                .feeQuote(quote)
                .build();

        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", 1);
        ListTag trades = new ListTag();
        trades.add(TradeNbtCodec.encodePlayerTrade(trade));
        root.put("trades", trades);

        // 单条损坏 -> decodeAll 跳过该交易并警告，其余保留
        TradeSavedData data = TradeSavedData.load(root, null);
        assertTrue(data.tradesView().isEmpty());
    }

    @Test
    void decodeFeeQuoteDirectlyRejectsOversizedFees() {
        CompoundTag tag = new CompoundTag();
        ListTag fees = new ListTag();
        for (int i = 0; i < TradeNbtCodec.MAX_QUOTE_FEES + 1; i++) {
            CompoundTag fee = new CompoundTag();
            fee.putString("itemId", "minecraft:stick");
            fee.putLong("count", i + 1);
            fee.putUUID("chargedTo", A);
            fees.add(fee);
        }
        tag.put("itemFees", fees);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> TradeNbtCodec.decodeFeeQuote(tag));
        assertTrue(e.getMessage().contains("feeQuote.itemFees too large"));
    }

    // ------------------------------------------------------------------ NBT 深度（不透传字段不递归）

    @Test
    void deeplyNestedOpaqueNbtDoesNotStackOverflow() {
        CompoundTag deep = deepChain(20_000);
        // ItemAsset.stack 是透传不透明数据：解码不得递归遍历
        ItemAsset item = new ItemAsset(UUID.randomUUID(), UUID.randomUUID(), deep);
        ItemAsset decoded = TradeNbtCodec.decodeItemAsset(TradeNbtCodec.encodeItemAsset(item));
        assertNotNull(decoded.stackNbt());

        // 经完整 load 路径验证：交易报价内嵌 20_000 层 stack 标签
        PlayerTrade base = PlayerTrade.builder(TradeId.random(), A, B)
                .status(TradeStatus.OPEN)
                .build();
        CompoundTag tradeTag = TradeNbtCodec.encodePlayerTrade(base);
        tradeTag.put("leftOffer", offerTag(deep));
        tradeTag.put("rightOffer", offerTag(deep));

        CompoundTag root = new CompoundTag();
        root.putInt("schema_version", 1);
        ListTag trades = new ListTag();
        trades.add(tradeTag);
        root.put("trades", trades);

        TradeSavedData data = TradeSavedData.load(root, null); // 不得抛 StackOverflowError
        assertEquals(1, data.tradesView().size());
    }

    // ------------------------------------------------------------------ 工具

    /** 迭代构造深度嵌套链（测试自身不递归，避免测试栈溢出）。 */
    private static CompoundTag deepChain(int depth) {
        CompoundTag head = new CompoundTag();
        CompoundTag cursor = head;
        for (int i = 0; i < depth; i++) {
            CompoundTag next = new CompoundTag();
            cursor.put("child", next);
            cursor = next;
        }
        return head;
    }

    /** 构造带指定 stack 的合法 item 报价标签（kind/assetId/owner 与 codec 一致）。 */
    private static CompoundTag offerTag(CompoundTag stack) {
        CompoundTag item = new CompoundTag();
        item.putString("kind", "ITEM");
        item.putUUID("assetId", UUID.randomUUID());
        item.putUUID("owner", UUID.randomUUID());
        item.put("stack", stack);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag offer = new CompoundTag();
        offer.put("items", items);
        offer.put("pkm", new ListTag());
        offer.put("pokemon", new ListTag());
        return offer;
    }

    private static CompoundTag randomCompound(Random rng, int depth) {
        CompoundTag tag = new CompoundTag();
        int count = depth > 6 ? 1 + rng.nextInt(2) : rng.nextInt(6);
        for (int i = 0; i < count; i++) {
            tag.put(SCHEMA_KEYS[rng.nextInt(SCHEMA_KEYS.length)], randomTag(rng, depth + 1));
        }
        return tag;
    }

    private static Tag randomTag(Random rng, int depth) {
        switch (rng.nextInt(8)) {
            case 0:
                return IntTag.valueOf(rng.nextInt());
            case 1:
                return LongTag.valueOf(rng.nextLong());
            case 2:
                return StringTag.valueOf(randString(rng));
            case 3: {
                byte[] arr = new byte[rng.nextInt(8)];
                rng.nextBytes(arr);
                return new ByteArrayTag(arr);
            }
            case 4: {
                int[] arr = new int[rng.nextInt(8)];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = rng.nextInt();
                }
                return new IntArrayTag(arr);
            }
            case 5: {
                long[] arr = new long[rng.nextInt(8)];
                for (int i = 0; i < arr.length; i++) {
                    arr[i] = rng.nextLong();
                }
                return new LongArrayTag(arr);
            }
            case 6:
                return randomList(rng, depth);
            default:
                return depth > 6 ? IntTag.valueOf(rng.nextInt()) : randomCompound(rng, depth);
        }
    }

    /** 生成元素类型一致的 ListTag（Minecraft ListTag 不允许混入异构元素）。 */
    private static ListTag randomList(Random rng, int depth) {
        int type = rng.nextInt(4); // 0:int 1:long 2:string 3:compound
        ListTag list = new ListTag();
        int n = rng.nextInt(4);
        for (int i = 0; i < n; i++) {
            switch (type) {
                case 0 -> list.add(IntTag.valueOf(rng.nextInt()));
                case 1 -> list.add(LongTag.valueOf(rng.nextLong()));
                case 2 -> list.add(StringTag.valueOf(randString(rng)));
                default -> list.add(randomCompound(rng, depth + 1));
            }
        }
        return list;
    }

    private static String randString(Random rng) {
        int len = rng.nextInt(24);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }

    /** 受控失败：允许文档化的安全失败异常，禁止致命 Error 与类型混淆 bug。 */
    private static void assertControlledFailure(Throwable t) {
        boolean fatal = t instanceof Error
                || t instanceof NullPointerException
                || t instanceof ClassCastException;
        assertFalse(fatal, "uncontrolled failure from loader: " + t);
    }
}
