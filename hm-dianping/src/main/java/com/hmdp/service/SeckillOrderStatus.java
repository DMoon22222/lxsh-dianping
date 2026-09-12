package com.hmdp.service;

/** 面向客户端的秒杀订单状态常量。 */
public final class SeckillOrderStatus {

    public static final String PROCESSING = "PROCESSING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String NOT_FOUND = "NOT_FOUND";

    private SeckillOrderStatus() {
    }
}
