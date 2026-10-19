package com.hmdp.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

@Slf4j
@Service
public class ShopCacheService {

    @Resource
    private Cache<Long, Shop> shopLocalCache;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${hmdp.cache.shop.local-enabled:true}")
    private boolean shopLocalCacheEnabled;

    public Shop queryShopById(Long id, Function<Long, Shop> dbFallback) {
        if (!shopLocalCacheEnabled) {
            log.info("Caffeine已关闭，直接查询Redis/MySQL，shopId={}", id);
            return queryRedisAndDb(id, dbFallback);
        }

        boolean localCacheAvailable = true;
        try {
            Shop shop = shopLocalCache.getIfPresent(id);
            if (shop != null) {
                log.info("Caffeine命中，shopId={}", id);
                return shop;
            }
        } catch (RuntimeException e) {
            localCacheAvailable = false;
            log.error("读取Caffeine失败，降级到Redis/MySQL，shopId={}", id, e);
        }

        log.info("Caffeine未命中，shopId={}", id);
        if (!localCacheAvailable) {
            return loadAndCacheBestEffort(id, dbFallback);
        }

        AtomicReference<Shop> loadedReference = new AtomicReference<>();
        try {
            return shopLocalCache.get(id, shopId -> {
                try {
                    Shop loaded = queryRedisAndDb(shopId, dbFallback);
                    loadedReference.set(loaded);
                    return loaded;
                } catch (RuntimeException e) {
                    throw new BackendLoadException(e);
                }
            });
        } catch (BackendLoadException e) {
            throw e.getBackendCause();
        } catch (RuntimeException e) {
            log.error("Caffeine加载或回填失败，本次仍返回后端查询结果，shopId={}", id, e);
            Shop loaded = loadedReference.get();
            return loaded != null ? loaded : queryRedisAndDb(id, dbFallback);
        }
    }

    private Shop loadAndCacheBestEffort(Long id, Function<Long, Shop> dbFallback) {
        Shop loaded = queryRedisAndDb(id, dbFallback);
        if (loaded != null) {
            try {
                shopLocalCache.put(id, loaded);
            } catch (RuntimeException e) {
                log.error("回填Caffeine失败，本次仍返回查询结果，shopId={}", id, e);
            }
        }
        return loaded;
    }

    private Shop queryRedisAndDb(Long id, Function<Long, Shop> dbFallback) {
        String redisKey = CACHE_SHOP_KEY + id;
        String json = null;

        try {
            json = stringRedisTemplate.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            log.error("读取Redis商户缓存失败，降级到MySQL，shopId={}", id, e);
        }

        if (StrUtil.isNotBlank(json)) {
            try {
                Shop cachedShop = JSONUtil.toBean(json, Shop.class);
                if (cachedShop != null && cachedShop.getId() != null) {
                    return cachedShop;
                }
            } catch (RuntimeException e) {
                log.warn("商户缓存反序列化失败，将回源数据库，shopId={}，cache={}", id, json, e);
            }
            log.warn("商户缓存内容异常，删除后回源数据库，shopId={}，cache={}", id, json);
            try {
                stringRedisTemplate.delete(redisKey);
            } catch (RuntimeException e) {
                log.error("删除异常Redis商户缓存失败，shopId={}", id, e);
            }
            json = null;
        }

        if (json != null) {
            return null;
        }

        Shop shop = dbFallback.apply(id);
        if (shop == null) {
            try {
                stringRedisTemplate.opsForValue().set(
                        redisKey,
                        "",
                        CACHE_NULL_TTL,
                        TimeUnit.MINUTES
                );
            } catch (RuntimeException e) {
                log.error("回填Redis空值缓存失败，本次仍返回MySQL结果，shopId={}", id, e);
            }
            return null;
        }

        try {
            stringRedisTemplate.opsForValue().set(
                    redisKey,
                    JSONUtil.toJsonStr(shop),
                    randomShopCacheTtlMinutes(),
                    TimeUnit.MINUTES
            );
        } catch (RuntimeException e) {
            log.error("回填Redis商户缓存失败，本次仍返回MySQL结果，shopId={}", id, e);
        }

        return shop;
    }

    public void invalidateLocal(Long id) {
        shopLocalCache.invalidate(id);
    }

    public void invalidateAllLocal() {
        shopLocalCache.invalidateAll();
    }

    private long randomShopCacheTtlMinutes() {
        return CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(0, 11);
    }

    private static final class BackendLoadException extends RuntimeException {
        private final RuntimeException backendCause;

        private BackendLoadException(RuntimeException backendCause) {
            super(backendCause);
            this.backendCause = backendCause;
        }

        private RuntimeException getBackendCause() {
            return backendCause;
        }
    }
}
