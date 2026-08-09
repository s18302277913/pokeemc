package com.pokeemc.client;

import com.pokeemc.emc.PKMManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.text.NumberFormat;

/**
 * 在物品 tooltip 上显示 PKM 值。
 * 格式：PKM: 1,024（黄色）
 */
public class TooltipEvents {

    private static final NumberFormat FORMATTER = NumberFormat.getIntegerInstance();

    @SubscribeEvent
    public void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty()) {
            return;
        }
        long pkm = PKMManager.getPkm(stack);
        if (pkm < 0) {
            return;
        }
        event.getToolTip().add(Component.translatable("poketrade.gui.pkm").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(": ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(FORMATTER.format(pkm)).withStyle(ChatFormatting.WHITE)));
    }
}
