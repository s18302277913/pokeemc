package com.pokeemc.resources;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布命名空间回归测试：构建产物只应发布 {@code poketrade} 命名空间。
 *
 * <p>扫描 {@code src/main/resources}，断言旧的 {@code assets/pokeemc}、
 * {@code data/pokeemc} 目录已删除，且文本资源中不存在任何未批准的
 * {@code pokeemc:}、{@code itemGroup.pokeemc} 或 {@code dependencies.pokeemc}。
 * 测试夹具不参与该目录，因此无需豁免列表。</p>
 */
class PublishedNamespaceTest {

    private static final List<String> FORBIDDEN = List.of(
            "pokeemc:", "itemGroup.pokeemc", "dependencies.pokeemc");

    @Test
    void legacyResourceDirectoriesAreRemoved() {
        Path resources = resourcesRoot();
        assertFalse(Files.exists(resources.resolve("assets/pokeemc")),
                "assets/pokeemc 目录必须已迁移");
        assertFalse(Files.exists(resources.resolve("data/pokeemc")),
                "data/pokeemc 目录必须已迁移");
    }

    @Test
    void noLegacyNamespaceStringsInTextResources() throws IOException {
        Path resources = resourcesRoot();
        try (Stream<Path> paths = Files.walk(resources)) {
            paths.filter(Files::isRegularFile)
                    .filter(PublishedNamespaceTest::isText)
                    .forEach(PublishedNamespaceTest::assertNoForbidden);
        }
    }

    private static void assertNoForbidden(Path file) {
        String rel = file.getFileName().toString();
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            for (String needle : FORBIDDEN) {
                assertFalse(content.contains(needle),
                        () -> rel + " 仍包含不允许的旧命名空间引用: " + needle);
            }
        } catch (IOException e) {
            throw new AssertionError("无法读取资源文件: " + rel, e);
        }
    }

    private static boolean isText(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".json") || name.endsWith(".toml")
                || name.endsWith(".txt") || name.endsWith(".md");
    }

    private static Path resourcesRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path candidate = cwd.resolve("src/main/resources");
        for (int i = 0; i < 4 && !Files.isDirectory(candidate); i++) {
            Path parent = candidate.getParent();
            if (parent == null) {
                break;
            }
            candidate = parent.getParent() == null ? parent : parent.getParent();
        }
        assertTrue(Files.isDirectory(candidate), "找不到 src/main/resources: " + candidate);
        return candidate.normalize();
    }
}
