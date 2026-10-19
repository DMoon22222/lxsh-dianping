package com.hmdp.mq;

import com.hmdp.entity.CacheInvalidationOutbox;
import com.hmdp.mapper.CacheInvalidationOutboxMapper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class CacheInvalidationOutboxService {

    public static final int STAGE_INITIAL = 0;
    public static final int STAGE_DELAYED = 1;

    public static final int STATUS_PENDING = 0;

    @Resource
    private CacheInvalidationOutboxMapper outboxMapper;

    public void recordShopInvalidation(Long shopId) {
        CacheInvalidationOutbox event = new CacheInvalidationOutbox()
                .setCacheName("shop")
                .setCacheKey(String.valueOf(shopId))
                .setStage(STAGE_INITIAL)
                .setStatus(STATUS_PENDING)
                .setRetryCount(0)
                .setNextRetryTime(LocalDateTime.now());

        int inserted = outboxMapper.insert(event);
        if (inserted != 1) {
            throw new CacheInvalidationException("写入缓存失效Outbox失败，shopId=" + shopId);
        }
    }
}
