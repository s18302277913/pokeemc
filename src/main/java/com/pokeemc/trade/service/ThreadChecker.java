package com.pokeemc.trade.service;

/**
 * 服务端线程守卫：所有 TradeService 公开写操作第一行调用 {@link #check()}，
 * 确保 Minecraft/Pixelmon 存储读写都在服务端主线程。测试注入总是通过的实现。
 */
@FunctionalInterface
public interface ThreadChecker {

    /** 非服务端主线程时抛出 IllegalStateException */
    void check();

    /** 测试用：总是通过 */
    static ThreadChecker always() {
        return () -> {
        };
    }
}
