package com.pokeemc.exchange.command;

import com.pokeemc.config.PokeTradeConfig;
import com.pokeemc.config.PokeTradeConfig.ExchangeMode;
import com.pokeemc.exchange.price.ExchangePriceService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * [NEW] 会话 #21-H：目录模式指令的可测核心——{@code parseMode}（learning/full 大小写不敏感、
 * 非法抛异常）与 {@code applyMode}（解析 → 写配置 → 仅当模式真正变化时递增目录版本并触发
 * 目录变更通知）。
 *
 * <p>JUnit 下服务端 SPEC 未加载：{@code exchangeMode()} 恒回退 LEARNING、{@code setExchangeMode}
 * 为 no-op。因此「同值不通知」以「解析模式 == 有效当前模式（LEARNING）时不递增版本」验证——
 * 这正是 guard {@code before != mode} 在未加载环境下的可观测行为。</p>
 */
class ExchangeModeCommandTest {

    @Test
    void parseModeAcceptsCaseInsensitiveNames() {
        assertEquals(ExchangeMode.LEARNING, ExchangeModeCommand.parseMode("learning"));
        assertEquals(ExchangeMode.LEARNING, ExchangeModeCommand.parseMode("LEARNING"));
        assertEquals(ExchangeMode.FULL, ExchangeModeCommand.parseMode("full"));
        assertEquals(ExchangeMode.FULL, ExchangeModeCommand.parseMode("Full"));
    }

    @Test
    void parseModeRejectsUnknownOrBlankNames() {
        assertThrows(IllegalArgumentException.class, () -> ExchangeModeCommand.parseMode("bogus"));
        assertThrows(IllegalArgumentException.class, () -> ExchangeModeCommand.parseMode(""));
        assertThrows(IllegalArgumentException.class, () -> ExchangeModeCommand.parseMode(null));
        assertThrows(IllegalArgumentException.class, () -> ExchangeModeCommand.parseMode(" learning "));
    }

    @Test
    void applyModeOnlyNotifiesWhenParsedModeDiffersFromCurrent() {
        // 注入 map 构造（live=false）绕过 Minecraft 注册表；rebuild() 后 catalogVersion 从 0 → 1。
        ExchangePriceService svc = new ExchangePriceService(Map.of(), Map.of(), Map.of());
        long base = svc.catalogVersion();
        assertEquals(ExchangeMode.LEARNING, ExchangeModeCommand.applyMode("learning", svc));
        assertEquals(base, svc.catalogVersion(),
                "解析模式与当前一致（LEARNING）→ 不得递增版本/触发目录变更通知");

        assertEquals(ExchangeMode.FULL, ExchangeModeCommand.applyMode("full", svc));
        assertEquals(base + 1, svc.catalogVersion(), "模式变化 → 递增版本并触发目录变更通知");
    }
}
