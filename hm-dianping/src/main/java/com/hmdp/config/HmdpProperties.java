package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "hmdp")
public class HmdpProperties {

    /**
     * Current application instance id, used to build instance-specific queues.
     */
    private String instanceId = "app-1";

    private Cache cache = new Cache();

    private IdGenerator idGenerator = new IdGenerator();

    private RateLimit rateLimit = new RateLimit();

    private Redisson redisson = new Redisson();

    private SeckillProperties seckill = new SeckillProperties();

    private OrderTask orderTask=new OrderTask();

    @Data
    public static class Cache {
        private Shop shop = new Shop();
    }

    @Data
    public static class Shop {
        /**
         * Whether shop query should use Caffeine local cache before Redis.
         */
        private boolean localEnabled = true;
    }

    @Data
    public static class IdGenerator {
        /**
         * Snowflake worker id in the current datacenter.
         */
        private long workerId = 1L;

        /**
         * Snowflake datacenter id.
         */
        private long datacenterId = 1L;
    }

    @Data
    public static class RateLimit {
        /**
         * Global switch for annotation-based Redis sliding-window rate limiting.
         */
        private boolean enabled = true;

        private RateLimitSeckill seckill = new RateLimitSeckill();
    }

    @Data
    public static class RateLimitSeckill {
        /**
         * Maximum requests allowed in one sliding window.
         */
        private int maxCount = 300;

        /**
         * Sliding window length in seconds.
         */
        private long windowSeconds = 1L;
    }

    @Data
    public static class Redisson {
        /**
         * Whether to create RedissonClient beans.
         */
        private boolean enabled = false;

        /**
         * Address of the first Redisson single-server node.
         */
        private String node1Address = "redis://localhost:6379";

        /**
         * Address of the second Redisson single-server node.
         */
        private String node2Address = "redis://localhost:6381";

        /**
         * Address of the third Redisson single-server node.
         */
        private String node3Address = "redis://localhost:6382";

        /**
         * Optional Redis password used by Redisson.
         */
        private String password = "";
    }

    @Data
    public static class SeckillProperties {
        /**
         * Whether seckill order creation should be sent to RabbitMQ asynchronously.
         */
        private boolean asyncEnabled = true;
    }
    @Data
    public static class OrderTask{
        private int corePoolSize=4;
        private int maximumPoolSize=8;
        private int queueCapacity=200;
        private long keepAliveSeconds=60L;
        private long monitorIntervalMs=30000L;
    }
}
