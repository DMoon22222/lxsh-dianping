package com.hmdp.mq;

import com.hmdp.entity.CacheInvalidationOutbox;
import com.hmdp.mapper.CacheInvalidationOutboxMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.mq.CacheInvalidationOutboxService.STAGE_INITIAL;

@Slf4j
@Component
public class CacheInvalidationOutboxTask {

    private static final int MAX_ERROR_LENGTH = 512;

    @Resource
    private CacheInvalidationOutboxMapper outboxMapper;

    @Resource
    private CacheInvalidatePublisher cacheInvalidatePublisher;

    @Value("${hmdp.cache.outbox.batch-size:50}")
    private int batchSize;

    @Value("${hmdp.cache.outbox.lease-seconds:30}")
    private long leaseSeconds;

    @Value("${hmdp.cache.outbox.max-retries:8}")
    private int maxRetries;

    @Value("${hmdp.cache.outbox.max-backoff-seconds:60}")
    private long maxBackoffSeconds;

    @Value("${hmdp.cache.outbox.double-delete-delay-ms:1000}")
    private long doubleDeleteDelayMs;

    @Scheduled(fixedDelayString = "${hmdp.cache.outbox.scan-interval-ms:1000}")
    public void processDueEvents() {
        LocalDateTime now = LocalDateTime.now();
        int recovered = outboxMapper.recoverExpiredClaims(now);
        if (recovered > 0) {
            log.warn("已恢复过期的缓存失效Outbox租约，count={}", recovered);
        }

        List<CacheInvalidationOutbox> events = outboxMapper.findDue(now, batchSize);
        for (CacheInvalidationOutbox event : events) {
            processOne(event);
        }
    }

    void processOne(CacheInvalidationOutbox event) {
        LocalDateTime leaseDeadline = LocalDateTime.now().plusSeconds(leaseSeconds);
        if (outboxMapper.claim(event.getId(), leaseDeadline) != 1) {
            return;
        }

        try {
            if (!"shop".equals(event.getCacheName())) {
                throw new CacheInvalidationException("不支持的缓存名称：" + event.getCacheName());
            }

            Long shopId = Long.valueOf(event.getCacheKey());
            cacheInvalidatePublisher.invalidateShop(event.getId(), shopId);

            if (event.getStage() != null && event.getStage() == STAGE_INITIAL) {
                LocalDateTime delayedTime = LocalDateTime.now()
                        .plusNanos(doubleDeleteDelayMs * 1_000_000L);
                outboxMapper.scheduleDelayedStage(event.getId(), delayedTime);
                log.info("商户缓存首次失效完成，已安排延迟双删，eventId={}，shopId={}",
                        event.getId(), shopId);
            } else {
                outboxMapper.markCompleted(event.getId());
                log.info("商户缓存延迟双删完成，eventId={}，shopId={}", event.getId(), shopId);
            }
        } catch (Exception e) {
            handleFailure(event, e);
        }
    }

    private void handleFailure(CacheInvalidationOutbox event, Exception failure) {
        int retryCount = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        String error = abbreviate(failure.toString());

        if (retryCount >= maxRetries) {
            outboxMapper.markDead(event.getId(), retryCount, error);
            log.error("缓存失效Outbox重试耗尽，eventId={}，cacheName={}，cacheKey={}",
                    event.getId(), event.getCacheName(), event.getCacheKey(), failure);
            return;
        }

        long backoffSeconds = Math.min(maxBackoffSeconds, 1L << Math.min(retryCount - 1, 20));
        LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(backoffSeconds);
        outboxMapper.markRetry(event.getId(), retryCount, nextRetryTime, error);
        log.warn("缓存失效Outbox执行失败，将重试，eventId={}，retryCount={}，nextRetryTime={}",
                event.getId(), retryCount, nextRetryTime, failure);
    }

    private String abbreviate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }
}
