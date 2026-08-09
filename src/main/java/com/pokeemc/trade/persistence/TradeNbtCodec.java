package com.pokeemc.trade.persistence;

import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.model.ItemAsset;
import com.pokeemc.trade.model.PkmAsset;
import com.pokeemc.trade.model.PlayerTrade;
import com.pokeemc.trade.model.PokemonAsset;
import com.pokeemc.trade.model.TradeAsset;
import com.pokeemc.trade.model.TradeError;
import com.pokeemc.trade.model.TradeFeeQuote;
import com.pokeemc.trade.model.TradeId;
import com.pokeemc.trade.model.TradeOffer;
import com.pokeemc.trade.model.TradeReceipt;
import com.pokeemc.trade.model.TradeStatus;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家交易数据 NBT 编解码（Task 2）。全部为纯静态方法，不依赖
 * {@code HolderLookup} 与 Minecraft 注册表，可直接在 JVM 单元测试中往返。
 *
 * <p>顶层 {@code schema_version = 1}；未知更高版本由 {@link TradeSavedData}
 * 拒绝加载并记录清晰错误，不得静默清空交易。</p>
 *
 * <p>物品/宝可梦的 NBT 快照原样保存；解码时不做任何注册表解析，
 * 完整性与语义校验由 gateway（Task 3-5）在交付/归还时执行。</p>
 */
public final class TradeNbtCodec {

    public static final int SCHEMA_VERSION = 1;

    /**
     * 解码防御性上限（Task 13 步骤 1）：损坏存档中的超大集合必须在分配
     * 模型对象之前被拒绝（安全失败），不得造成无界内存分配。
     * 正常存档远低于这些上限；回执在生产中由 addReceipt 修剪到 10,000 条，
     * 解码上限（12,000）必须不低于运行上限，否则合法存档会被拒绝加载。
     */
    public static final int MAX_TRADES = 10_000;
    public static final int MAX_INBOX_ENTRIES = 10_000;
    public static final int MAX_RECEIPTS = 12_000;
    public static final int MAX_OPERATIONS = 10_000;
    public static final int MAX_PREFERENCES = 10_000;
    public static final int MAX_QUOTE_FEES = 128;

    private static final String TAG_SCHEMA_VERSION = "schema_version";

    /** 集合大小守卫：超限抛出 IllegalStateException（安全失败，不分配元素）。 */
    private static void checkSize(String label, int size, int max) {
        if (size > max) {
            throw new IllegalStateException(
                    "trade data " + label + " too large: " + size + " > " + max);
        }
    }

    private TradeNbtCodec() {
    }

    // ---------------------------------------------------------------- 顶层

    /** 编码全部交易数据（活动交易 + 收件箱 + 回执 + operation ledger）。 */
    public static CompoundTag encodeAll(TradeSavedData data) {
        CompoundTag root = new CompoundTag();
        root.putInt(TAG_SCHEMA_VERSION, SCHEMA_VERSION);

        ListTag tradesTag = new ListTag();
        for (PlayerTrade trade : data.tradesView().values()) {
            tradesTag.add(encodePlayerTrade(trade));
        }
        root.put("trades", tradesTag);

        ListTag inboxTag = new ListTag();
        for (InboxEntry entry : data.inboxView()) {
            inboxTag.add(encodeInboxEntry(entry));
        }
        root.put("inbox", inboxTag);

        ListTag receiptsTag = new ListTag();
        for (TradeReceipt receipt : data.receiptsView().values()) {
            receiptsTag.add(encodeReceipt(receipt));
        }
        root.put("receipts", receiptsTag);

        ListTag operationsTag = new ListTag();
        for (OperationEntry op : data.operationsView().values()) {
            operationsTag.add(encodeOperation(op));
        }
        root.put("operations", operationsTag);

        ListTag preferencesTag = new ListTag();
        for (Map.Entry<UUID, DeliveryPreference> entry : data.preferencesView().entrySet()) {
            CompoundTag pref = encodePreference(entry.getValue());
            pref.putUUID("playerId", entry.getKey());
            preferencesTag.add(pref);
        }
        root.put("preferences", preferencesTag);
        return root;
    }

