package com.hmdp.mq;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Arrays;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Service
public class PendingOrderService {

    // ZSet，保存需要补偿扫描的 orderId。
    public static final String PENDING_KEY = "seckill:pending";
    // ZSet，保存最终失败的 orderId。
    public static final String FAILED_KEY = "seckill:pending:failed";
    // Hash，保存订单数据。
    public static final String DATA_KEY_PREFIX = "seckill:pending:data:";
    // 订单数据的过期时间，1天
    private static final long DATA_TTL_SECONDS = 86400;
    // 首次发送失败后等待10秒再重试，之后采用指数退避。
    private static final long INITIAL_RETRY_DELAY_MILLIS = 10_000L;
    // 单次重试最长等待5分钟。
    private static final long MAX_RETRY_DELAY_MILLIS = 300_000L;
    private static final DefaultRedisScript<Long> RESTORE_SCRIPT;

    static {
        RESTORE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_SCRIPT.setLocation(new ClassPathResource("seckill_restore.lua"));
        RESTORE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 标记这条订单消息正在发送RabbitMQ 状态由PENDING变为SENDING
     * @param orderId
     */
    public void markSending(Long orderId) {
        String dataKey = dataKey(orderId);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "SENDING");
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * RabbitMQ Broker Confirm ACK 后调用，表示 Broker 已经收到消息。
     * 从 seckill:pending 中移除 orderId
     * 把 status 改成 SENT
     * @param orderId
     */
    public void markSendSuccess(Long orderId) {
        String dataKey = dataKey(orderId);
        Object status = stringRedisTemplate.opsForHash().get(dataKey, "status");

        if ("ROUTE_FAILED".equals(status)
                || "BROKER_NACK".equals(status)
                || "CONSUMING".equals(status)
                || "ORDER_CREATED".equals(status)
                || "FAILED".equals(status)) {
            return;
        }

        stringRedisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "SENT");
        stringRedisTemplate.opsForHash().put(dataKey, "sentTime", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * 作用：Confirm NACK 或 convertAndSend() 同步异常时调用。
     * status = BROKER_NACK
     * lastError = 失败原因
     * nextRetryTime 使用指数退避计算，最长等待5分钟
     * 重新加入 seckill:pending
     * Broker 没有可靠接收到消息；
     * 不能丢掉这笔 Redis 预扣成功的订单；
     * 交给定时任务稍后重投。
     * @param orderId
     * @param reason
     */
    public void markSendFailed(Long orderId, String reason) {
        long now = System.currentTimeMillis();
        String dataKey = dataKey(orderId);
        long retryDelay = calculateRetryDelay(dataKey);
        long nextRetryTime = now + retryDelay;
        stringRedisTemplate.opsForHash().put(dataKey, "status", "BROKER_NACK");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "retryDelayMillis", String.valueOf(retryDelay));
        stringRedisTemplate.opsForHash().put(dataKey, "nextRetryTime", String.valueOf(nextRetryTime));
        stringRedisTemplate.opsForZSet().add(PENDING_KEY, orderId.toString(), nextRetryTime);
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * ReturnCallback 触发时调用，表示消息到达 Exchange，但没有路由到 Queue。
     * 这种情况下，消费者永远收不到消息，所以必须补偿重投。
     * @param orderMessage
     * @param reason
     */

    public void markRouteFailed(VoucherOrderMessage orderMessage, String reason) {
        long now = System.currentTimeMillis();
        Long orderId = orderMessage.getOrderId();
        String dataKey = dataKey(orderId);
        long retryDelay = calculateRetryDelay(dataKey);
        long nextRetryTime = now + retryDelay;

        stringRedisTemplate.opsForHash().put(dataKey, "orderId", orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "userId", orderMessage.getUserId().toString());
        stringRedisTemplate.opsForHash().put(dataKey, "voucherId", orderMessage.getVoucherId().toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "ROUTE_FAILED");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "retryDelayMillis", String.valueOf(retryDelay));
        stringRedisTemplate.opsForHash().put(dataKey, "nextRetryTime", String.valueOf(nextRetryTime));
        stringRedisTemplate.opsForZSet().add(PENDING_KEY, orderId.toString(), nextRetryTime);
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * 定时补偿任务准备重投某条消息之前调用。
     * 记录这条消息已经重投过几次；
     * 避免无限重投；
     * 后续如果超过最大次数，就转入失败集合。
     * retryCount + 1
     * status = REPUBLISHING
     * lastRetryTime = 当前时间
     * @param orderId
     */

    public void beforeRepublish(Long orderId) {
        String dataKey = dataKey(orderId);
        Long retryCount = stringRedisTemplate.opsForHash().increment(dataKey, "retryCount", 1);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "REPUBLISHING");
        stringRedisTemplate.opsForHash().put(dataKey, "lastRetryTime", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().put(dataKey, "retryCount", String.valueOf(retryCount));
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * 这条订单消息已经无法自动恢复，标记为最终失败。
     * 从 seckill:pending 移除
     * 加入 seckill:pending:failed
     * status = FAILED
     * lastError = 失败原因
     * failedTime = 当前时间
     * 不让定时任务一直扫描同一条坏数据；
     * 保留失败记录，方便人工排查；
     * 可以后续做后台补偿页面或报警。
     * @param orderId
     * @param reason
     */

    public void markFailed(Long orderId, String reason) {
        long now = System.currentTimeMillis();
        String dataKey = dataKey(orderId);
        stringRedisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
        stringRedisTemplate.opsForZSet().add(FAILED_KEY, orderId.toString(), now);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "FAILED");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "failedTime", String.valueOf(now));
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    public Long restorePreDeduct(Long orderId, Long userId, Long voucherId) {
        return stringRedisTemplate.execute(
                RESTORE_SCRIPT,
                Arrays.asList(
                        "seckill:stock:" + voucherId,
                        "seckill:order:" + voucherId,
                        "seckill:restore:" + orderId,
                        PENDING_KEY
                ),
                userId.toString(),
                orderId.toString(),
                String.valueOf(System.currentTimeMillis())
        );
    }

    /**
     * 记录消费者开始处理的时间。Confirm 回调可能晚于消费开始，因此不能让
     * Confirm 回调把 CONSUMING 覆盖回 SENT。
     */
    public void markConsuming(Long orderId, long consumeStartedAt) {
        String dataKey = dataKey(orderId);
        Object status = stringRedisTemplate.opsForHash().get(dataKey, "status");
        if ("ORDER_CREATED".equals(status) || "FAILED".equals(status)) {
            return;
        }

        stringRedisTemplate.opsForHash().put(dataKey, "status", "CONSUMING");
        stringRedisTemplate.opsForHash().putIfAbsent(
                dataKey,
                "consumeStartTime",
                String.valueOf(consumeStartedAt)
        );
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * 订单已经确定创建成功，清理待补偿状态
     * 从 seckill:pending 移除 orderId
     * status = ORDER_CREATED
     * 刷新 TTL
     * 消费者正常创建订单成功
     * 重复消费触发唯一索引，说明订单其实已经存在
     * 补偿任务发现 MySQL 中已经有订单
     * @param orderId
     */

    public void removePending(Long orderId) {
        removePending(orderId, System.currentTimeMillis());
    }

    /**
     * 在 MySQL 事务成功返回后调用，记录可用于端到端压测的完成时间。
     */
    public void removePending(Long orderId, long dbCommittedAt) {
        String dataKey = dataKey(orderId);
        Long acceptedAt = readLong(dataKey, "createTime");
        Long consumeStartedAt = readLong(dataKey, "consumeStartTime");

        stringRedisTemplate.opsForZSet().remove(PENDING_KEY, orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "ORDER_CREATED");
        stringRedisTemplate.opsForHash().put(
                dataKey,
                "dbCommittedTime",
                String.valueOf(dbCommittedAt)
        );
        if (acceptedAt != null) {
            stringRedisTemplate.opsForHash().put(
                    dataKey,
                    "endToEndMillis",
                    String.valueOf(Math.max(0L, dbCommittedAt - acceptedAt))
            );
        }
        if (acceptedAt != null && consumeStartedAt != null) {
            stringRedisTemplate.opsForHash().put(
                    dataKey,
                    "queueDelayMillis",
                    String.valueOf(Math.max(0L, consumeStartedAt - acceptedAt))
            );
        }
        if (consumeStartedAt != null) {
            stringRedisTemplate.opsForHash().put(
                    dataKey,
                    "dbProcessMillis",
                    String.valueOf(Math.max(0L, dbCommittedAt - consumeStartedAt))
            );
        }
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
    }

    /**
     * 给定时补偿任务用，扫描到期需要重投的订单 ID。
     * 从 seckill:pending 里找 score <= 当前时间的 orderId
     * 一次最多取 limit 条
     * @param now
     * @param limit
     * @return
     */

    public Set<String> listDueOrderIds(long now, int limit) {
        return stringRedisTemplate.opsForZSet()
                .rangeByScore(PENDING_KEY, 0, now, 0, limit);
    }

    /**
     * 读取某个订单的 PENDING 详情。
     * @param orderId
     * @return
     */
    public Map<Object, Object> getData(Long orderId) {
        return stringRedisTemplate.opsForHash().entries(dataKey(orderId));
    }

    public String dataKey(Long orderId) {
        return DATA_KEY_PREFIX + orderId;
    }

    private long calculateRetryDelay(String dataKey) {
        Object retryCountValue = stringRedisTemplate.opsForHash().get(dataKey, "retryCount");
        int retryCount = 0;
        if (retryCountValue != null) {
            try {
                retryCount = Integer.parseInt(String.valueOf(retryCountValue));
            } catch (NumberFormatException ignored) {
                retryCount = 0;
            }
        }

        int exponent = Math.min(Math.max(retryCount, 0), 5);
        long delay = INITIAL_RETRY_DELAY_MILLIS * (1L << exponent);
        return Math.min(delay, MAX_RETRY_DELAY_MILLIS);
    }

    private String safe(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() > 500 ? reason.substring(0, 500) : reason;
    }

    private Long readLong(String dataKey, String field) {
        Object value = stringRedisTemplate.opsForHash().get(dataKey, field);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
