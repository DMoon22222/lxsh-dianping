package com.hmdp.mq;

import com.hmdp.cache.ShopCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheInvalidatePublisher {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopCacheService shopCacheService;

    @Resource
    private CacheInvalidateMessagePublisher messagePublisher;

    public void invalidateShop(Long eventId, Long shopId) {
        List<Throwable> failures = new ArrayList<>();
        String redisKey = CACHE_SHOP_KEY + shopId;

        try {
            stringRedisTemplate.delete(redisKey);
        } catch (Exception e) {
            failures.add(e);
            log.error("删除Redis商户缓存失败，eventId={}，shopId={}", eventId, shopId, e);
        }

        try {
            shopCacheService.invalidateLocal(shopId);
        } catch (Exception e) {
            failures.add(e);
            log.error("清理本机Caffeine商户缓存失败，eventId={}，shopId={}", eventId, shopId, e);
        }

        try {
            CacheInvalidateMessage message =
                    new CacheInvalidateMessage("shop", String.valueOf(shopId));
            messagePublisher.send(eventId, message);
        } catch (Exception e) {
            failures.add(e);
            log.error("发布商户缓存失效消息失败，eventId={}，shopId={}", eventId, shopId, e);
        }

        if (!failures.isEmpty()) {
            CacheInvalidationException aggregate = new CacheInvalidationException(
                    "商户缓存失效存在失败步骤，eventId=" + eventId + "，shopId=" + shopId);
            failures.forEach(aggregate::addSuppressed);
            throw aggregate;
        }

        log.info("商户缓存失效执行成功，eventId={}，shopId={}", eventId, shopId);
    }
}
