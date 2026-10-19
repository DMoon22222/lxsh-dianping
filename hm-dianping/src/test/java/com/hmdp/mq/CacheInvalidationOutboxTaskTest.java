package com.hmdp.mq;

import com.hmdp.entity.CacheInvalidationOutbox;
import com.hmdp.mapper.CacheInvalidationOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CacheInvalidationOutboxTaskTest {

    private CacheInvalidationOutboxMapper mapper;
    private CacheInvalidatePublisher publisher;
    private CacheInvalidationOutboxTask task;

    @BeforeEach
    void setUp() {
        mapper = mock(CacheInvalidationOutboxMapper.class);
        publisher = mock(CacheInvalidatePublisher.class);
        task = new CacheInvalidationOutboxTask();
        ReflectionTestUtils.setField(task, "outboxMapper", mapper);
        ReflectionTestUtils.setField(task, "cacheInvalidatePublisher", publisher);
        ReflectionTestUtils.setField(task, "leaseSeconds", 30L);
        ReflectionTestUtils.setField(task, "maxRetries", 8);
        ReflectionTestUtils.setField(task, "maxBackoffSeconds", 60L);
        ReflectionTestUtils.setField(task, "doubleDeleteDelayMs", 1000L);
    }

    @Test
    void successfulInitialStageSchedulesDelayedDelete() {
        CacheInvalidationOutbox event = event(1L, 0, 0);
        when(mapper.claim(eq(1L), any(LocalDateTime.class))).thenReturn(1);

        task.processOne(event);

        verify(publisher).invalidateShop(1L, 9L);
        verify(mapper).scheduleDelayedStage(eq(1L), any(LocalDateTime.class));
    }

    @Test
    void failedInvalidationIsRescheduled() {
        CacheInvalidationOutbox event = event(2L, 0, 0);
        when(mapper.claim(eq(2L), any(LocalDateTime.class))).thenReturn(1);
        doThrow(new CacheInvalidationException("redis failed"))
                .when(publisher).invalidateShop(2L, 9L);

        task.processOne(event);

        verify(mapper).markRetry(
                eq(2L),
                eq(1),
                any(LocalDateTime.class),
                any(String.class));
    }

    private CacheInvalidationOutbox event(Long id, int stage, int retryCount) {
        return new CacheInvalidationOutbox()
                .setId(id)
                .setCacheName("shop")
                .setCacheKey("9")
                .setStage(stage)
                .setStatus(0)
                .setRetryCount(retryCount);
    }
}
