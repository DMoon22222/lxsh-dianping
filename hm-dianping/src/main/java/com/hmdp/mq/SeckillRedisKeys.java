package com.hmdp.mq;

/**
 * 秒杀 Redis Key 统一使用 voucherId 作为 Hash Tag。
 *
 * <p>这样同一张券的库存、reservation 和 Pending 数据会被 Redis Cluster
 * 路由到同一个 slot，因而可以在同一段 Lua 脚本中原子操作。</p>
 */
public final class SeckillRedisKeys {

    private SeckillRedisKeys() {
    }

    public static String stockKey(Long voucherId) {
        return "seckill:stock:" + tag(voucherId);
    }

    /**
     * 秒杀活动窗口 Hash，字段为 beginTimeMillis、endTimeMillis。
     *
     * <p>与库存使用相同 Hash Tag，使 Lua 在 Redis Cluster 中能原子校验时间和扣减库存。</p>
     */
    public static String activityTimeKey(Long voucherId) {
        return "seckill:time:" + tag(voucherId);
    }

    public static String reservationKey(Long voucherId) {
        return "seckill:reservation:" + tag(voucherId);
    }

    public static String legacyOrderKey(Long voucherId) {
        return "seckill:order:" + tag(voucherId);
    }

    public static String pendingKey(Long voucherId) {
        return "seckill:pending:" + tag(voucherId);
    }

    public static String failedKey(Long voucherId) {
        return "seckill:pending:failed:" + tag(voucherId);
    }

    public static String pendingDataKey(Long voucherId, Long orderId) {
        return "seckill:pending:data:" + tag(voucherId) + ":" + orderId;
    }

    public static String pendingDataPattern(Long voucherId) {
        return "seckill:pending:data:" + tag(voucherId) + ":*";
    }

    private static String tag(Long voucherId) {
        return "{" + voucherId + "}";
    }
}
