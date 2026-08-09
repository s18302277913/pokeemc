package com.pokeemc.trade.client;

import com.pokeemc.trade.menu.PlayerTradeMenu;
import com.pokeemc.trade.model.AssetPageKind;
import com.pokeemc.trade.model.DeliveryPreference;
import com.pokeemc.trade.network.AcceptTradePacket;
import com.pokeemc.trade.network.CancelTradePacket;
import com.pokeemc.trade.network.ConfirmTradePacket;
import com.pokeemc.trade.network.CreateTradePacket;
import com.pokeemc.trade.network.OfferItemPacket;
import com.pokeemc.trade.network.OfferPkmPacket;
import com.pokeemc.trade.network.OfferPokemonPacket;
import com.pokeemc.trade.network.RemoveOfferAssetPacket;
import com.pokeemc.trade.network.RequestTradeAssetPagePacket;
import com.pokeemc.trade.network.RequestTradeDirectoryPacket;
import com.pokeemc.trade.network.SetDeliveryPreferencePacket;
import com.pokeemc.trade.network.TradeAssetPagePacket;
import com.pokeemc.trade.network.TradeDirectoryPacket;
import com.pokeemc.trade.network.TradePacketLimits;
import com.pokeemc.trade.network.TradeSnapshotPacket;
import com.pokeemc.trade.service.TradeAssetPage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家交易界面（计划 5.4，Task 9）。
 * <p>
 * 三态视图显式建模：{@code DIRECTORY}（在线玩家目录）→ {@code WORKSPACE}（交易工作台）
 * → {@code OWN_ASSETS}（本人资产页）。所有交互只发 C2S packet，状态来自
 * {@link TradeClientState#INSTANCE}（S2C handler 写入），界面本身零业务逻辑。
 * <p>
 * 状态文本：<b>INVITED/OPEN</b> 编辑报价；<b>LOCKED</b> 3 秒锁定提交；<b>COMMITTING..COMPLETED</b>
 * 只读；<b>CANCELLED</b> 关闭。对手报价只含展示摘要（不含 NBT）。
 */
public class PlayerTradeScreen extends AbstractContainerScreen<PlayerTradeMenu> {

    public enum View { DIRECTORY, WORKSPACE, OWN_ASSETS }

    private final TradeClientState state = TradeClientState.INSTANCE;

    private View view = View.DIRECTORY;
    private String query = "";
    private int directoryPage = 0;
    private AssetPageKind assetKind = AssetPageKind.ITEMS;
    private int assetPage = 0;

    private EditBox searchBox;
    private Button assetsButton;
    private Button confirmButton;
    private Button cancelButton;
    private Button backButton;
    private Button directoryPrevButton;
    private Button directoryNextButton;
    private Button assetsPrevButton;
    private Button assetsNextButton;
    private Button itemsTab;
    private Button pkmTab;
    private Button partyTab;
    private Button pcTab;
    private Button prefToggleButton;
    private Button pkmAdd10kButton;
    private Button pkmAdd100kButton;

    private TradeDirectoryPacket lastDirectory;
    private TradeSnapshotPacket lastSnapshot;
    private TradeAssetPagePacket lastAssetPage;
    private final List<Button> rowButtons = new ArrayList<>();

    public PlayerTradeScreen(PlayerTradeMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 320;
        imageHeight = 220;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos;
        int y = topPos;

        searchBox = new EditBox(font, x + 16, y + 26, 150, 14, Component.literal("搜索玩家"));
        searchBox.setMaxLength(TradePacketLimits.MAX_SEARCH_LENGTH);
        searchBox.setResponder(s -> {
            query = s == null ? "" : s.trim();
            directoryPage = 0;
            requestDirectory();
        });
        addRenderableWidget(searchBox);

        assetsButton = Button.builder(Component.literal("我的资产"),
                        b -> openView(View.OWN_ASSETS, AssetPageKind.ITEMS, 0))
                .bounds(x + imageWidth - 96, y + 6, 80, 16).build();
        confirmButton = Button.builder(Component.literal("确认交易"), b -> sendConfirm())
                .bounds(x + imageWidth - 96, y + imageHeight - 40, 80, 18).build();
        cancelButton = Button.builder(Component.literal("取消交易"), b -> sendCancel())
                .bounds(x + 16, y + imageHeight - 40, 72, 18).build();
        backButton = Button.builder(Component.literal("← 返回"), b -> goBack())
                .bounds(x + 8, y + imageHeight - 22, 60, 14).build();
        prefToggleButton = Button.builder(Component.literal("交付方式"), b -> togglePreference())
                .bounds(x + 96, y + imageHeight - 40, 80, 18).build();

        directoryPrevButton = Button.builder(Component.literal("◀"), b -> {
            if (directoryPage > 0) { directoryPage--; requestDirectory(); }
        }).bounds(x + 8, y + imageHeight - 42, 36, 16).build();
        directoryNextButton = Button.builder(Component.literal("▶"), b -> {
            directoryPage++; requestDirectory();
        }).bounds(x + imageWidth - 44, y + imageHeight - 42, 36, 16).build();

        assetsPrevButton = Button.builder(Component.literal("◀"), b -> {
            if (assetPage > 0) { assetPage--; requestAssetPage(); }
        }).bounds(x + 8, y + imageHeight - 42, 36, 16).build();
        assetsNextButton = Button.builder(Component.literal("▶"), b -> {
            assetPage++; requestAssetPage();
        }).bounds(x + imageWidth - 44, y + imageHeight - 42, 36, 16).build();

        itemsTab = Button.builder(Component.literal("物品"), b -> openAssets(AssetPageKind.ITEMS))
                .bounds(x + 16, y + 26, 52, 14).build();
        pkmTab = Button.builder(Component.literal("PKM"), b -> openAssets(AssetPageKind.PKM))
                .bounds(x + 72, y + 26, 52, 14).build();
        partyTab = Button.builder(Component.literal("队伍"), b -> openAssets(AssetPageKind.PARTY))
                .bounds(x + 128, y + 26, 52, 14).build();
        pcTab = Button.builder(Component.literal("电脑"), b -> openAssets(AssetPageKind.PC))
                .bounds(x + 184, y + 26, 52, 14).build();
        pkmAdd10kButton = Button.builder(Component.literal("+10000"), b -> addPkm(10_000L))
                .bounds(x + imageWidth - 120, y + imageHeight - 42, 54, 16).build();
        pkmAdd100kButton = Button.builder(Component.literal("+100000"), b -> addPkm(100_000L))
                .bounds(x + imageWidth - 62, y + imageHeight - 42, 54, 16).build();

        for (Button b : List.of(assetsButton, confirmButton, cancelButton, backButton, prefToggleButton,
                directoryPrevButton, directoryNextButton, assetsPrevButton, assetsNextButton,
                itemsTab, pkmTab, partyTab, pcTab, pkmAdd10kButton, pkmAdd100kButton)) {
            addRenderableWidget(b);
        }

        // 进入界面：有活动交易直接进工作台，否则目录
        if (state.hasActiveTrade()) {
            openView(View.WORKSPACE, AssetPageKind.ITEMS, 0);
        } else {
            openView(View.DIRECTORY, AssetPageKind.ITEMS, 0);
            requestDirectory();
        }
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        // 数据变化时重建动态行按钮（目录条目 / 资产条目）
        reconcileDynamicRows();
        renderBackground(g, mx, my, partialTick);
        renderPanel(g);
        super.render(g, mx, my, partialTick);
        switch (view) {
            case DIRECTORY -> renderDirectory(g, mx, my);
            case WORKSPACE -> renderWorkspace(g, mx, my);
            case OWN_ASSETS -> renderOwnAssets(g, mx, my);
        }
        renderTooltip(g, mx, my);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mx, int my) {
        // 自定义面板渲染在 renderPanel()
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ 渲染

    private void renderPanel(GuiGraphics g) {
        int x = leftPos;
        int y = topPos;
        g.fill(x, y, x + imageWidth, y + imageHeight, 0xC0101010);
        g.fill(x, y, x + imageWidth, y + 1, 0xFF3F3F3F);
        g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, 0xFF3F3F3F);
        g.fill(x, y, x + 1, y + imageHeight, 0xFF3F3F3F);
        g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, 0xFF3F3F3F);
    }

    private void renderDirectory(GuiGraphics g, int mx, int my) {
        g.drawString(font, "玩家交易 · 在线玩家", leftPos + 16, topPos + 8, 0xFFFFFF);
        g.drawString(font, "搜索:", leftPos + 16, topPos + 30, 0xAAAAAA);
        var dir = state.directory();
        if (dir.isEmpty()) {
            g.drawString(font, "（加载中…）", leftPos + 20, topPos + 52, 0x808080);
            return;
        }
        TradeDirectoryPacket d = dir.get();
        int y = topPos + 52;
        for (TradeDirectoryPacket.PlayerDirectoryEntry e : d.entries()) {
            String cap = switch (e.capability()) {
                case AVAILABLE -> "可交易";
                case SELF -> "自己";
                case BUSY -> "交易中";
                case INVITE_PENDING -> "已邀请";
                case OFFLINE -> "离线";
                case DISABLED_BY_PLAYER -> "已关闭";
                case DISABLED_BY_SERVER -> "已禁用";
                case PKM_UNSUPPORTED -> "PKM 不可用";
                case RECOVERY_REQUIRED -> "待恢复";
                case RATE_LIMITED -> "受限";
            };
            g.drawString(font, e.displayName(), leftPos + 20, y, 0xFFFFFF);
            g.drawString(font, cap, leftPos + 140, y, capColor(e.capability()));
            y += 16;
            if (y > topPos + imageHeight - 46) {
                break;
            }
        }
        g.drawString(font, "第 " + (d.page() + 1) + " 页 / 共 " + d.total() + " 人",
                leftPos + 16, topPos + imageHeight - 22, 0x808080);
    }

    private void renderWorkspace(GuiGraphics g, int mx, int my) {
        var snapOpt = state.snapshot();
        if (snapOpt.isEmpty()) {
            g.drawString(font, "（交易已结束）", leftPos + 16, topPos + 52, 0x808080);
            return;
        }
        TradeSnapshotPacket s = snapOpt.get();
        g.drawString(font, "玩家交易 · " + statusText(s.status()), leftPos + 16, topPos + 8, 0xFFFFFF);
        g.drawString(font, "对方: " + s.otherPlayer().displayName(), leftPos + 16, topPos + 26, 0xFFFFFF);

        // 左侧：我方报价
        int lx = leftPos + 16;
        int ly = topPos + 48;
        g.drawString(font, "我方报价" + (s.selfConfirmed() ? " ✓" : ""), lx, ly, 0x7CFC7C);
        ly += 12;
        for (TradeSnapshotPacket.ItemWire i : s.selfOffer().items()) {
            g.drawString(font, i.itemId() + " x" + i.count(), lx, ly, 0xE0E0E0);
            ly += 11;
        }
        if (s.selfOffer().pkmTotal() > 0) {
            g.drawString(font, "PKM " + s.selfOffer().pkmTotal(), lx, ly, 0xE0E0E0);
            ly += 11;
        }
        for (TradeSnapshotPacket.PokemonWire p : s.selfOffer().pokemon()) {
            String label = (p.shiny() ? "★" : "") + (p.nickname().isBlank() ? p.species() : p.nickname())
                    + " Lv" + p.level();
            g.drawString(font, label, lx, ly, 0xE0E0E0);
            ly += 11;
        }
        if (ly <= topPos + 52) {
            g.drawString(font, "（空）", lx, ly, 0x808080);
        }

        // 右侧：对方报价
        int rx = leftPos + 176;
        int ry = topPos + 48;
        g.drawString(font, "对方报价" + (s.otherConfirmed() ? " ✓" : ""), rx, ry, 0xFFCC66);
        ry += 12;
        for (TradeSnapshotPacket.ItemWire i : s.otherOffer().items()) {
            g.drawString(font, i.itemId() + " x" + i.count(), rx, ry, 0xE0E0E0);
            ry += 11;
        }
        if (s.otherOffer().pkmTotal() > 0) {
            g.drawString(font, "PKM " + s.otherOffer().pkmTotal(), rx, ry, 0xE0E0E0);
            ry += 11;
        }
        for (TradeSnapshotPacket.PokemonWire p : s.otherOffer().pokemon()) {
            String label = (p.shiny() ? "★" : "") + (p.nickname().isBlank() ? p.species() : p.nickname())
                    + " Lv" + p.level();
            g.drawString(font, label, rx, ry, 0xE0E0E0);
            ry += 11;
        }
        if (ry <= topPos + 52) {
            g.drawString(font, "（空）", rx, ry, 0x808080);
        }

        // 交付偏好（本地化短名）
        String pref = destLabel(s.selfDeliveryPreference().itemDestination()) + " / "
                + pokemonDestLabel(s.selfDeliveryPreference().pokemonDestination());
        g.drawString(font, "交付: " + pref, leftPos + 16, topPos + imageHeight - 56, 0xAAAAAA);
        if (s.lockDeadlineEpochMillis() > 0) {
            g.drawString(font, "锁定提交中…", leftPos + 16, topPos + imageHeight - 42, 0xFFCC66);
        }
    }

    private void renderOwnAssets(GuiGraphics g, int mx, int my) {
        g.drawString(font, "我的资产 · " + assetKind.name(), leftPos + 16, topPos + 8, 0xFFFFFF);
        TradeAssetPagePacket page = state.assetPage(assetKind, assetPage);
        if (page == null) {
            g.drawString(font, "（加载中…）", leftPos + 20, topPos + 48, 0x808080);
            return;
        }
        int y = topPos + 48;
        for (TradeAssetPage.TradeAssetEntry e : page.entries()) {
            String label;
            if (e instanceof TradeAssetPage.ItemEntry it) {
                label = it.itemId() + " x" + it.count();
            } else if (e instanceof TradeAssetPage.PkmEntry pk) {
                label = "PKM " + pk.amount();
            } else if (e instanceof TradeAssetPage.PokemonEntry mon) {
                label = (mon.shiny() ? "★" : "") + (mon.nickname().isBlank() ? mon.species() : mon.nickname())
                        + " Lv" + mon.level();
            } else {
                label = "";
            }
            boolean inOffer = inSelfOffer(e);
            g.drawString(font, label, leftPos + 20, y, inOffer ? 0x7CFC7C : 0xE0E0E0);
            y += 15;
            if (y > topPos + imageHeight - 48) {
                break;
            }
        }
        g.drawString(font, "第 " + (page.page() + 1) + " 页 / 共 " + page.total() + " 项",
                leftPos + 16, topPos + imageHeight - 22, 0x808080);
    }

    // ------------------------------------------------------------------ 动态行按钮

    private void reconcileDynamicRows() {
        boolean changed = false;
        switch (view) {
            case DIRECTORY -> {
                TradeDirectoryPacket d = state.directory().orElse(null);
                if (d != lastDirectory) {
                    lastDirectory = d;
                    rebuildDirectoryRows();
                    changed = true;
                }
            }
            case WORKSPACE -> {
                TradeSnapshotPacket s = state.snapshot().orElse(null);
                if (s != lastSnapshot) {
                    lastSnapshot = s;
                    rebuildWorkspaceRows();
                    changed = true;
                }
            }
            case OWN_ASSETS -> {
                TradeAssetPagePacket p = state.assetPage(assetKind, assetPage);
                if (p != lastAssetPage) {
                    lastAssetPage = p;
                    rebuildAssetRows();
                    changed = true;
                }
            }
        }
        if (changed) {
            syncButtonVisibility();
        }
    }

    private void rebuildDirectoryRows() {
        clearRows();
        var dir = state.directory();
        if (dir.isEmpty()) {
            return;
        }
        int y = topPos + 52;
        for (TradeDirectoryPacket.PlayerDirectoryEntry e : dir.get().entries()) {
            if (y > topPos + imageHeight - 50) {
                break;
            }
            final UUID target = e.playerId();
            Button invite = Button.builder(Component.literal("邀请交易"),
                            b -> sendCreate(target))
                    .bounds(leftPos + 168, y - 1, 64, 12).build();
            invite.active = e.capability() == com.pokeemc.trade.model.TradeCapability.AVAILABLE
                    || e.capability() == com.pokeemc.trade.model.TradeCapability.INVITE_PENDING;
            addRenderableWidget(invite);
            rowButtons.add(invite);
            y += 16;
        }
    }

    private void rebuildWorkspaceRows() {
        clearRows();
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        TradeSnapshotPacket s = snap.get();
        // 受邀方接受邀请按钮（INVITED 且对方是自己）
        if (s.status() == com.pokeemc.trade.model.TradeStatus.INVITED
                && state.selfPlayerId() != null
                && state.selfPlayerId().equals(s.otherPlayer().playerId())) {
            Button accept = Button.builder(Component.literal("接受邀请"),
                            b -> sendAccept(s.tradeId()))
                    .bounds(leftPos + 96, topPos + 6, 88, 16).build();
            addRenderableWidget(accept);
            rowButtons.add(accept);
        }
    }

    private void rebuildAssetRows() {
        clearRows();
        TradeAssetPagePacket page = state.assetPage(assetKind, assetPage);
        if (page == null) {
            return;
        }
        int y = topPos + 48;
        for (TradeAssetPage.TradeAssetEntry e : page.entries()) {
            if (y > topPos + imageHeight - 52) {
                break;
            }
            boolean inOffer = inSelfOffer(e);
            String actionText = inOffer ? "移除" : "加入";
            Button b = Button.builder(Component.literal(actionText),
                            btn -> toggleOffer(e))
                    .bounds(leftPos + 240, y - 1, 48, 12).build();
            addRenderableWidget(b);
            rowButtons.add(b);
            y += 15;
        }
    }

    private void clearRows() {
        for (Button b : rowButtons) {
            removeWidget(b);
        }
        rowButtons.clear();
    }

    // ------------------------------------------------------------------ 请求发送

    private void requestDirectory() {
        PacketDistributor.sendToServer(new RequestTradeDirectoryPacket(
                UUID.randomUUID(), query, directoryPage, 20));
    }

    private void requestAssetPage() {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new RequestTradeAssetPagePacket(
                UUID.randomUUID(), snap.get().tradeId(), snap.get().revision(), assetKind, assetPage, 20));
    }

    private void sendCreate(UUID target) {
        PacketDistributor.sendToServer(new CreateTradePacket(UUID.randomUUID(), target));
    }

    private void sendAccept(UUID tradeId) {
        PacketDistributor.sendToServer(new AcceptTradePacket(
                UUID.randomUUID(), tradeId, currentRevision()));
    }

    private void sendConfirm() {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new ConfirmTradePacket(
                UUID.randomUUID(), snap.get().tradeId(), snap.get().revision()));
    }

    private void sendCancel() {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new CancelTradePacket(
                UUID.randomUUID(), snap.get().tradeId(), snap.get().revision()));
    }

    private void togglePreference() {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        DeliveryPreference cur = snap.get().selfDeliveryPreference();
        DeliveryPreference.ItemDestination nextItem = switch (cur.itemDestination()) {
            case INVENTORY -> DeliveryPreference.ItemDestination.ENDER_CHEST;
            case ENDER_CHEST -> DeliveryPreference.ItemDestination.INBOX;
            default -> DeliveryPreference.ItemDestination.INVENTORY;
        };
        DeliveryPreference next = new DeliveryPreference(
                nextItem,
                cur.pokemonDestination() == DeliveryPreference.PokemonDestination.PC
                        ? DeliveryPreference.PokemonDestination.INBOX
                        : DeliveryPreference.PokemonDestination.PC);
        PacketDistributor.sendToServer(new SetDeliveryPreferencePacket(
                UUID.randomUUID(), snap.get().tradeId(), snap.get().revision(), next));
    }

    private static String destLabel(DeliveryPreference.ItemDestination d) {
        return switch (d) {
            case AUTO -> Component.translatable("poketrade.trade.dest.auto").getString();
            case INVENTORY -> Component.translatable("poketrade.trade.dest.inventory").getString();
            case ENDER_CHEST -> Component.translatable("poketrade.trade.dest.ender_chest").getString();
            case INBOX -> Component.translatable("poketrade.trade.dest.inbox").getString();
        };
    }

    private static String pokemonDestLabel(DeliveryPreference.PokemonDestination d) {
        return switch (d) {
            case AUTO -> Component.translatable("poketrade.trade.dest.auto").getString();
            case PARTY -> Component.translatable("poketrade.trade.dest.party").getString();
            case PC -> "PC";
            case INBOX -> Component.translatable("poketrade.trade.dest.inbox").getString();
        };
    }

    private void toggleOffer(TradeAssetPage.TradeAssetEntry e) {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        UUID tradeId = snap.get().tradeId();
        long revision = snap.get().revision();
        if (e instanceof TradeAssetPage.ItemEntry it) {
            if (inSelfOffer(e)) {
                PacketDistributor.sendToServer(new RemoveOfferAssetPacket(
                        UUID.randomUUID(), tradeId, revision, it.assetId()));
            } else {
                PacketDistributor.sendToServer(new OfferItemPacket(
                        UUID.randomUUID(), tradeId, revision, it.inventorySlot(), it.count()));
            }
        } else if (e instanceof TradeAssetPage.PokemonEntry mon) {
            if (inSelfOffer(e)) {
                PacketDistributor.sendToServer(new RemoveOfferAssetPacket(
                        UUID.randomUUID(), tradeId, revision, mon.assetId()));
            } else {
                PacketDistributor.sendToServer(new OfferPokemonPacket(
                        UUID.randomUUID(), tradeId, revision, mon.sourceStorage(),
                        mon.sourceBox(), mon.sourceSlot()));
            }
        }
    }

    private void addPkm(long amount) {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return;
        }
        PacketDistributor.sendToServer(new OfferPkmPacket(
                UUID.randomUUID(), snap.get().tradeId(), snap.get().revision(), amount));
    }

    private long currentRevision() {
        return state.snapshot().map(TradeSnapshotPacket::revision).orElse(0L);
    }

    // ------------------------------------------------------------------ 视图切换

    private void openView(View v, AssetPageKind kind, int page) {
        view = v;
        assetKind = kind;
        assetPage = page;
        syncButtonVisibility();
        if (v == View.OWN_ASSETS) {
            requestAssetPage();
        }
    }

    private void openAssets(AssetPageKind kind) {
        openView(View.OWN_ASSETS, kind, 0);
    }

    private void openOwnAssets() {
        openView(View.OWN_ASSETS, assetKind, 0);
    }

    private void goBack() {
        if (view == View.OWN_ASSETS) {
            openView(View.WORKSPACE, assetKind, 0);
        } else if (view == View.WORKSPACE) {
            this.onClose();
        } else {
            this.onClose();
        }
    }

    private void syncButtonVisibility() {
        // 1.21.1 的 Button 只有 public visible 字段，没有 setVisible(boolean) 方法
        searchBox.visible = view == View.DIRECTORY;
        searchBox.setFocused(false);
        assetsButton.visible = view == View.WORKSPACE;
        confirmButton.visible = view == View.WORKSPACE;
        cancelButton.visible = view == View.WORKSPACE;
        backButton.visible = view == View.WORKSPACE || view == View.OWN_ASSETS;
        prefToggleButton.visible = view == View.WORKSPACE;
        directoryPrevButton.visible = view == View.DIRECTORY;
        directoryNextButton.visible = view == View.DIRECTORY;
        assetsPrevButton.visible = view == View.OWN_ASSETS;
        assetsNextButton.visible = view == View.OWN_ASSETS;
        itemsTab.visible = view == View.OWN_ASSETS;
        pkmTab.visible = view == View.OWN_ASSETS;
        partyTab.visible = view == View.OWN_ASSETS;
        pcTab.visible = view == View.OWN_ASSETS;
        pkmAdd10kButton.visible = view == View.OWN_ASSETS && assetKind == AssetPageKind.PKM;
        pkmAdd100kButton.visible = view == View.OWN_ASSETS && assetKind == AssetPageKind.PKM;
        confirmButton.active = state.snapshot()
                .map(s -> s.status() == com.pokeemc.trade.model.TradeStatus.OPEN
                        || s.status() == com.pokeemc.trade.model.TradeStatus.LOCKED)
                .orElse(false);
    }

    // ------------------------------------------------------------------ 工具

    private boolean inSelfOffer(TradeAssetPage.TradeAssetEntry e) {
        var snap = state.snapshot();
        if (snap.isEmpty()) {
            return false;
        }
        TradeSnapshotPacket.OfferSummary mine = snap.get().selfOffer();
        if (e instanceof TradeAssetPage.ItemEntry it) {
            for (TradeSnapshotPacket.ItemWire w : mine.items()) {
                if (w.itemId().equals(it.itemId()) && w.count() == it.count()) {
                    return true;
                }
            }
        } else if (e instanceof TradeAssetPage.PokemonEntry mon) {
            for (TradeSnapshotPacket.PokemonWire w : mine.pokemon()) {
                if (w.pokemonId().equals(mon.pokemonId())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String statusText(com.pokeemc.trade.model.TradeStatus s) {
        return switch (s) {
            case INVITED -> "已邀请";
            case OPEN -> "编辑中";
            case LOCKED -> "锁定";
            case COMMITTING -> "提交中";
            case COMMITTED -> "已提交";
            case DELIVERING -> "交付中";
            case COMPLETED -> "已完成";
            case CANCELLING -> "取消中";
            case CANCELLED -> "已取消";
            case FAILED_REQUIRES_ADMIN -> "需管理员";
        };
    }

    private int capColor(com.pokeemc.trade.model.TradeCapability c) {
        return switch (c) {
            case AVAILABLE -> 0x7CFC7C;
            case SELF -> 0xFFFFFF;
            case BUSY -> 0xFFCC66;
            case INVITE_PENDING -> 0x66CCFF;
            case OFFLINE -> 0x808080;
            case DISABLED_BY_PLAYER -> 0xCCCCCC;
            case DISABLED_BY_SERVER -> 0xCCCCCC;
            case PKM_UNSUPPORTED -> 0xFF9999;
            case RECOVERY_REQUIRED -> 0xFF6666;
            case RATE_LIMITED -> 0xFF4444;
        };
    }
}
