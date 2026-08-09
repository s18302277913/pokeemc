package com.poketrade.api.storage;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二进制表面快照测试：锁定 {@code com.poketrade.api.storage} 的公共 API 表面，
 * 禁止泄漏 Minecraft/NeoForge/Pixelmon/根模组实现类型。
 */
class StorageApiSurfaceTest {

    private static final Set<String> FORBIDDEN_PREFIXES = Set.of(
            "net.minecraft", "net.neoforged", "com.pokeemc", "com.pixelmonmod");

    private static final List<Class<?>> API_TYPES = List.of(
            StorageId.class, StorageCapability.class, StorageDescriptor.class,
            StorageQuery.class, StorageQuery.Sort.class, StorageQuery.Filter.class,
            StorageSnapshot.class, StorageItemSlot.class, StorageEndpoint.class,
            StorageEndpoint.Kind.class, StorageTransaction.class,
            StorageTransactionResult.class, StorageService.class, StorageAdapter.class,
            StorageAdapterContext.class, StorageHandle.class, StorageAdapterRegistry.class);

    @Test
    void publicApiNeverLeaksGameTypes() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : API_TYPES) {
            inspectType(type, violations);
        }
        assertTrue(violations.isEmpty(),
                "API surface leaks game types:\n" + String.join("\n", violations));
    }

    @Test
    void expectedPublicTypesExist() {
        for (Class<?> type : API_TYPES) {
            assertTrue(type.getPackage().getName().equals("com.poketrade.api.storage"),
                    type.getName() + " not in storage package");
        }
    }

    @Test
    void onlyExpectedPublicTypesArePublished() throws Exception {
        Set<String> expectedTopLevel = API_TYPES.stream()
                .map(Class::getName)
                .filter(n -> !n.contains("$"))
                .collect(Collectors.toSet());

        for (String classPath : discoverPackageClasses()) {
            if (classPath.endsWith("Test.class") || classPath.contains("Test$")) {
                continue; // 测试类不属于 API 表面
            }
            String binaryName = classPath.substring(0, classPath.length() - ".class".length())
                    .replace('/', '.');
            String topLevel = binaryName.substring(0, binaryName.indexOf('$') == -1
                    ? binaryName.length() : binaryName.indexOf('$'));
            assertTrue(expectedTopLevel.contains(topLevel),
                    "Unexpected public type in storage package: " + binaryName);
        }
    }

    private static void inspectType(Class<?> type, List<String> violations) {
        if (type == null || type.isPrimitive() || type == Object.class || type == Enum.class) {
            return;
        }
        checkName(type.getName(), type, violations);

        // 父类与接口
        inspectType(type.getSuperclass(), violations);
        for (Class<?> iface : type.getInterfaces()) {
            inspectType(iface, violations);
        }

        // 泛型参数
        for (TypeVariable<?> tv : type.getTypeParameters()) {
            checkType(tv, type, violations);
        }

        // 公开字段
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isPrivate(field.getModifiers())) {
                checkType(field.getGenericType(), type, violations);
            }
        }

        // 公开方法（含继承的接口默认方法）
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    && method.isSynthetic()) {
                continue;
            }
            checkType(method.getGenericReturnType(), type, violations);
            for (Type param : method.getGenericParameterTypes()) {
                checkType(param, type, violations);
            }
            for (Type ex : method.getGenericExceptionTypes()) {
                checkType(ex, type, violations);
            }
        }
    }

    private static void checkType(Type type, Class<?> owner, List<String> violations) {
        if (type instanceof Class<?> c) {
            checkName(c.getName(), owner, violations);
        } else if (type instanceof ParameterizedType p) {
            checkType(p.getRawType(), owner, violations);
            for (Type arg : p.getActualTypeArguments()) {
                checkType(arg, owner, violations);
            }
            if (p.getOwnerType() != null) {
                checkType(p.getOwnerType(), owner, violations);
            }
        } else if (type instanceof WildcardType w) {
            for (Type upper : w.getUpperBounds()) {
                checkType(upper, owner, violations);
            }
            for (Type lower : w.getLowerBounds()) {
                checkType(lower, owner, violations);
            }
        } else if (type instanceof GenericArrayType g) {
            checkType(g.getGenericComponentType(), owner, violations);
        } else if (type instanceof TypeVariable<?> v) {
            for (Type bound : v.getBounds()) {
                checkType(bound, owner, violations);
            }
        }
    }

    private static void checkName(String name, Class<?> owner, List<String> violations) {
        for (String forbidden : FORBIDDEN_PREFIXES) {
            if (name.startsWith(forbidden)) {
                violations.add(owner.getName() + " references " + name);
            }
        }
    }

    private static List<String> discoverPackageClasses() throws Exception {
        List<String> result = new ArrayList<>();
        String packagePath = "com/poketrade/api/storage";
        Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                .getResources(packagePath);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            collect(url, packagePath, result);
        }
        return result;
    }

    private static void collect(URL url, String base, List<String> out)
            throws URISyntaxException {
        if ("file".equals(url.getProtocol())) {
            File dir = new File(url.toURI());
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".class")) {
                    out.add(base + "/" + file.getName());
                }
            }
        }
    }
}
