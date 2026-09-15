package com.hmdp.mq;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/**
 * 管理 Redis 中的订单投递状态。
 *
 * <p>所有 Pending Key 都按 voucherId 分片，并带相同 Hash Tag；因此同一张券的
 * 库存、reservation 与 Pending 可以安全地在 Redis Cluster 的同一 Lua 脚本中操作。</p>
 */
@Service
public class PendingOrderService {

    private static final long DATA_TTL_SECONDS = 86400;
    private static final long INITIAL_RETRY_DELAY_MILLIS = 10_000L;
    private static final long MAX_RETRY_DELAY_MILLIS = 300_000L;
    private static final DefaultRedisScript<Long> RESTORE_SCRIPT;

    static {
        RESTORE_SCRIPT = new DefaultRedisScript<>();
        RESTORE_SCRIPT.setLocation(new ClassPathResource("seckill_restore.lua"));
        RESTORE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void markSending(Long voucherId, Long orderId) {
        String dataKey = dataKey(voucherId, orderId);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "SENDING");
        refreshDataTtl(dataKey);
    }

    public void markSendSuccess(Long voucherId, Long orderId) {
        String dataKey = dataKey(voucherId, orderId);
        Object status = stringRedisTemplate.opsForHash().get(dataKey, "status");
        if ("ROUTE_FAILED".equals(status)
                || "BROKER_NACK".equals(status)
                || "CONSUMING".equals(status)
                || "ORDER_CREATED".equals(status)
                || "FAILED".equals(status)) {
            return;
        }

        stringRedisTemplate.opsForZSet().remove(pendingKey(voucherId), orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "SENT");
        stringRedisTemplate.opsForHash().put(dataKey, "sentTime", String.valueOf(System.currentTimeMillis()));
        refreshDataTtl(dataKey);
    }

