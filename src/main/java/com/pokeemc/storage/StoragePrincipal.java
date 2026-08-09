package com.pokeemc.storage;

import java.util.Objects;
import java.util.UUID;

/**
 * 授权主体。
 *
 * <ul>
 *   <li>{@link Player}：精确玩家 UUID。</li>
 *   <li>{@link Public}：所有玩家。</li>
 *   <li>{@link Group}：服务器权限组适配器预留 {@code GROUP:<provider>:<id>}；
 *       未安装组权限适配器时忽略而不是误授权。</li>
 * </ul>
 */
public sealed interface StoragePrincipal permits StoragePrincipal.Player, StoragePrincipal.Public, StoragePrincipal.Group {

    /** 精确玩家。 */
    record Player(UUID uuid) implements StoragePrincipal {
        public Player {
            Objects.requireNonNull(uuid, "uuid");
        }
    }

    /** 所有玩家。 */
    record Public() implements StoragePrincipal {
    }

    /** 权限组主体。 */
    record Group(String provider, String id) implements StoragePrincipal {
        public Group {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(id, "id");
            if (provider.isEmpty() || id.isEmpty()) {
                throw new IllegalArgumentException("provider and id must be non-empty");
            }
        }

        @Override
        public String toString() {
            return "GROUP:" + provider + ":" + id;
        }
    }
}
