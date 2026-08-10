package com.pokeemc.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 会话 #16（bug 1 防御）：交易所右键菜单 7 项 + 悬停 tooltip + 分类/按钮键必须在
 * 中英语言文件中都存在且非空——键缺失时 {@code Component.translatable(key).getString()}
 * 回退显示原始键名，玩家看到的就是「乱码」（类 base64 的键名串）。
 */
class ExchangeLangKeysTest {

    /** 右键菜单 7 项标签键（与 ExchangeScreen.CONTEXT_MENU_ENTRIES 一致）。 */
    private static final Set<String> MENU_LABEL_KEYS = Set.of(
            "poketrade.exchange.pickup",
            "poketrade.exchange.withdraw.to_inventory",
            "poketrade.exchange.sell.toggle",
            "poketrade.exchange.batch.withdraw",
            "poketrade.exchange.batch.sell.storage",
            "poketrade.exchange.batch.sell.nearby",
            "poketrade.exchange.batch.sell.whole");

    /** 右键菜单悬停 tooltip 键（对应 4 项批量操作的 .tip 键）。 */
    private static final Set<String> MENU_TOOLTIP_KEYS = Set.of(
            "poketrade.exchange.batch.withdraw.tip",
            "poketrade.exchange.batch.sell.storage.tip",
            "poketrade.exchange.batch.sell.nearby.tip",
            "poketrade.exchange.batch.sell.whole.tip");

    /** 其他依赖的按钮/分类键。 */
    private static final Set<String> MISC_KEYS = Set.of(
            "poketrade.exchange.sell.whole",
            "poketrade.category.pokeballs");

    private static JsonObject load(String file) throws IOException {
        Path p = Path.of("src/main/resources/assets/poketrade/lang").resolve(file);
        String raw = Files.readString(p, StandardCharsets.UTF_8);
        JsonElement root = JsonParser.parseString(raw);
        return root.getAsJsonObject();
    }

    @Test
    void rightClickMenuKeysExistInBothLanguages() throws IOException {
        for (String lang : new String[]{"zh_cn.json", "en_us.json"}) {
            JsonObject obj = load(lang);
            for (String key : MENU_LABEL_KEYS) {
                assertTrue(obj.has(key), lang + " 缺菜单键 " + key);
                assertFalse(obj.get(key).getAsString().isBlank(), lang + " 菜单键空值 " + key);
            }
            for (String key : MENU_TOOLTIP_KEYS) {
                assertTrue(obj.has(key), lang + " 缺 tooltip 键 " + key);
                assertFalse(obj.get(key).getAsString().isBlank(), lang + " tooltip 键空值 " + key);
            }
            for (String key : MISC_KEYS) {
                assertTrue(obj.has(key), lang + " 缺依赖键 " + key);
                assertFalse(obj.get(key).getAsString().isBlank(), lang + " 依赖键空值 " + key);
            }
        }
    }

    @Test
    void langValuesAreCleanUtf8WithoutReplacementChars() throws IOException {
        for (String lang : new String[]{"zh_cn.json", "en_us.json"}) {
            String raw = Files.readString(
                    Path.of("src/main/resources/assets/poketrade/lang").resolve(lang),
                    StandardCharsets.UTF_8);
            // 替换符 U+FFFD 或非法控制字符意味着文件以错误编码写入（mojibake 来源）
            assertFalse(raw.contains("�"), lang + " 含替换符 U+FFFD");
        }
    }
}
