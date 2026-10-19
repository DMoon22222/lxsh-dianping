package com.hmdp.mq;

public final class RabbitMqConstants {
    private RabbitMqConstants() {
    }
    /**
     * 正常订单交换机，队列和路由键
     */
    public static final String ORDER_EXCHANGE =
            "hmdp.order.exchange";

    public static final String ORDER_QUEUE =
            "hmdp.order.create.queue";

    public static final String ORDER_ROUTING_KEY =
            "order.create";
    /**
     * 订单重试交换机、队列和路由键
     */
    public static final String ORDER_RETRY_EXCHANGE =
            "hmdp.order.retry.exchange";

    public static final String ORDER_RETRY_QUEUE =
            "hmdp.order.retry.queue";

    public static final String ORDER_RETRY_ROUTING_KEY =
            "order.retry";

    /**
     * 最终失败交换机、队列和路由键
     */
    public static final String ORDER_FAILED_EXCHANGE =
            "hmdp.order.failed.exchange";

    public static final String ORDER_FAILED_QUEUE =
            "hmdp.order.failed.queue";

    public static final String ORDER_FAILED_ROUTING_KEY =
            "order.failed";

    /**
     * 标识正常消费者向最终失败队列转发的内部消息。
     */
    public static final String MESSAGE_PURPOSE_HEADER =
            "x-hmdp-message-purpose";

    public static final String FAILED_MESSAGE_PURPOSE =
            "failed-order";

    public static final String FAILED_CORRELATION_PREFIX =
            "failed-order:";

    /**
     * 最终失败消费者重试耗尽后的停车交换机、队列和路由键。
     */
    public static final String ORDER_FAILED_PARKING_EXCHANGE =
            "hmdp.order.failed.parking.exchange";

    public static final String ORDER_FAILED_PARKING_QUEUE =
            "hmdp.order.failed.parking.queue";

    public static final String ORDER_FAILED_PARKING_ROUTING_KEY =
            "order.failed.parking";
    /**
     * 缓存失效广播交换机和实例队列前缀。
     */
    public static final String CACHE_INVALIDATE_EXCHANGE =
            "hmdp.cache.invalidate.exchange";

    public static final String CACHE_INVALIDATE_QUEUE_PREFIX =
            "hmdp.cache.invalidate.";

    public static final String CACHE_INVALIDATE_PARKING_EXCHANGE =
            "hmdp.cache.invalidate.parking.exchange";

    public static final String CACHE_INVALIDATE_PARKING_QUEUE =
            "hmdp.cache.invalidate.parking.queue";

    public static final String CACHE_INVALIDATE_PARKING_ROUTING_KEY =
            "cache.invalidate.parking";
}
