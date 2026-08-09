package com.pokeemc.id;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 迁移回归测试：四个注册表（方块、物品、方块实体、菜单）在调用
 * {@code register(bus)} 之前必须已完成全部 {@code addAlias(legacy, current)}
 * 别名安装，且别名内容与 {@link ModIdAliases} 完全一致。
 *
 * <p>生产注册类（ModBlocks 等）的静态初始化会触发 DeferredHolder 绑定，
 * 而后者依赖游戏内置注册表（Bootstrap.bootStrap 需要 FML 加载环境），
 * 无法在纯单元测试中加载。因此本测试用与生产代码相同的调用序列
 * （遍历 ModIdAliases 映射 → addAlias → register(bus)）在真实的
 * DeferredRegister 上验证框架机制；别名内容与集合完整性由
 * ModIdAliasesTest 覆盖。</p>
 */
class RegistryAliasInstallationTest {

    @Test
    void blocksInstallAllAliasesBeforeBusRegistration() {
        assertInstallation(Registries.BLOCK, ModIdAliases.blockAliases());
    }

    @Test
    void itemsInstallAllAliasesBeforeBusRegistration() {
        assertInstallation(Registries.ITEM, ModIdAliases.itemAliases());
    }

    @Test
    void blockEntitiesInstallAllAliasesBeforeBusRegistration() {
        assertInstallation(Registries.BLOCK_ENTITY_TYPE, ModIdAliases.blockEntityAliases());
    }

    @Test
    void menusInstallAllAliasesBeforeBusRegistration() {
        assertInstallation(Registries.MENU, ModIdAliases.menuAliases());
    }

    private static <T> void assertInstallation(
            ResourceKey<? extends Registry<T>> registryKey, Map<String, String> aliases) {
        DeferredRegister<T> dr = DeferredRegister.create(registryKey, "poketrade");
        Map<String, String> atBusTime = new LinkedHashMap<>();
        IEventBus bus = recordingBus(() -> atBusTime.putAll(stringAliases(dr)));

        aliases.forEach((from, to) ->
                dr.addAlias(ResourceLocation.parse(from), ResourceLocation.parse(to)));
        dr.register(bus);

        assertEquals(aliases, atBusTime,
                "register(bus) 触发瞬间必须已安装全部 legacy→current 别名");
    }

    /** 记录型事件总线：每次 addListener 调用时快照当前别名表。 */
    private static IEventBus recordingBus(Runnable onAddListener) {
        return (IEventBus) Proxy.newProxyInstance(
                RegistryAliasInstallationTest.class.getClassLoader(),
                new Class<?>[]{IEventBus.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("addListener")) {
                        onAddListener.run();
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> stringAliases(DeferredRegister<?> dr) {
        try {
            Field field = DeferredRegister.class.getDeclaredField("aliases");
            field.setAccessible(true);
            Map<ResourceLocation, ResourceLocation> raw =
                    (Map<ResourceLocation, ResourceLocation>) field.get(dr);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((from, to) -> result.put(from.toString(), to.toString()));
            return result;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法读取 DeferredRegister 别名表", e);
        }
    }
}
