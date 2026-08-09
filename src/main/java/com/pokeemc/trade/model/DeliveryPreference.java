package com.pokeemc.trade.model;

/**
 * 每位接收者的收货去向偏好（计划 2.6）。由本人设置并持久化，
 * 双方确认时复制进当前 revision 冻结；锁定期间修改全局偏好不影响本次交易。
 * <p>
 * 任何目标无容量时必须降级到持久化收件箱，绝不能丢弃资产。
 */
public record DeliveryPreference(
        ItemDestination itemDestination,
        PokemonDestination pokemonDestination
) {

    public DeliveryPreference {
        if (itemDestination == null) {
            throw new IllegalArgumentException("itemDestination cannot be null");
        }
        if (pokemonDestination == null) {
            throw new IllegalArgumentException("pokemonDestination cannot be null");
        }
    }

    public static DeliveryPreference defaults() {
        return new DeliveryPreference(ItemDestination.AUTO, PokemonDestination.AUTO);
    }

    /** 物品去向：AUTO 先背包后收件箱 */
    public enum ItemDestination {
        AUTO,
        INVENTORY,
        ENDER_CHEST,
        INBOX
    }

    /** 宝可梦去向：AUTO 先 Party 后 PC 再收件箱；显式 PARTY/PC 无空间时也进入收件箱 */
    public enum PokemonDestination {
        AUTO,
        PARTY,
        PC,
        INBOX
    }
}