    public void markSendFailed(Long voucherId, Long orderId, String reason) {
        long now = System.currentTimeMillis();
        String dataKey = dataKey(voucherId, orderId);
        long retryDelay = calculateRetryDelay(dataKey);
        long nextRetryTime = now + retryDelay;
        stringRedisTemplate.opsForHash().put(dataKey, "status", "BROKER_NACK");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "retryDelayMillis", String.valueOf(retryDelay));
        stringRedisTemplate.opsForHash().put(dataKey, "nextRetryTime", String.valueOf(nextRetryTime));
        stringRedisTemplate.opsForZSet().add(pendingKey(voucherId), orderId.toString(), nextRetryTime);
        refreshDataTtl(dataKey);
    }

    public void markRouteFailed(VoucherOrderMessage orderMessage, String reason) {
        Long voucherId = orderMessage.getVoucherId();
        Long orderId = orderMessage.getOrderId();
        long now = System.currentTimeMillis();
        String dataKey = dataKey(voucherId, orderId);
        long retryDelay = calculateRetryDelay(dataKey);
        long nextRetryTime = now + retryDelay;

        stringRedisTemplate.opsForHash().put(dataKey, "orderId", orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "userId", orderMessage.getUserId().toString());
        stringRedisTemplate.opsForHash().put(dataKey, "voucherId", voucherId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "ROUTE_FAILED");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "retryDelayMillis", String.valueOf(retryDelay));
        stringRedisTemplate.opsForHash().put(dataKey, "nextRetryTime", String.valueOf(nextRetryTime));
        stringRedisTemplate.opsForZSet().add(pendingKey(voucherId), orderId.toString(), nextRetryTime);
        refreshDataTtl(dataKey);
    }

    public void beforeRepublish(Long voucherId, Long orderId) {
        String dataKey = dataKey(voucherId, orderId);
        Long retryCount = stringRedisTemplate.opsForHash().increment(dataKey, "retryCount", 1);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "REPUBLISHING");
        stringRedisTemplate.opsForHash().put(dataKey, "lastRetryTime", String.valueOf(System.currentTimeMillis()));
        stringRedisTemplate.opsForHash().put(dataKey, "retryCount", String.valueOf(retryCount));
        refreshDataTtl(dataKey);
    }

    public void markFailed(Long voucherId, Long orderId, String reason) {
        long now = System.currentTimeMillis();
        String dataKey = dataKey(voucherId, orderId);
        stringRedisTemplate.opsForZSet().remove(pendingKey(voucherId), orderId.toString());
        stringRedisTemplate.opsForZSet().add(failedKey(voucherId), orderId.toString(), now);
        stringRedisTemplate.opsForHash().put(dataKey, "status", "FAILED");
        stringRedisTemplate.opsForHash().put(dataKey, "lastError", safe(reason));
        stringRedisTemplate.opsForHash().put(dataKey, "failedTime", String.valueOf(now));
        refreshDataTtl(dataKey);
    }

    public Long restorePreDeduct(Long orderId, Long userId, Long voucherId) {
        return stringRedisTemplate.execute(
                RESTORE_SCRIPT,
                Arrays.asList(
                        SeckillRedisKeys.stockKey(voucherId),
                        SeckillRedisKeys.reservationKey(voucherId),
                        SeckillRedisKeys.legacyOrderKey(voucherId),
                        pendingKey(voucherId)
                ),
                userId.toString(),
                orderId.toString()
        );
    }

    public void markConsuming(Long voucherId, Long orderId, long consumeStartedAt) {
        String dataKey = dataKey(voucherId, orderId);
        Object status = stringRedisTemplate.opsForHash().get(dataKey, "status");
        if ("ORDER_CREATED".equals(status) || "FAILED".equals(status)) {
            return;
        }

        stringRedisTemplate.opsForHash().put(dataKey, "status", "CONSUMING");
        stringRedisTemplate.opsForHash().putIfAbsent(dataKey, "consumeStartTime", String.valueOf(consumeStartedAt));
        refreshDataTtl(dataKey);
    }

    public void removePending(Long voucherId, Long orderId) {
        removePending(voucherId, orderId, System.currentTimeMillis());
    }

    public void removePending(Long voucherId, Long orderId, long dbCommittedAt) {
        String dataKey = dataKey(voucherId, orderId);
        Long acceptedAt = readLong(dataKey, "createTime");
        Long consumeStartedAt = readLong(dataKey, "consumeStartTime");

        stringRedisTemplate.opsForZSet().remove(pendingKey(voucherId), orderId.toString());
        stringRedisTemplate.opsForHash().put(dataKey, "status", "ORDER_CREATED");
        stringRedisTemplate.opsForHash().put(dataKey, "dbCommittedTime", String.valueOf(dbCommittedAt));
        if (acceptedAt != null) {
            stringRedisTemplate.opsForHash().put(dataKey, "endToEndMillis", String.valueOf(Math.max(0L, dbCommittedAt - acceptedAt)));
        }
        if (acceptedAt != null && consumeStartedAt != null) {
            stringRedisTemplate.opsForHash().put(dataKey, "queueDelayMillis", String.valueOf(Math.max(0L, consumeStartedAt - acceptedAt)));
        }
        if (consumeStartedAt != null) {
            stringRedisTemplate.opsForHash().put(dataKey, "dbProcessMillis", String.valueOf(Math.max(0L, dbCommittedAt - consumeStartedAt)));
        }
        refreshDataTtl(dataKey);
    }

    public Set<String> listDueOrderIds(Long voucherId, long now, int limit) {
        return stringRedisTemplate.opsForZSet().rangeByScore(pendingKey(voucherId), 0, now, 0, limit);
    }

    public Map<Object, Object> getData(Long voucherId, Long orderId) {
        return stringRedisTemplate.opsForHash().entries(dataKey(voucherId, orderId));
    }

    public String pendingKey(Long voucherId) {
        return SeckillRedisKeys.pendingKey(voucherId);
    }

    public String failedKey(Long voucherId) {
        return SeckillRedisKeys.failedKey(voucherId);
    }

    public String dataKey(Long voucherId, Long orderId) {
        return SeckillRedisKeys.pendingDataKey(voucherId, orderId);
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

    private void refreshDataTtl(String dataKey) {
        stringRedisTemplate.expire(dataKey, Duration.ofSeconds(DATA_TTL_SECONDS));
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
