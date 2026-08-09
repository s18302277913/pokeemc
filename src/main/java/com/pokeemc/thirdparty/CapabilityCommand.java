package com.pokeemc.thirdparty;

import com.mojang.brigadier.CommandDispatcher;
import com.poketrade.api.capability.CapabilityEntry;
import com.poketrade.api.capability.CapabilityProbe;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * 能力探测报告命令：`/poketrade capability`。
 * 报告生成（{@link #buildReport}）为纯函数，可直接 JVM 单测。
 */
public final class CapabilityCommand {

    private CapabilityCommand() {
    }

    /** 注册 /poketrade capability（挂在 PokeEMC 构造的 NeoForge.EVENT_BUS）。 */
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("poketrade")
                .then(Commands.literal("capability")
                        .executes(ctx -> sendReport(ctx.getSource()))));
    }

    private static int sendReport(CommandSourceStack source) {
        CapabilityProbe probe = ThirdPartyServices.capabilityProbe();
        source.sendSuccess(() -> Component.literal(buildReport(probe)), false);
        return 1;
    }

    /** 纯文本能力报告：四组分组 + (计数) + 条目列表。 */
    public static String buildReport(CapabilityProbe probe) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== PokeTrade Capability Report ===\n");
        sb.append("apiVersion: ").append(probe.apiVersion()).append('\n');
        appendSection(sb, "容器适配器", probe.storageAdapters());
        appendSection(sb, "保护 Provider", probe.protectionProviders());
        appendSection(sb, "经济后端", probe.economyBackends());
        appendSection(sb, "未适配第三方", probe.unadaptedMods().stream()
                .map(id -> new CapabilityEntry(id, "none", false)).toList());
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, List<CapabilityEntry> entries) {
        sb.append(title).append(" (").append(entries.size()).append("):\n");
        for (CapabilityEntry entry : entries) {
            sb.append("  - ").append(entry.id()).append(" (")
                    .append(entry.implementation()).append(", ")
                    .append(entry.active() ? "active" : "inactive").append(")\n");
        }
    }
}
