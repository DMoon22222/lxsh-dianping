package com.hmdp.mq;

import com.hmdp.cache.ShopCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CacheInvalidatePublisherTest {

    private StringRedisTemplate redisTemplate;
    private ShopCacheService shopCacheService;
    private CacheInvalidateMessagePublisher messagePublisher;
    private CacheInvalidatePublisher publisher;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        shopCacheService = mock(ShopCacheService.class);
        messagePublisher = mock(CacheInvalidateMessagePublisher.class);
        publisher = new CacheInvalidatePublisher();
        ReflectionTestUtils.setField(publisher, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(publisher, "shopCacheService", shopCacheService);
        ReflectionTestUtils.setField(publisher, "messagePublisher", messagePublisher);
    }

    @Test
    void redisFailureDoesNotBlockLocalInvalidationOrMessagePublishing() {
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate).delete("cache:shop:1");

        assertThrows(CacheInvalidationException.class,
                () -> publisher.invalidateShop(10L, 1L));

        verify(shopCacheService).invalidateLocal(1L);
        verify(messagePublisher).send(eq(10L), any(CacheInvalidateMessage.class));
    }

    @Test
    void caffeineFailureDoesNotBlockMessagePublishing() {
        doThrow(new RuntimeException("local cache failure"))
                .when(shopCacheService).invalidateLocal(1L);

        assertThrows(CacheInvalidationException.class,
                () -> publisher.invalidateShop(11L, 1L));

        verify(redisTemplate).delete("cache:shop:1");
        verify(messagePublisher).send(eq(11L), any(CacheInvalidateMessage.class));
    }
}