    /** 解码全部交易数据（容错：单条损坏跳过并警告，其余保留）。 */
    public static void decodeAll(CompoundTag root, TradeSavedData data) {
        if (root == null) {
            return;
        }
        int version = root.contains(TAG_SCHEMA_VERSION, Tag.TAG_INT)
                ? root.getInt(TAG_SCHEMA_VERSION) : 1;
        if (version < 1 || version > SCHEMA_VERSION) {
            throw new UnsupportedOperationException(
                    "trade data schema version " + version
                            + " unsupported (current " + SCHEMA_VERSION + "); refusing to load");
        }

        ListTag tradesTag = root.getList("trades", Tag.TAG_COMPOUND);
        checkSize("trades", tradesTag.size(), MAX_TRADES);
        for (int i = 0; i < tradesTag.size(); i++) {
            try {
                PlayerTrade trade = decodePlayerTrade(tradesTag.getCompound(i));
                data.restoreTrade(trade);
            } catch (Exception e) {
                TradeSavedData.LOGGER.warn("Skipping corrupt trade entry {}: {}", i, e.toString());
            }
        }

        ListTag inboxTag = root.getList("inbox", Tag.TAG_COMPOUND);
        checkSize("inbox", inboxTag.size(), MAX_INBOX_ENTRIES);
        for (int i = 0; i < inboxTag.size(); i++) {
            try {
                data.restoreInboxEntry(decodeInboxEntry(inboxTag.getCompound(i)));
            } catch (Exception e) {
                TradeSavedData.LOGGER.warn("Skipping corrupt inbox entry {}: {}", i, e.toString());
            }
        }

        ListTag receiptsTag = root.getList("receipts", Tag.TAG_COMPOUND);
        checkSize("receipts", receiptsTag.size(), MAX_RECEIPTS);
        for (int i = 0; i < receiptsTag.size(); i++) {
            try {
                data.restoreReceipt(decodeReceipt(receiptsTag.getCompound(i)));
            } catch (Exception e) {
                TradeSavedData.LOGGER.warn("Skipping corrupt receipt entry {}: {}", i, e.toString());
            }
        }

        ListTag operationsTag = root.getList("operations", Tag.TAG_COMPOUND);
        checkSize("operations", operationsTag.size(), MAX_OPERATIONS);
        for (int i = 0; i < operationsTag.size(); i++) {
            try {
                data.restoreOperation(decodeOperation(operationsTag.getCompound(i)));
            } catch (Exception e) {
                TradeSavedData.LOGGER.warn("Skipping corrupt operation entry {}: {}", i, e.toString());
            }
        }

        ListTag preferencesTag = root.getList("preferences", Tag.TAG_COMPOUND);
        checkSize("preferences", preferencesTag.size(), MAX_PREFERENCES);
        for (int i = 0; i < preferencesTag.size(); i++) {
            try {
                CompoundTag pref = preferencesTag.getCompound(i);
                data.restorePreference(pref.getUUID("playerId"), decodePreference(pref));
            } catch (Exception e) {
                TradeSavedData.LOGGER.warn("Skipping corrupt preference entry {}: {}", i, e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- PlayerTrade

    public static CompoundTag encodePlayerTrade(PlayerTrade trade) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("tradeId", trade.tradeId().value());
        tag.putUUID("left", trade.leftPlayerId());
        tag.putUUID("right", trade.rightPlayerId());
        tag.putString("status", trade.status().name());
        tag.putLong("revision", trade.revision());
        tag.put("leftOffer", encodeOffer(trade.leftOffer()));
        tag.put("rightOffer", encodeOffer(trade.rightOffer()));
        tag.putLong("leftConfirmedRevision", trade.leftConfirmedRevision());
        tag.putLong("rightConfirmedRevision", trade.rightConfirmedRevision());
        tag.put("leftPreference", encodePreference(trade.leftPreference()));
        tag.put("rightPreference", encodePreference(trade.rightPreference()));
        if (trade.feeQuote() != null) {
            tag.put("feeQuote", encodeFeeQuote(trade.feeQuote()));
        }
        tag.putLong("createdAt", trade.createdAtEpochMillis());
        tag.putLong("updatedAt", trade.updatedAtEpochMillis());
        tag.putLong("expiresAt", trade.expiresAtEpochMillis());
        tag.putLong("lockDeadline", trade.lockDeadlineEpochMillis());
        tag.putString("failureError", trade.failureError().name());
        tag.putString("failureDetail", trade.failureDetail());
        return tag;
    }

    public static PlayerTrade decodePlayerTrade(CompoundTag tag) {
        UUID tradeId = tag.getUUID("tradeId");
        UUID left = tag.getUUID("left");
        UUID right = tag.getUUID("right");
        TradeStatus status = TradeStatus.valueOf(tag.getString("status"));
        long revision = tag.getLong("revision");
        TradeOffer leftOffer = decodeOffer(tag.getCompound("leftOffer"));
        TradeOffer rightOffer = decodeOffer(tag.getCompound("rightOffer"));
        long leftConfirmed = tag.getLong("leftConfirmedRevision");
        long rightConfirmed = tag.getLong("rightConfirmedRevision");
        DeliveryPreference leftPref = decodePreference(tag.getCompound("leftPreference"));
        DeliveryPreference rightPref = decodePreference(tag.getCompound("rightPreference"));
        TradeFeeQuote quote = tag.contains("feeQuote", Tag.TAG_COMPOUND)
                ? decodeFeeQuote(tag.getCompound("feeQuote")) : null;
        long createdAt = tag.getLong("createdAt");
        long updatedAt = tag.getLong("updatedAt");
        long expiresAt = tag.getLong("expiresAt");
        long lockDeadline = tag.contains("lockDeadline", Tag.TAG_LONG)
                ? tag.getLong("lockDeadline") : -1;
        TradeError failureError = tag.contains("failureError", Tag.TAG_STRING)
                ? TradeError.valueOf(tag.getString("failureError")) : TradeError.NONE;
        String failureDetail = tag.getString("failureDetail");

        return PlayerTrade.builder(new TradeId(tradeId), left, right)
                .status(status)
                .revision(revision)
                .leftOffer(leftOffer)
                .rightOffer(rightOffer)
                .leftConfirmedRevision(leftConfirmed)
                .rightConfirmedRevision(rightConfirmed)
                .leftPreference(leftPref)
                .rightPreference(rightPref)
                .feeQuote(quote)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .expiresAt(expiresAt)
                .lockDeadline(lockDeadline)
                .failureError(failureError)
                .failureDetail(failureDetail)
                .build();
    }

    // ---------------------------------------------------------------- 报价 / 资产

    public static CompoundTag encodeOffer(TradeOffer offer) {
        CompoundTag tag = new CompoundTag();
        ListTag items = new ListTag();
        for (ItemAsset a : offer.items()) {
            items.add(encodeItemAsset(a));
        }
        tag.put("items", items);
        ListTag pkm = new ListTag();
        for (PkmAsset a : offer.pkm()) {
            pkm.add(encodePkmAsset(a));
        }
        tag.put("pkm", pkm);
        ListTag pokemon = new ListTag();
        for (PokemonAsset a : offer.pokemon()) {
            pokemon.add(encodePokemonAsset(a));
        }
        tag.put("pokemon", pokemon);
        return tag;
    }

    public static TradeOffer decodeOffer(CompoundTag tag) {
        TradeOffer offer = TradeOffer.empty();
        ListTag items = tag.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            offer = offer.withAdded(decodeItemAsset(items.getCompound(i)));
        }
        ListTag pkm = tag.getList("pkm", Tag.TAG_COMPOUND);
        for (int i = 0; i < pkm.size(); i++) {
            offer = offer.withAdded(decodePkmAsset(pkm.getCompound(i)));
        }
        ListTag pokemon = tag.getList("pokemon", Tag.TAG_COMPOUND);
        for (int i = 0; i < pokemon.size(); i++) {
            offer = offer.withAdded(decodePokemonAsset(pokemon.getCompound(i)));
        }
        return offer;
    }

    public static CompoundTag encodeItemAsset(ItemAsset asset) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "ITEM");
        tag.putUUID("assetId", asset.assetId());
        tag.putUUID("owner", asset.originalOwner());
        tag.put("stack", asset.stackNbt());
        return tag;
    }

