package com.hmdp.mq;

import com.hmdp.cache.ShopCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheInvalidateConsumer {

    @Resource
    private ShopCacheService shopCacheService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @RabbitListener(
            queues = "#{cacheInvalidateQueue.name}",
            containerFactory = "cacheInvalidateRabbitListenerContainerFactory"
    )
    public void onCacheInvalidate(CacheInvalidateMessage cacheMessage) {
        if (!"shop".equals(cacheMessage.getCacheName())) {
            log.warn("忽略未知缓存失效消息，message={}", cacheMessage);
            return;
        }

        Long shopId = Long.valueOf(cacheMessage.getKey());
        List<Throwable> failures = new ArrayList<>();

        try {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        } catch (Exception e) {
            failures.add(e);
            log.error("消费端删除Redis商户缓存失败，shopId={}", shopId, e);
        }

        try {
            shopCacheService.invalidateLocal(shopId);
        } catch (Exception e) {
            failures.add(e);
            log.error("消费端清理Caffeine商户缓存失败，shopId={}", shopId, e);
        }

        if (!failures.isEmpty()) {
            CacheInvalidationException aggregate = new CacheInvalidationException(
                    "消费端商户缓存失效失败，shopId=" + shopId);
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }

        log.info("消费端商户缓存失效完成，shopId={}", shopId);
    }
}
