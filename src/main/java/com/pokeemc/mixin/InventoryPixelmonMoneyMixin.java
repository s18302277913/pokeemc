package com.pokeemc.mixin;

import com.pokeemc.client.ExchangeUiModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.math.BigDecimal;

/**
 * [CHANGED] 会话 #27：Pixelmon 原版物品栏队伍面板底部钱包金额缩写。
 * <p>
 * 目标：Pixelmon 在 {@code InventoryPixelmon.drawGuiContainerBackgroundLayer} 中
 * {@code ScreenHelper.drawStringRightAligned(g, NumberFormat.getInstance().format(ClientData.playerMoney), ...)}
 * 绘制钱包余额（如 {@code 5,632,000}），玩家物品栏（生存 {@code InventoryPixelmonExtendedScreen}、
 * 创造 {@code CreativeInventoryExtendedScreen}）共用该 {@code InventoryPixelmon}，因此一处注入同时覆盖两种界面。
 * 本 Mixin 只把传入 drawStringRightAligned 的第二个参数（纯数字文本）替换为 {@link ExchangeUiModel#formatWallet(long)}
 * 缩写（如 {@code 5.6m}）；「₽」前缀由 Pixelmon 其他位置独立绘制，保持不变。
 * <p>
 * NeoForge 采用官方映射、无 remap，Mixin 目标类名/方法名为原始（未混淆）签名，由
 * {@code neoforge.mods.toml} 的 {@code [[mixins]]} 声明经 FML {@code LoadingModList.addMixinConfigs()} 注册。
 */
@Mixin(targets = "com.pixelmonmod.pixelmon.client.gui.inventory.InventoryPixelmon")
public abstract class InventoryPixelmonMoneyMixin {

    /**
     * 仅替换金额数字文本（index = 1，即 {@code NumberFormat.getInstance().format(playerMoney)} 的结果）。
     * 非纯数字输入（异常/未来版本改动）原样返回，保证不破坏 Pixelmon 原生显示。
     */
    @ModifyArg(
            method = "drawGuiContainerBackgroundLayer",
            at = @At(value = "INVOKE",
                    target = "Lcom/pixelmonmod/pixelmon/client/gui/ScreenHelper;drawStringRightAligned"
                            + "(Lnet/minecraft/client/gui/GuiGraphics;Ljava/lang/String;FFIZZ)V"),
            index = 1)
    private String pokeemc$abbreviateMoneyText(String original) {
        String plain = original.replace(",", "").trim();
        try {
            long amount = new BigDecimal(plain).longValue();
            return ExchangeUiModel.formatWallet(amount);
        } catch (NumberFormatException | ArithmeticException ex) {
            return original;
        }
    }
}