    public static ItemAsset decodeItemAsset(CompoundTag tag) {
        return new ItemAsset(
                tag.getUUID("assetId"),
                tag.getUUID("owner"),
                tag.getCompound("stack"));
    }

    public static CompoundTag encodePkmAsset(PkmAsset asset) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "PKM");
        tag.putUUID("assetId", asset.assetId());
        tag.putUUID("owner", asset.originalOwner());
        tag.putLong("amount", asset.amount());
        tag.putString("debitOperationId", asset.debitOperationId());
        tag.putBoolean("debited", asset.debited());
        return tag;
    }

    public static PkmAsset decodePkmAsset(CompoundTag tag) {
        return new PkmAsset(
                tag.getUUID("assetId"),
                tag.getUUID("owner"),
                tag.getLong("amount"),
                tag.getString("debitOperationId"),
                tag.getBoolean("debited"));
    }

    public static CompoundTag encodePokemonAsset(PokemonAsset asset) {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "POKEMON");
        tag.putUUID("assetId", asset.assetId());
        tag.putUUID("owner", asset.originalOwner());
        tag.putUUID("pokemonId", asset.pokemonId());
        tag.put("pokemonNbt", asset.pokemonNbt());
        tag.putString("sourceStorage", asset.sourceStorage());
        tag.putInt("sourceBox", asset.sourceBox());
        tag.putInt("sourceSlot", asset.sourceSlot());
        return tag;
    }

    public static PokemonAsset decodePokemonAsset(CompoundTag tag) {
        return new PokemonAsset(
                tag.getUUID("assetId"),
                tag.getUUID("owner"),
                tag.getUUID("pokemonId"),
                tag.getCompound("pokemonNbt"),
                tag.getString("sourceStorage"),
                tag.getInt("sourceBox"),
                tag.getInt("sourceSlot"));
    }

    /** 按 kind 分派解码资产（收件箱与回执通用）。 */
    public static TradeAsset decodeAsset(CompoundTag tag) {
        return switch (tag.getString("kind")) {
            case "ITEM" -> decodeItemAsset(tag);
            case "PKM" -> decodePkmAsset(tag);
            case "POKEMON" -> decodePokemonAsset(tag);
            default -> throw new IllegalArgumentException(
                    "unknown asset kind: " + tag.getString("kind"));
        };
    }

    // ---------------------------------------------------------------- 偏好 / 手续费 / 回执

    public static CompoundTag encodePreference(DeliveryPreference preference) {
        CompoundTag tag = new CompoundTag();
        tag.putString("item", preference.itemDestination().name());
        tag.putString("pokemon", preference.pokemonDestination().name());
        return tag;
    }

    public static DeliveryPreference decodePreference(CompoundTag tag) {
        return new DeliveryPreference(
                DeliveryPreference.ItemDestination.valueOf(tag.getString("item")),
                DeliveryPreference.PokemonDestination.valueOf(tag.getString("pokemon")));
    }

    public static CompoundTag encodeFeeQuote(TradeFeeQuote quote) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("quoteId", quote.quoteId());
        tag.putUUID("tradeId", quote.tradeId());
        tag.putLong("revision", quote.quotedRevision());
        tag.putLong("expiresAt", quote.expiresAtEpochMillis());
        tag.putLong("leftFee", quote.leftPkmFee());
        tag.putLong("rightFee", quote.rightPkmFee());
        ListTag fees = new ListTag();
        for (TradeFeeQuote.ItemFee fee : quote.itemFees()) {
            fees.add(encodeItemFee(fee));
        }
        tag.put("itemFees", fees);
        tag.putString("policyId", quote.policyId());
        tag.putInt("policyVersion", quote.policyVersion());
        return tag;
    }

    public static TradeFeeQuote decodeFeeQuote(CompoundTag tag) {
        List<TradeFeeQuote.ItemFee> fees = new ArrayList<>();
        ListTag feesTag = tag.getList("itemFees", Tag.TAG_COMPOUND);
        checkSize("feeQuote.itemFees", feesTag.size(), MAX_QUOTE_FEES);
        for (int i = 0; i < feesTag.size(); i++) {
            fees.add(decodeItemFee(feesTag.getCompound(i)));
        }
        return new TradeFeeQuote(
                tag.getUUID("quoteId"),
                tag.getUUID("tradeId"),
                tag.getLong("revision"),
                tag.getLong("expiresAt"),
                tag.getLong("leftFee"),
                tag.getLong("rightFee"),
                fees,
                tag.getString("policyId"),
                tag.getInt("policyVersion"));
    }

    private static CompoundTag encodeItemFee(TradeFeeQuote.ItemFee fee) {
        CompoundTag tag = new CompoundTag();
        tag.putString("itemId", fee.itemId());
        tag.putLong("count", fee.count());
        tag.putUUID("chargedTo", fee.chargedToPlayerId());
        return tag;
    }

    private static TradeFeeQuote.ItemFee decodeItemFee(CompoundTag tag) {
        return new TradeFeeQuote.ItemFee(
                tag.getString("itemId"),
                tag.getLong("count"),
                tag.getUUID("chargedTo"));
    }

    public static CompoundTag encodeReceipt(TradeReceipt receipt) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("tradeId", receipt.tradeId());
        tag.putLong("revision", receipt.revision());
        tag.putLong("committedAt", receipt.committedAt().toEpochMilli());
        tag.put("feeQuote", encodeFeeQuote(receipt.feeQuote()));
        tag.putLong("leftFee", receipt.leftPkmFee());
        tag.putLong("rightFee", receipt.rightPkmFee());
        ListTag applied = new ListTag();
        for (TradeReceipt.ItemFeeApplied fee : receipt.itemFeesApplied()) {
            applied.add(encodeItemFeeApplied(fee));
        }
        tag.put("itemFeesApplied", applied);
        return tag;
    }

    public static TradeReceipt decodeReceipt(CompoundTag tag) {
        List<TradeReceipt.ItemFeeApplied> applied = new ArrayList<>();
        ListTag appliedTag = tag.getList("itemFeesApplied", Tag.TAG_COMPOUND);
        checkSize("receipt.itemFeesApplied", appliedTag.size(), MAX_QUOTE_FEES);
        for (int i = 0; i < appliedTag.size(); i++) {
            applied.add(decodeItemFeeApplied(appliedTag.getCompound(i)));
        }
        return new TradeReceipt(
                tag.getUUID("tradeId"),
                tag.getLong("revision"),
                java.time.Instant.ofEpochMilli(tag.getLong("committedAt")),
                decodeFeeQuote(tag.getCompound("feeQuote")),
                tag.getLong("leftFee"),
                tag.getLong("rightFee"),
                applied);
    }

    private static CompoundTag encodeItemFeeApplied(TradeReceipt.ItemFeeApplied fee) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("feeAssetId", fee.feeAssetId());
        tag.putString("itemId", fee.itemId());
        tag.putLong("count", fee.count());
        tag.putUUID("chargedTo", fee.chargedToPlayerId());
        tag.putUUID("sink", fee.feeSinkPlayerId());
        return tag;
    }

    private static TradeReceipt.ItemFeeApplied decodeItemFeeApplied(CompoundTag tag) {
        return new TradeReceipt.ItemFeeApplied(
                tag.getUUID("feeAssetId"),
                tag.getString("itemId"),
                tag.getLong("count"),
                tag.getUUID("chargedTo"),
                tag.getUUID("sink"));
    }

    // ---------------------------------------------------------------- 收件箱 / 操作

    public static CompoundTag encodeInboxEntry(InboxEntry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("entryId", entry.entryId());
        tag.putUUID("tradeId", entry.tradeId());
        tag.putUUID("recipient", entry.recipientId());
        tag.put("asset", encodeAsset(entry.asset()));
        tag.put("preference", encodePreference(entry.preference()));
        tag.putLong("revision", entry.revision());
        tag.putLong("createdAt", entry.createdAtEpochMillis());
        tag.putString("state", entry.state().name());
        return tag;
    }

    public static InboxEntry decodeInboxEntry(CompoundTag tag) {
        return new InboxEntry(
                tag.getUUID("entryId"),
                tag.getUUID("tradeId"),
                tag.getUUID("recipient"),
                decodeAsset(tag.getCompound("asset")),
                decodePreference(tag.getCompound("preference")),
                tag.getLong("revision"),
                tag.getLong("createdAt"),
                InboxEntry.InboxState.valueOf(tag.getString("state")));
    }

    public static CompoundTag encodeOperation(OperationEntry op) {
        CompoundTag tag = new CompoundTag();
        tag.putString("operationId", op.operationId());
        tag.putString("kind", op.kind());
        tag.putString("state", op.state().name());
        tag.putUUID("tradeId", op.tradeId());
        if (op.assetId() != null) {
            tag.putUUID("assetId", op.assetId());
        }
        if (op.playerId() != null) {
            tag.putUUID("playerId", op.playerId());
        }
        tag.putLong("amount", op.amount());
        tag.putString("detail", op.detail());
        tag.putLong("createdAt", op.createdAtEpochMillis());
        return tag;
    }

    public static OperationEntry decodeOperation(CompoundTag tag) {
        UUID assetId = tag.contains("assetId", Tag.TAG_INT_ARRAY) ? tag.getUUID("assetId") : null;
        UUID playerId = tag.contains("playerId", Tag.TAG_INT_ARRAY) ? tag.getUUID("playerId") : null;
        return new OperationEntry(
                tag.getString("operationId"),
                tag.getString("kind"),
                OperationEntry.OperationState.valueOf(tag.getString("state")),
                tag.getUUID("tradeId"),
                assetId,
                playerId,
                tag.getLong("amount"),
                tag.getString("detail"),
                tag.getLong("createdAt"));
    }

    // ---------------------------------------------------------------- 内部

    private static CompoundTag encodeAsset(TradeAsset asset) {
        return switch (asset) {
            case ItemAsset a -> encodeItemAsset(a);
            case PkmAsset a -> encodePkmAsset(a);
            case PokemonAsset a -> encodePokemonAsset(a);
        };
    }
}
