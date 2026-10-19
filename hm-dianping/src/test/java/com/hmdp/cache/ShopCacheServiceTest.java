package com.hmdp.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopCacheServiceTest {

    private Cache<Long, Shop> localCache;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private ShopCacheService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        localCache = mock(Cache.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service = new ShopCacheService();
        ReflectionTestUtils.setField(service, "shopLocalCache", localCache);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "shopLocalCacheEnabled", true);
    }

    @Test
    void redisReadAndWriteFailuresStillReturnMysqlResult() {
        Shop mysqlShop = new Shop().setId(1L).setName("new-name");
        when(valueOperations.get("cache:shop:1"))
                .thenThrow(new RuntimeException("redis read failed"));
        doThrow(new RuntimeException("redis write failed"))
                .when(valueOperations)
                .set(anyString(), anyString(), anyLong(), eq(TimeUnit.MINUTES));
        when(localCache.get(eq(1L), org.mockito.ArgumentMatchers.<Function<Long, Shop>>any()))
                .thenAnswer(invocation -> {
                    Function<Long, Shop> loader = invocation.getArgument(1);
                    return loader.apply(1L);
                });

        Shop result = service.queryShopById(1L, id -> mysqlShop);

        assertSame(mysqlShop, result);
        verify(localCache).get(eq(1L), org.mockito.ArgumentMatchers.<Function<Long, Shop>>any());
    }

    @Test
    void caffeineReadFailureFallsBackToRedisAndMysql() {
        Shop mysqlShop = new Shop().setId(2L).setName("shop-2");
        when(localCache.getIfPresent(2L))
                .thenThrow(new RuntimeException("caffeine read failed"));
        when(valueOperations.get("cache:shop:2")).thenReturn(null);

        Shop result = service.queryShopById(2L, id -> mysqlShop);

        assertSame(mysqlShop, result);
        verify(localCache).put(2L, mysqlShop);
    }
}
